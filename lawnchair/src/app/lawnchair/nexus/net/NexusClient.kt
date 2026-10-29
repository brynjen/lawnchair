package app.lawnchair.nexus.net

import app.lawnchair.nexus.box.SubstrateFrame
import app.lawnchair.nexus.box.parseSubstrateLanes
import app.lawnchair.nexus.news.NewsFeed
import app.lawnchair.nexus.news.parseNewsFeed
import app.lawnchair.nexus.news.resolveNewsImageUrl
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject

/**
 * Kotlin re-implementation of the in-app assistant transport (the VoiceSquad + Audio endpoints) over
 * the raw Serverpod wire protocol. Unary calls are plain JSON-over-HTTP POSTs; streamed answers and
 * transcription are method-stream WebSockets. See `docs/nexus-search-bar-plan.md` for the confirmed
 * wire facts.
 *
 * All calls carry the signed-in person's session key: unary as the `Authorization` header, the
 * WebSocket in the `omsc` `auth` field. An empty key is omitted. A 401 on a unary call is a
 * [NexusUnauthorizedException].
 */
class NexusClient(
    private val http: OkHttpClient = defaultHttpClient(),
) : NexusTransport {

    // region unary

    /** `POST /health {"method":"ping"}` → `"nexus-ok"`. Backs reachability. */
    suspend fun ping(base: String, token: String): Boolean = runCatching {
        val body = post(base, "/health", token, JSONObject().put("method", "ping"))
        body.trim().trim('"') == PING_OK
    }.getOrDefault(false)

    /** `POST /presence {"method":"heartbeat"}` → `PresenceAck`. Null on any failure. */
    suspend fun heartbeat(base: String, token: String): PresenceAck? = runCatching {
        val body = post(base, "/presence", token, JSONObject().put("method", "heartbeat"))
        val o = JSONObject(body)
        PresenceAck(
            ok = o.optBoolean("ok", false),
            serverTime = o.optString("serverTime", null),
            whisperReady = o.optBoolean("whisperReady", false),
            ttsReady = o.optBoolean("ttsReady", false),
            llmReady = o.optBoolean("llmReady", false),
            allReady = o.optBoolean("allReady", false),
        )
    }.getOrNull()

    /**
     * `POST /voiceSquad {"method":"createConversation","title":null}` → conversation id. With a
     * [subject] the title is the article's and `subjectKind`/`subjectId` name it.
     */
    override suspend fun createConversation(base: String, token: String, subject: ChatSubject?): Int {
        val request = JSONObject().put("method", "createConversation")
        if (subject == null) {
            request.put("title", JSONObject.NULL)
        } else {
            request.put("title", subject.title).put("subjectKind", subject.kind).put("subjectId", subject.id)
        }
        return post(base, "/voiceSquad", token, request).trim().toInt()
    }

    /** `POST /voiceSquad {"method":"readyCue","language"?}` → the line's audio, or null when the answer has none. */
    override suspend fun readyCue(base: String, token: String, language: String?): ByteArray? {
        val request = JSONObject().put("method", "readyCue")
        if (language != null) request.put("language", language)
        val b64 = JSONObject(post(base, "/voiceSquad", token, request)).optString("audioDataBase64", "")
        return b64.takeIf { it.isNotEmpty() }?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
    }

    /** `POST /voiceSquad {conversationId, userText, turnId, voice?, method:"submitTurn"}` → void. */
    override suspend fun submitTurn(
        base: String,
        token: String,
        conversationId: Int,
        userText: String,
        turnId: String,
        voice: Boolean?,
    ) {
        val request = JSONObject()
            .put("conversationId", conversationId)
            .put("userText", userText)
            .put("turnId", turnId)
            .put("method", "submitTurn")
        if (voice != null) request.put("voice", voice)
        post(base, "/voiceSquad", token, request)
    }

    /** `POST /voiceSquad {conversationId, promptId, answer, method:"answerUserPrompt"}` → void. */
    override suspend fun answerUserPrompt(
        base: String,
        token: String,
        conversationId: Int,
        promptId: String,
        answer: String,
    ) {
        post(
            base,
            "/voiceSquad",
            token,
            JSONObject()
                .put("conversationId", conversationId)
                .put("promptId", promptId)
                .put("answer", answer)
                .put("method", "answerUserPrompt"),
        )
    }

    /** `POST /voiceSquad {conversationId, heardThroughSegmentIndex, method:"cancelTurn"}`. */
    override suspend fun cancelTurn(
        base: String,
        token: String,
        conversationId: Int,
        heardThroughSegmentIndex: Int?,
    ) {
        runCatching {
            post(
                base,
                "/voiceSquad",
                token,
                JSONObject()
                    .put("conversationId", conversationId)
                    .put(
                        "heardThroughSegmentIndex",
                        heardThroughSegmentIndex ?: JSONObject.NULL,
                    )
                    .put("method", "cancelTurn"),
            )
        }
    }

    /** `POST /news {"method":"feed","limit":n,"cursor"?,"section"?,"query"?}` → the server's `NewsFeed`; a 401 is a [NexusUnauthorizedException]. */
    override suspend fun newsFeed(base: String, token: String, limit: Int, cursor: String?, section: String?, query: String?): NewsFeed {
        val request = JSONObject().put("method", "feed").put("limit", limit)
        if (cursor != null) request.put("cursor", cursor)
        if (section != null) request.put("section", section)
        if (query != null) request.put("query", query)
        return readNews(post(base, "/news", token, request))
    }

    /** `POST /news {"method":"refresh"}` → a sweep of the sources, then the same `NewsFeed`. Slow. */
    override suspend fun newsRefresh(base: String, token: String): NewsFeed =
        readNews(post(base, "/news", token, JSONObject().put("method", "refresh")))

    /** `POST /news {"method":"setFavorite","id":n,"favorite":b}` → whether the article is starred now (`true` / `false`). */
    override suspend fun newsFavorite(base: String, token: String, id: Long, favorite: Boolean): Boolean {
        val request = JSONObject().put("method", "setFavorite").put("id", id).put("favorite", favorite)
        return post(base, "/news", token, request).trim().toBooleanStrictOrNull()
            ?: throw java.io.IOException("Unreadable answer to setFavorite")
    }

    /** `POST /news {"method":"setRead","id":n,"read":b}` → whether the article is marked read now. */
    override suspend fun newsRead(base: String, token: String, id: Long, read: Boolean): Boolean {
        val request = JSONObject().put("method", "setRead").put("id", id).put("read", read)
        return post(base, "/news", token, request).trim().toBooleanStrictOrNull()
            ?: throw java.io.IOException("Unreadable answer to setRead")
    }

    private fun readNews(body: String): NewsFeed =
        parseNewsFeed(body) ?: throw java.io.IOException("Unreadable news feed")

    /**
     * `GET base+path` with the session key → the picture's bytes (a news picture, `/uploads/system/news/...`).
     * The path must resolve to this server ([resolveNewsImageUrl]); a 401 is a [NexusUnauthorizedException].
     */
    override suspend fun newsImage(base: String, token: String, path: String): ByteArray {
        val url = resolveNewsImageUrl(base, path) ?: throw java.io.IOException("Not a picture of this server: $path")
        val request = Request.Builder()
            .url(url)
            .apply { if (token.isNotEmpty()) header("Authorization", bearer(token)) }
            .get()
            .build()
        return execute(request, path) { it.bytes() }
    }

    private suspend fun post(
        base: String,
        endpoint: String,
        token: String,
        json: JSONObject,
    ): String {
        val request = Request.Builder()
            .url(unaryUrl(base, endpoint))
            .apply { if (token.isNotEmpty()) header("Authorization", bearer(token)) }
            .post(json.toString().toRequestBody(JSON_MEDIA))
            .build()
        return execute(request, endpoint) { it.string() }
    }

    /** Runs [request]; [read] turns a successful body into the result, a 401 is [NexusUnauthorizedException]. */
    private suspend fun <T> execute(request: Request, what: String, read: (ResponseBody) -> T): T =
        suspendCancellableCoroutine { cont ->
            val call = http.newCall(request)
            cont.invokeOnCancellation { runCatching { call.cancel() } }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: java.io.IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (!cont.isActive) return
                        if (it.code == 401) {
                            cont.resumeWithException(NexusUnauthorizedException())
                        } else if (!it.isSuccessful) {
                            val text = it.body?.string().orEmpty().take(300)
                            cont.resumeWithException(java.io.IOException("HTTP ${it.code} for $what: $text"))
                        } else {
                            val result = runCatching { read(it.body) }
                            result.exceptionOrNull()?.let { e -> cont.resumeWithException(e) }
                                ?: cont.resume(result.getOrThrow())
                        }
                    }
                }
            })
        }

    // endregion

    // region streaming — method streams

    /**
     * Opens the method stream [endpoint].[method] on `/v1/websocket` and emits what it says: each
     * streamed value as a [MethodStreamEvent.Message], and, if the stream ends without the collector
     * having asked, one [MethodStreamEvent.Ended] with the reason, after which the flow completes (or
     * fails with the socket's error). [onReady] fires, on the socket's thread, once the server
     * acknowledges the stream (`omsr res:success`). Cancelling the collection sends a `cmsc` close
     * command and closes the socket.
     *
     * The `omsc` call, ping/pong and the close frames live here and nowhere else, so when the app
     * clients are sealed (docs/client-sealing.md, C2) the codec lands in this one function.
     */
    private fun openMethodStream(
        base: String,
        token: String,
        endpoint: String,
        method: String,
        args: JSONObject = JSONObject(),
        onReady: () -> Unit = {},
    ): Flow<MethodStreamEvent> =
        callbackFlow {
            val cid = UUID.randomUUID().toString()
            var closed = false

            fun sendClose(ws: WebSocket) {
                runCatching { ws.send(buildCmsc(cid, endpoint, method).toString()) }
                runCatching { ws.close(1000, null) }
            }

            fun end(ws: WebSocket, reason: String) {
                trySend(MethodStreamEvent.Ended(reason))
                closed = true
                sendClose(ws)
                close()
            }

            val listener = object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, response: Response) {
                    ws.send(buildOmsc(cid, endpoint, method, args.toString(), token).toString())
                }

                override fun onMessage(ws: WebSocket, text: String) {
                    val msg = runCatching { JSONObject(text) }.getOrNull() ?: return
                    when (msg.optString("type")) {
                        "ping" -> ws.send(
                            JSONObject().put("type", "pong").put("data", JSONObject()).toString(),
                        )

                        "omsr" -> {
                            val res = msg.optJSONObject("data")?.optString("res")
                            if (res == "success") {
                                onReady()
                            } else if (res != null) {
                                end(ws, "stream rejected: $res")
                            }
                        }

                        "msm" -> msg.optJSONObject("data")?.optJSONObject("o")?.optJSONObject("data")
                            ?.let { trySend(MethodStreamEvent.Message(it)) }

                        "msse", "brm" -> end(ws, msg.optJSONObject("data")?.toString() ?: "stream error")

                        // The server ended our method stream (a rejected token ends it this way,
                        // after omsr success). Without this the turn waited for the watchdog.
                        "cmsc" -> if (!closed && msg.optJSONObject("data")?.optString("cid") == cid) {
                            trySend(MethodStreamEvent.Ended("Nexus closed the stream."))
                            closed = true
                            runCatching { ws.close(1000, null) }
                            close()
                        }
                    }
                }

                override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                    if (!closed) trySend(MethodStreamEvent.Ended(t.message ?: "socket failure"))
                    close(t)
                }

                override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                    ws.close(1000, null)
                    if (!closed) {
                        val why = listOf(code.toString(), reason).filter { it.isNotBlank() }.joinToString(" ")
                        trySend(MethodStreamEvent.Ended("The connection to Nexus closed ($why)."))
                        closed = true
                    }
                    close()
                }
            }

            val request = Request.Builder().url(webSocketUrl(base)).build()
            val ws = http.newWebSocket(request, listener)

            awaitClose {
                if (!closed) sendClose(ws)
                runCatching { ws.cancel() }
            }
        }

    // endregion

    // region streaming — turn

    /**
     * Opens a `streamTurn` method-stream and emits [NexusTurnEvent]s as they arrive. [onReady] fires
     * once the server acknowledges the stream (`omsr res:success`) — the caller must `POST submitTurn`
     * from there so the turn is only triggered after the handshake completes. The flow completes on
     * the terminal `completed`/`failed` event; cancelling the collection closes the socket (a `cmsc`
     * close command is sent first).
     */
    override fun streamTurn(
        base: String,
        token: String,
        conversationId: Int,
        onReady: () -> Unit,
    ): Flow<NexusTurnEvent> =
        openMethodStream(
            base,
            token,
            ENDPOINT_VOICE,
            METHOD_STREAM_TURN,
            JSONObject().put("conversationId", conversationId),
            onReady,
        ).transformWhile { ev ->
            when (ev) {
                is MethodStreamEvent.Message -> {
                    val event = parseTurnEvent(ev.data)
                    if (event != null) emit(event)
                    event !is NexusTurnEvent.Completed && event !is NexusTurnEvent.Failed
                }

                is MethodStreamEvent.Ended -> {
                    emit(NexusTurnEvent.Failed(ev.reason))
                    false
                }
            }
        }

    override fun watchConversation(base: String, token: String, conversationId: Int): Flow<NexusTurnEvent> =
        openMethodStream(
            base,
            token,
            ENDPOINT_VOICE,
            METHOD_STREAM_TURN,
            JSONObject().put("conversationId", conversationId),
            onReady = {},
        ).transformWhile { ev ->
            when (ev) {
                is MethodStreamEvent.Message -> {
                    parseTurnEvent(ev.data)?.let { emit(it) }
                    true
                }

                is MethodStreamEvent.Ended -> {
                    emit(NexusTurnEvent.Failed(ev.reason))
                    false
                }
            }
        }

    /** Read a stream message's `data.o.data` → a [NexusTurnEvent], or null for ignored kinds. */
    private fun parseTurnEvent(data: JSONObject): NexusTurnEvent? {
        val turnId = data.optString("turnId", "").ifEmpty { null }
        return when (data.optString("kind")) {
            "textDelta" -> NexusTurnEvent.TextDelta(data.optString("textDelta", ""), turnId)
            "narratorChunk" -> NexusTurnEvent.Narrator(
                text = data.optString("narratorText", ""),
                narratorKind = data.optString("narratorKind", "").ifEmpty { null },
                audible = if (data.has("audible")) data.optBoolean("audible") else null,
                toolName = data.optString("toolName", "").ifEmpty { null },
                turnId = turnId,
            )
            "searchSources" -> {
                val arr = data.optJSONArray("sources") ?: JSONArray()
                val sources = (0 until arr.length()).mapNotNull { i ->
                    arr.optJSONObject(i)?.let {
                        NexusSource(
                            title = it.optString("title", it.optString("url", "")),
                            url = it.optString("url", ""),
                        )
                    }
                }
                NexusTurnEvent.Sources(sources, turnId)
            }
            "audio" -> {
                val b64 = data.optString("audioDataBase64", "")
                val bytes = runCatching { Base64.getDecoder().decode(b64) }.getOrNull()
                    ?: return null
                NexusTurnEvent.Audio(
                    opusBytes = bytes,
                    segmentIndex = data.optInt("segmentIndex", 0),
                    durationMs = data.optInt("durationMs", 0),
                    isFinal = data.optBoolean("isFinal", false),
                    turnId = turnId,
                )
            }
            "image" -> data.optString("imageUrl", "").ifEmpty { null }
                ?.let { NexusTurnEvent.Image(it, turnId) }
            "userPromptRequest" -> data.optString("promptId", "").ifEmpty { null }?.let {
                NexusTurnEvent.UserPrompt(it, data.optString("question", ""), turnId)
            }
            "completed" -> NexusTurnEvent.Completed(turnId)
            "failed" -> NexusTurnEvent.Failed(data.optString("errorMessage", "turn failed"), turnId)
            else -> null // heartbeat — nothing to show
        }
    }

    // endregion

    // region streaming — substrate

    /**
     * Opens `substrate.lanes`: the box's eight lanes as the orb draws them, one digit each, a whole
     * snapshot per packet (the server sends one only when a lane changes, at most 10 a second, and
     * repeats it every 20 s). Only the newest matters, so a slow collector gets the latest
     * ([conflate]) rather than a backlog. A packet that does not parse is dropped; the flow completes
     * when the stream ends and fails when the socket does.
     */
    override fun watchBox(base: String, token: String): Flow<SubstrateFrame> =
        openMethodStream(base, token, ENDPOINT_SUBSTRATE, METHOD_LANES)
            .conflate()
            .transformWhile { ev ->
                if (ev is MethodStreamEvent.Message) parseSubstrateLanes(ev.data.toString())?.let { emit(it) }
                ev is MethodStreamEvent.Message
            }

    // endregion

    // region streaming — transcription (STT)

    /**
     * Streams mic PCM16 to `audio.sendAudioChunk` and emits transcription events.
     *
     * Serverpod models each chunk as its own server-stream method call correlated by `recordingId`:
     * chunk 0 opens the whisper socket and stays open to yield the transcript; chunks >0 push bytes
     * and return after `chunk_received`. All calls are multiplexed over ONE WebSocket (distinct
     * `cid`s) so frame ordering is preserved — critical, since whisper needs chunk 0 first.
     *
     * The chunk-0 gate (mirrors `server_speech_to_text_datasource.dart`): buffer chunks >0 until
     * chunk 0's first response arrives, then flush. When [pcm16Chunks] completes, a final
     * `isLastChunk` request (empty payload) is sent so whisper finalizes.
     *
     * NOTE: unverified against a live server (server was down at authoring time); the wire shape
     * follows the documented envelope. Verified end-to-end via [FakeNexusTransport] until then.
     */
    override fun streamTranscription(
        base: String,
        token: String,
        recordingId: String,
        sessionId: String,
        pcm16Chunks: Flow<ByteArray>,
        language: String?,
    ): Flow<TranscriptionEvent> = callbackFlow {
        val chunk0Cid = UUID.randomUUID().toString()
        val wsReady = CompletableDeferred<WebSocket>()
        val gateOpen = CompletableDeferred<Unit>()
        var closed = false
        var chunkCount = 0

        fun request(index: Int, base64: String, isLast: Boolean): String {
            val req = JSONObject()
                .put("audioChunkBase64", base64)
                .put("recordingId", recordingId)
                .put("chunkIndex", index)
                .put("isLastChunk", isLast)
                .put("sessionId", sessionId)
                .put("userMessageId", JSONObject.NULL)
                .put("language", language ?: JSONObject.NULL)
                .put("format", "pcm")
            return JSONObject().put("request", req).toString()
        }

        // Each chunk >0 / EOF gets its own short-lived cid on the same socket.
        fun sendChunk(ws: WebSocket, index: Int, bytes: ByteArray, isLast: Boolean) {
            val cid = if (index == 0) chunk0Cid else UUID.randomUUID().toString()
            val b64 = if (bytes.isEmpty()) "" else Base64.getEncoder().encodeToString(bytes)
            ws.send(buildOmsc(cid, ENDPOINT_AUDIO, METHOD_SEND_AUDIO_CHUNK, request(index, b64, isLast), token).toString())
        }

        val listener = object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                wsReady.complete(ws)
            }

            override fun onMessage(ws: WebSocket, text: String) {
                val msg = runCatching { JSONObject(text) }.getOrNull() ?: return
                when (msg.optString("type")) {
                    "ping" -> ws.send(JSONObject().put("type", "pong").put("data", JSONObject()).toString())
                    "msm" -> {
                        val cid = msg.optJSONObject("data")?.optString("cid")
                        if (cid != chunk0Cid) {
                            // A >0 / EOF chunk's `chunk_received` — nothing to surface.
                            return
                        }
                        val payload = msg.optJSONObject("data")?.optJSONObject("o")?.optJSONObject("data")
                            ?: return
                        // First response on chunk 0 opens the gate for buffered chunks.
                        if (!gateOpen.isCompleted) gateOpen.complete(Unit)
                        val event = payload.optString("event")
                        when (event) {
                            "chunk_received", "eof_sent" -> Unit // control frames
                            "error" -> {
                                trySend(TranscriptionEvent("", isFinal = true, isError = true, errorMessage = payload.optString("text").ifEmpty { "transcription error" }))
                                closed = true
                                close()
                            }
                            else -> {
                                val txt = payload.optString("text", "")
                                val isFinal = payload.optBoolean("isFinal", false)
                                trySend(TranscriptionEvent(txt, isFinal = isFinal))
                                if (isFinal) {
                                    closed = true
                                    close()
                                }
                            }
                        }
                    }
                    "msse", "brm" -> {
                        trySend(TranscriptionEvent("", isFinal = true, isError = true, errorMessage = "stream error"))
                        closed = true
                        close()
                    }
                }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                if (!closed) trySend(TranscriptionEvent("", isFinal = true, isError = true, errorMessage = t.message ?: "socket failure"))
                close(t)
            }
        }

        val request = Request.Builder().url(webSocketUrl(base)).build()
        val ws = http.newWebSocket(request, listener)

        // Feed pcm chunks: chunk 0 first, buffer the rest until the gate, then flush + stream.
        val feeder = launch {
            val socket = wsReady.await()
            val buffered = ArrayList<ByteArray>()
            var index = 0
            pcm16Chunks
                .onCompletion {
                    // EOF sentinel so whisper finalizes.
                    runCatching { sendChunk(socket, chunkCount, ByteArray(0), isLast = true) }
                }
                .collect { bytes ->
                    if (index == 0) {
                        sendChunk(socket, 0, bytes, isLast = false)
                        chunkCount = 1
                        index = 1
                    } else if (!gateOpen.isCompleted) {
                        buffered.add(bytes)
                    } else {
                        if (buffered.isNotEmpty()) {
                            buffered.forEach { sendChunk(socket, chunkCount++, it, isLast = false) }
                            buffered.clear()
                        }
                        sendChunk(socket, chunkCount++, bytes, isLast = false)
                    }
                }
        }

        awaitClose {
            feeder.cancel()
            runCatching { ws.cancel() }
        }
    }

    // endregion

    companion object {
        private const val PING_OK = "nexus-ok"
        private const val ENDPOINT_VOICE = "voiceSquad"
        private const val ENDPOINT_AUDIO = "audio"
        private const val ENDPOINT_SUBSTRATE = "substrate"
        private const val METHOD_STREAM_TURN = "streamTurn"
        private const val METHOD_SEND_AUDIO_CHUNK = "sendAudioChunk"
        private const val METHOD_LANES = "lanes"
        private val JSON_MEDIA = "application/json".toMediaType()

        /** Build an `omsc` (open method-stream command) frame. [args] is a JSON-encoded STRING. */
        private fun buildOmsc(
            cid: String,
            endpoint: String,
            method: String,
            args: String,
            token: String,
        ): JSONObject {
            val data = JSONObject()
                .put("en", endpoint)
                .put("m", method)
                .put("cid", cid)
                .put("args", args) // JSON-encoded STRING, not a nested object
                .put("is", JSONArray())
            if (token.isNotEmpty()) data.put("auth", bearer(token))
            return JSONObject().put("type", "omsc").put("data", data)
        }

        /** Build a `cmsc` (close method-stream command) frame. */
        private fun buildCmsc(cid: String, endpoint: String, method: String): JSONObject =
            JSONObject().put("type", "cmsc").put(
                "data",
                JSONObject().put("cid", cid).put("en", endpoint).put("m", method),
            )

        /**
         * Wrap the key as a Bearer value (`Bearer <key>`). Serverpod unwraps it to the raw
         * key and checks it against the person's session. Bearer (not Basic) because with
         * `validateHeaders` on (the default) both the HTTP (Relic typed parser) and the WS
         * (isValidAuthHeaderValue) paths require a valid scheme, and a `Basic` value whose base64
         * isn't `user:pass` is rejected ("Invalid basic token format" → 400).
         */
        private fun bearer(token: String): String = "Bearer $token"

        /** `{base}{endpoint}` with exactly one slash and no trailing slash on the endpoint. */
        fun unaryUrl(base: String, endpoint: String): String =
            base.trimEnd('/') + "/" + endpoint.trimStart('/')

        /** Derive `ws(s)://host:port/v1/websocket` from an `http(s)://host:port/…` base. */
        fun webSocketUrl(base: String): String {
            val trimmed = base.trim().trimEnd('/')
            val authority = trimmed
                .substringAfter("://", trimmed)
                .substringBefore('/')
            val scheme = if (trimmed.startsWith("https")) "wss" else "ws"
            return "$scheme://$authority/v1/websocket"
        }

        private fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS) // streaming socket must not time out
            .pingInterval(20, TimeUnit.SECONDS)
            // Serverpod 4's server answers OkHttp's permessage-deflate offer with a
            // client_max_window_bits the client never offered (RFC 7692 forbids it), and OkHttp
            // closes the socket with 1010 — every turn and transcription stream died at the
            // handshake. The frames are small JSON; don't offer compression. An application
            // interceptor, because OkHttp skips network interceptors on WebSocket calls.
            .addInterceptor { chain ->
                val request = chain.request()
                chain.proceed(
                    if (request.header("Upgrade").equals("websocket", ignoreCase = true)) {
                        request.newBuilder().removeHeader("Sec-WebSocket-Extensions").build()
                    } else {
                        request
                    },
                )
            }
            .build()
    }
}
