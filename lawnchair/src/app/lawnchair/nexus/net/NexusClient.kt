package app.lawnchair.nexus.net

import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
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
 * Kotlin re-implementation of the in-app assistant transport (the VoiceSquad endpoint) over the
 * raw Serverpod wire protocol. Unary calls are plain JSON-over-HTTP POSTs; the streamed answer is a
 * method-stream WebSocket. See `docs/nexus-search-bar-plan.md` for the confirmed wire facts.
 *
 * All calls carry the interim shared access token: unary as the `Authorization` header, the
 * WebSocket in the `omsc` `auth` field. An empty token is omitted.
 */
class NexusClient(
    private val http: OkHttpClient = defaultHttpClient(),
) {

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
            ollamaReady = o.optBoolean("ollamaReady", false),
            allReady = o.optBoolean("allReady", false),
        )
    }.getOrNull()

    /** `POST /voiceSquad {"method":"createConversation","title":null}` → conversation id. */
    suspend fun createConversation(base: String, token: String): Int {
        val body = post(
            base,
            "/voiceSquad",
            token,
            JSONObject().put("method", "createConversation").put("title", JSONObject.NULL),
        )
        return body.trim().toInt()
    }

    /** `POST /voiceSquad {conversationId, userText, turnId:null, method:"submitTurn"}` → void. */
    suspend fun submitTurn(base: String, token: String, conversationId: Int, userText: String) {
        post(
            base,
            "/voiceSquad",
            token,
            JSONObject()
                .put("conversationId", conversationId)
                .put("userText", userText)
                .put("turnId", JSONObject.NULL)
                .put("method", "submitTurn"),
        )
    }

    /** `POST /voiceSquad {conversationId, heardThroughSegmentIndex:null, method:"cancelTurn"}`. */
    suspend fun cancelTurn(base: String, token: String, conversationId: Int) {
        runCatching {
            post(
                base,
                "/voiceSquad",
                token,
                JSONObject()
                    .put("conversationId", conversationId)
                    .put("heardThroughSegmentIndex", JSONObject.NULL)
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
            .apply { if (token.isNotEmpty()) header("Authorization", basicAuth(token)) }
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

    // region streaming

    /**
     * Opens a `streamTurn` method-stream and emits [NexusTurnEvent]s as they arrive. [onReady] fires
     * once the server acknowledges the stream (`omsr res:success`) — the caller must `POST submitTurn`
     * from there so the turn is only triggered after the handshake completes. The flow completes on
     * the terminal `completed`/`failed` event; cancelling the collection closes the socket (a `cmsc`
     * close command is sent first).
     */
    fun streamTurn(
        base: String,
        token: String,
        conversationId: Int,
        onReady: () -> Unit = {},
    ): Flow<NexusTurnEvent> =
        callbackFlow {
            val cid = UUID.randomUUID().toString()
            var closed = false

            fun sendClose(ws: WebSocket) {
                val cmsc = JSONObject().put("type", "cmsc").put(
                    "data",
                    JSONObject().put("cid", cid).put("en", ENDPOINT).put("m", METHOD_STREAM_TURN),
                )
                runCatching { ws.send(cmsc.toString()) }
                runCatching { ws.close(1000, null) }
            }

            val listener = object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, response: Response) {
                    val args = JSONObject().put("conversationId", conversationId).toString()
                    val data = JSONObject()
                        .put("en", ENDPOINT)
                        .put("m", METHOD_STREAM_TURN)
                        .put("cid", cid)
                        .put("args", args) // JSON-encoded STRING, not a nested object
                        .put("is", JSONArray())
                    if (token.isNotEmpty()) data.put("auth", token)
                    ws.send(JSONObject().put("type", "omsc").put("data", data).toString())
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
                    }
                }

                override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                    if (!closed) trySend(NexusTurnEvent.Failed(t.message ?: "socket failure"))
                    close(t)
                }

                override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                    ws.close(1000, null)
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
        return when (data.optString("kind")) {
            "textDelta" -> NexusTurnEvent.TextDelta(data.optString("textDelta", ""))
            "narratorChunk" -> NexusTurnEvent.Narrator(data.optString("narratorText", ""))
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
                NexusTurnEvent.Sources(sources)
            }
            "completed" -> NexusTurnEvent.Completed
            "failed" -> NexusTurnEvent.Failed(data.optString("errorMessage", "turn failed"))
            else -> null // audio / heartbeat / userPromptRequest — ignored for text UI
        }
    }

    // endregion

    companion object {
        private const val PING_OK = "nexus-ok"
        private const val ENDPOINT = "voiceSquad"
        private const val METHOD_STREAM_TURN = "streamTurn"
        private val JSON_MEDIA = "application/json".toMediaType()

        /**
         * Wrap the token as a Basic auth value (`Basic base64(token)`) — matches Serverpod's
         * `wrapAsBasicAuthHeaderValue`, which the server unwraps back to the raw token. Sending it
         * schemeless risks the server's header validator rejecting the request before the gate runs.
         */
        private fun basicAuth(token: String): String {
            val encoded = android.util.Base64.encodeToString(
                token.toByteArray(Charsets.UTF_8),
                android.util.Base64.NO_WRAP,
            )
            return "Basic $encoded"
        }

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
            .build()
    }
}
