package app.lawnchair.nexus.net

import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
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
 * All calls carry the interim shared access token: unary as the `Authorization` header, the
 * WebSocket in the `omsc` `auth` field. An empty token is omitted.
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

    /** `POST /voiceSquad {"method":"createConversation","title":null}` → conversation id. */
    override suspend fun createConversation(base: String, token: String): Int {
        val body = post(
            base,
            "/voiceSquad",
            token,
            JSONObject().put("method", "createConversation").put("title", JSONObject.NULL),
        )
        return body.trim().toInt()
    }

    /** `POST /voiceSquad {conversationId, userText, turnId, method:"submitTurn"}` → void. */
    override suspend fun submitTurn(
        base: String,
        token: String,
        conversationId: Int,
        userText: String,
        turnId: String,
    ) {
        post(
            base,
            "/voiceSquad",
            token,
            JSONObject()
                .put("conversationId", conversationId)
                .put("userText", userText)
                .put("turnId", turnId)
                .put("method", "submitTurn"),
        )
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

    private suspend fun post(
        base: String,
        endpoint: String,
        token: String,
        json: JSONObject,
    ): String = suspendCancellableCoroutine { cont ->
        val request = Request.Builder()
            .url(unaryUrl(base, endpoint))
            .apply { if (token.isNotEmpty()) header("Authorization", bearer(token)) }
            .post(json.toString().toRequestBody(JSON_MEDIA))
            .build()
        val call = http.newCall(request)
        cont.invokeOnCancellation { runCatching { call.cancel() } }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!cont.isActive) return
                    val text = it.body?.string().orEmpty()
                    if (!it.isSuccessful) {
                        cont.resumeWithException(
                            java.io.IOException("HTTP ${it.code} for $endpoint: $text"),
                        )
                    } else {
                        cont.resume(text)
                    }
                }
            }
        })
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
        callbackFlow {
            val cid = UUID.randomUUID().toString()
            var closed = false

            fun sendClose(ws: WebSocket) {
                runCatching { ws.send(buildCmsc(cid, ENDPOINT_VOICE, METHOD_STREAM_TURN).toString()) }
                runCatching { ws.close(1000, null) }
            }

            val listener = object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, response: Response) {
                    val args = JSONObject().put("conversationId", conversationId).toString()
                    ws.send(buildOmsc(cid, ENDPOINT_VOICE, METHOD_STREAM_TURN, args, token).toString())
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
                                trySend(NexusTurnEvent.Failed("stream rejected: $res"))
                                closed = true
                                sendClose(ws)
                                close()
                            }
                        }

                        "msm" -> {
                            val event = parseTurnEvent(msg) ?: return
                            trySend(event)
                            if (event is NexusTurnEvent.Completed || event is NexusTurnEvent.Failed) {
                                closed = true
                                sendClose(ws)
                                close()
                            }
                        }

                        "msse", "brm" -> {
                            trySend(NexusTurnEvent.Failed(msg.optJSONObject("data")?.toString() ?: "stream error"))
                            closed = true
                            sendClose(ws)
                            close()
                        }

                        // The server ended our method stream (a rejected token ends it this way,
                        // after omsr success). Without this the turn waited for the watchdog.
                        "cmsc" -> if (!closed && msg.optJSONObject("data")?.optString("cid") == cid) {
                            trySend(NexusTurnEvent.Failed("Nexus closed the stream."))
                            closed = true
                            runCatching { ws.close(1000, null) }
                            close()
                        }
                    }
                }

                override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                    if (!closed) trySend(NexusTurnEvent.Failed(t.message ?: "socket failure"))
                    close(t)
                }

                override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                    ws.close(1000, null)
                    if (!closed) {
                        val why = listOf(code.toString(), reason).filter { it.isNotBlank() }.joinToString(" ")
                        trySend(NexusTurnEvent.Failed("The connection to Nexus closed ($why)."))
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

    /** Unwrap a `msm` frame → `data.o.data` → a [NexusTurnEvent], or null for ignored kinds. */
    private fun parseTurnEvent(msg: JSONObject): NexusTurnEvent? {
        val data = msg.optJSONObject("data")?.optJSONObject("o")?.optJSONObject("data") ?: return null
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
        private const val METHOD_STREAM_TURN = "streamTurn"
        private const val METHOD_SEND_AUDIO_CHUNK = "sendAudioChunk"
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
         * Wrap the token as a Bearer value (`Bearer <token>`). Serverpod unwraps it to the raw
         * token; the gate compares that to NEXUS_ACCESS_TOKEN. Bearer (not Basic) because with
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
