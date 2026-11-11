package app.lawnchair.nexus.push

import org.json.JSONObject

/**
 * What the push socket says, in ntfy's own wire format (one JSON object a frame): the server's
 * `open` when the subscription stands, a `keepalive` every keepalive interval, and a `message` for
 * each notification. Anything else is [Other] and ignored.
 */
sealed interface PushEvent {
    data object Open : PushEvent
    data object Keepalive : PushEvent

    /** A notification: [id] is ntfy's, and what the next connection asks for everything since. */
    data class Message(val id: String, val title: String, val text: String) : PushEvent

    data object Other : PushEvent
}

fun parsePushEvent(frame: String): PushEvent {
    val json = runCatching { JSONObject(frame) }.getOrNull() ?: return PushEvent.Other
    return when (json.optString("event")) {
        "open" -> PushEvent.Open
        "keepalive" -> PushEvent.Keepalive
        "message" -> {
            val id = json.optString("id")
            if (id.isEmpty()) {
                PushEvent.Other
            } else {
                PushEvent.Message(id = id, title = json.optString("title"), text = json.optString("message"))
            }
        }
        else -> PushEvent.Other
    }
}

/**
 * Where to listen and on what beat: the server's answer to `push.subscription`. [keepaliveSeconds]
 * is the ntfy server's own keepalive interval; this end pings on the same one, so the two land
 * together and the radio is woken once.
 */
data class PushSubscription(val url: String, val keepaliveSeconds: Int)

/** The server's answer as it comes (`{"url":…,"keepaliveSeconds":…}` or `null`); null when there is none. */
fun parsePushSubscription(body: String): PushSubscription? {
    val json = runCatching { JSONObject(body.trim()) }.getOrNull() ?: return null
    val url = json.optString("url")
    if (url.isEmpty()) return null
    return PushSubscription(url, json.optInt("keepaliveSeconds", DEFAULT_KEEPALIVE_SECONDS).coerceIn(30, 3600))
}

/** Under the 100 seconds after which Cloudflare closes a quiet WebSocket. */
const val DEFAULT_KEEPALIVE_SECONDS = 90

/**
 * The WebSocket address of a topic: `http://host/topic` is `ws://host/topic/ws`, `https` is `wss`.
 * With [since] (the last message's id) the server first sends what came while nobody listened, so a
 * dropped connection loses nothing ntfy still holds. Null for an address that is not one topic's.
 */
fun pushSocketUrl(topicUrl: String, since: String?): String? {
    val trimmed = topicUrl.trim().trimEnd('/')
    val scheme = when {
        trimmed.startsWith("https://") -> "wss"
        trimmed.startsWith("http://") -> "ws"
        else -> return null
    }
    val rest = trimmed.substringAfter("://")
    val host = rest.substringBefore('/')
    val topic = rest.substringAfter('/', "")
    if (host.isEmpty() || topic.isEmpty() || topic.contains('/') || topic.contains('?')) return null
    val query = if (since.isNullOrEmpty()) "" else "?since=$since"
    return "$scheme://$host/$topic/ws$query"
}

/** How long to wait before connection attempt number [failures] + 1: quick at first, then every two minutes. */
fun pushRetrySeconds(failures: Int): Int = RETRY_SECONDS[(failures - 1).coerceIn(0, RETRY_SECONDS.lastIndex)]

private val RETRY_SECONDS = intArrayOf(5, 10, 15, 30, 60, 120)

/**
 * What the push connection has done since the service started: how often it was opened and lost,
 * what arrived, and how long it has stood. Every count here is a time the phone's radio was woken
 * for push, which is what a battery measurement is set against (`tool/push_test` in nexus). Read
 * with `adb shell dumpsys activity service NexusPushService`.
 */
class PushStats(private val now: () -> Long = System::currentTimeMillis) {
    private val startedAt = now()
    private var connectedAt = 0L
    private var connectedMs = 0L
    var opens = 0
        private set
    var failures = 0
        private set
    var keepalives = 0
        private set
    var messages = 0
        private set
    private var lastEventAt = 0L
    private var lastFailure = ""

    @Synchronized
    fun opened() {
        opens++
        connectedAt = now()
        lastEventAt = connectedAt
    }

    @Synchronized
    fun keepalive() {
        keepalives++
        lastEventAt = now()
    }

    @Synchronized
    fun message() {
        messages++
        lastEventAt = now()
    }

    /** The connection is gone, for [reason] (an exception's class, a close code: never content). */
    @Synchronized
    fun lost(reason: String) {
        if (connectedAt > 0) connectedMs += now() - connectedAt
        connectedAt = 0
        failures++
        lastFailure = reason
    }

    @Synchronized
    fun lines(): List<String> {
        val at = now()
        val connected = connectedMs + if (connectedAt > 0) at - connectedAt else 0
        return listOf(
            "push since=${(at - startedAt) / 1000}s connected=${connectedAt > 0} connectedFor=${connected / 1000}s",
            "push opens=$opens failures=$failures keepalives=$keepalives messages=$messages",
            "push lastEvent=${if (lastEventAt > 0) "${(at - lastEventAt) / 1000}s ago" else "never"} " +
                "lastFailure=${lastFailure.ifEmpty { "none" }}",
        )
    }
}
