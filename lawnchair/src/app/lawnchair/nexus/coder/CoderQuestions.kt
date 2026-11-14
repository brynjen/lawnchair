package app.lawnchair.nexus.coder

import java.net.URLEncoder
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import org.json.JSONArray
import org.json.JSONObject

/**
 * A question one of the person's coding agents waits on, as nexus lists it
 * (`/coder/host/waitingQuestions`, nexus-coder's `WaitingQuestion`). A cloud session's is answered
 * here, on the app's question page; a Mac session's only says the answer is needed on the Mac,
 * where its conversation is.
 */
data class WaitingQuestion(
    val sessionId: String,
    val questionId: String,
    val sessionTitle: String,
    val question: String,
    val options: List<String>,
    val onMac: Boolean,
    /** When the agent's recommended answer is taken; null when it waits for the person. */
    val decidedAtMs: Long?,
    val askedAtMs: Long,
) {
    /** One notification for each question of each session. */
    val key: String get() = "$sessionId/$questionId"

    val title: String get() = sessionTitle.ifBlank { "Your agent" }

    /** The line under the title. */
    val text: String get() = if (onMac) "Needs your answer on the Mac" else question

    /** What the expanded notification says; the time is a clock time, which does not go stale. */
    fun details(zone: ZoneId = ZoneId.systemDefault()): String = if (onMac) {
        "$question\n\nAnswer it on the Mac, where the conversation is."
    } else {
        listOfNotNull(
            question,
            options.firstOrNull()?.let { "Recommended: $it" },
            decidedAtMs?.let { decidedAt(it, zone) },
        ).joinToString("\n\n")
    }

    /**
     * Where a tap opens the app, on its own (`alone=1`, so back leaves the app again): the question
     * page for a cloud question, the session for a Mac one.
     */
    val route: String get() {
        val session = URLEncoder.encode(sessionId, Charsets.UTF_8)
        val page = if (onMac) "/code/$session" else "/code/$session/question/${URLEncoder.encode(questionId, Charsets.UTF_8)}"
        return "$page?alone=1"
    }
}

/** When the recommended answer is taken, as the app's page says it: a clock time in [zone]. */
fun decidedAt(atMs: Long, zone: ZoneId): String =
    "The recommended answer is taken at ${
        LocalTime.ofInstant(Instant.ofEpochMilli(atMs), zone).format(DateTimeFormatter.ofPattern("HH:mm"))
    }."

/** The host's list; an entry it cannot read is left out. */
fun parseWaitingQuestions(value: Any?): List<WaitingQuestion> {
    val list = value as? JSONArray ?: return emptyList()
    return (0 until list.length()).mapNotNull { index ->
        val json = list.optJSONObject(index) ?: return@mapNotNull null
        runCatching { waitingQuestionOf(json) }.getOrNull()
    }
}

private fun waitingQuestionOf(json: JSONObject): WaitingQuestion {
    val askedAt = Instant.parse(json.getString("askedAt")).toEpochMilli()
    val timeoutSeconds = json.optLong("timeoutSeconds", 0)
    val options = json.optJSONArray("options")
    return WaitingQuestion(
        sessionId = json.getString("sessionId"),
        questionId = json.getString("questionId"),
        sessionTitle = json.optString("sessionTitle"),
        question = json.getString("question"),
        options = List(options?.length() ?: 0) { options!!.optString(it) },
        onMac = json.optString("location") == "mac",
        decidedAtMs = if (timeoutSeconds > 0) askedAt + timeoutSeconds * 1000 else null,
        askedAtMs = askedAt,
    )
}
