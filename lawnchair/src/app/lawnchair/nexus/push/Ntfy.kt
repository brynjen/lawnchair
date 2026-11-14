package app.lawnchair.nexus.push

import org.json.JSONObject

/*
 * The ntfy transport: everything here is ntfy's own, and nothing outside [NexusPushService] reads it
 * (docs/features/push.md in nexus, "The contract"). Swapping ntfy for another transport replaces
 * this file and the service's socket; what a push says ([NexusPush]) stays.
 */

/** What `push.subscription` names ntfy. */
const val NTFY_TRANSPORT = "ntfy"

/**
 * What the topic's WebSocket says, one JSON object a frame: the server's `open` when the
 * subscription stands, a `keepalive` every keepalive interval, and a `message`, whose title and
 * text are a push for [decodeNexusPush]. Anything else is [Other] and ignored.
 */
sealed interface NtfyFrame {
    data object Open : NtfyFrame
    data object Keepalive : NtfyFrame

    /** [id] is ntfy's: what the next connection asks for everything since, and the notification's key. */
    data class Message(val id: String, val title: String, val body: String) : NtfyFrame

    data object Other : NtfyFrame
}

fun parseNtfyFrame(frame: String): NtfyFrame {
    val json = runCatching { JSONObject(frame) }.getOrNull() ?: return NtfyFrame.Other
    return when (json.optString("event")) {
        "open" -> NtfyFrame.Open
        "keepalive" -> NtfyFrame.Keepalive
        "message" -> {
            val id = json.optString("id")
            if (id.isEmpty()) NtfyFrame.Other else NtfyFrame.Message(id, json.optString("title"), json.optString("message"))
        }
        else -> NtfyFrame.Other
    }
}

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
