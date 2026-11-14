package app.lawnchair.nexus.push

import org.json.JSONObject

/**
 * What a push says once its transport has handed it over: Nexus's own, whatever carried it
 * (docs/features/push.md in nexus, "The contract"). The ntfy socket ([NexusPushService], `Ntfy.kt`)
 * hands over a message's title and text; another transport (an FCM data message) would hand over
 * the same body to [decodeNexusPush].
 */
sealed interface NexusPush {
    /** A notification to show: today only the admin page's test tab sends one. */
    data class Notification(val title: String, val text: String) : NexusPush

    /**
     * The server's word that the banner changed: never shown. [jobs] are the jobs that left the card,
     * whose end may be a notification for this person.
     */
    data class Nudge(val jobs: List<Int>) : NexusPush

    /** A word of Nexus's own that this build does not know: not a notification either. */
    data object Other : NexusPush
}

fun decodeNexusPush(title: String, body: String): NexusPush {
    val word = if (body.startsWith("{")) runCatching { JSONObject(body) }.getOrNull() else null
    return when {
        word?.optString("nexus") == "banner" -> {
            val jobs = word.optJSONArray("jobs")
            NexusPush.Nudge(List(jobs?.length() ?: 0) { jobs!!.optInt(it) })
        }
        word?.has("nexus") == true -> NexusPush.Other
        else -> NexusPush.Notification(title, body)
    }
}

/**
 * Where to listen and on what beat: the server's answer to `push.subscription`. [transport] says how
 * [url] is read ([NTFY_TRANSPORT]: a topic's whole address, held as a WebSocket); a transport this
 * build does not know is not listened to. [keepaliveSeconds] is the ntfy server's own keepalive
 * interval; this end pings on the same one, so the two land together and the radio is woken once.
 */
data class PushSubscription(val transport: String, val url: String, val keepaliveSeconds: Int)

/**
 * The server's answer as it comes (`{"transport":…,"url":…,"keepaliveSeconds":…}` or `null`); null
 * when there is none. A server from before 2026-11-14 names no transport, and had only ntfy.
 */
fun parsePushSubscription(body: String): PushSubscription? {
    val json = runCatching { JSONObject(body.trim()) }.getOrNull() ?: return null
    val url = json.optString("url")
    if (url.isEmpty()) return null
    return PushSubscription(
        transport = json.optString("transport").ifEmpty { NTFY_TRANSPORT },
        url = url,
        keepaliveSeconds = json.optInt("keepaliveSeconds", DEFAULT_KEEPALIVE_SECONDS).coerceIn(30, 3600),
    )
}

/** Under the 100 seconds after which Cloudflare closes a quiet WebSocket. */
const val DEFAULT_KEEPALIVE_SECONDS = 90

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
    companion object {
        /**
         * The launcher process's own, which the push service counts into however often it is
         * started again: a power recording reads it at both ends and takes the difference.
         */
        val process = PushStats()
    }

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

    /** How long the connection has stood in all, in seconds. */
    @Synchronized
    fun connectedSeconds(): Long = (connectedMs + if (connectedAt > 0) now() - connectedAt else 0) / 1000

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
