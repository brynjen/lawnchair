package app.lawnchair.nexus.coder

import java.net.URLEncoder
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

/**
 * What one of the person's coding agents answered when a run ended, kept for the phone by nexus
 * (`/coder/host/replies`): a run the person started from the phone, or from NexusCoder while they
 * are away from the Mac. One notification for each session, which opens the session.
 */
data class CoderReply(
    val sessionId: String,
    val sessionTitle: String,
    val text: String,
    val failed: Boolean,
    val atMs: Long,
) {
    /** The same session's next reply is another key, so it is shown again. */
    val key: String get() = "$sessionId/$atMs"

    val title: String get() = sessionTitle.ifBlank { "Your agent" }

    /** The line under the title. */
    val line: String get() = when {
        failed -> "Stopped with an error"
        text.isBlank() -> "Finished"
        else -> text.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    }

    /** What the expanded notification says. */
    val details: String get() = when {
        text.isBlank() -> line
        failed -> "Stopped with an error.\n\n$text"
        else -> text
    }

    /** Where a tap opens the app, on its own (`alone=1`, so back leaves the app again). */
    val route: String get() = "/code/${URLEncoder.encode(sessionId, Charsets.UTF_8)}?alone=1"
}

/** The host's list; an entry it cannot read is left out. */
fun parseCoderReplies(value: Any?): List<CoderReply> {
    val list = value as? JSONArray ?: return emptyList()
    return (0 until list.length()).mapNotNull { index ->
        val json = list.optJSONObject(index) ?: return@mapNotNull null
        runCatching { coderReplyOf(json) }.getOrNull()
    }
}

private fun coderReplyOf(json: JSONObject): CoderReply = CoderReply(
    sessionId = json.getString("sessionId"),
    sessionTitle = json.optString("sessionTitle"),
    text = json.optString("text"),
    failed = json.optBoolean("failed"),
    atMs = Instant.parse(json.getString("at")).toEpochMilli(),
)
