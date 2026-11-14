package app.lawnchair.nexus.coder

import java.net.URLEncoder
import java.time.Instant
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

    /** What the expanded notification says. */
    fun details(now: Long): String = if (onMac) {
        "$question\n\nAnswer it on the Mac, where the conversation is."
    } else {
        listOfNotNull(
            question,
            options.firstOrNull()?.let { "Recommended: $it" },
            decidedAtMs?.let { decidedIn(it - now) },
        ).joinToString("\n\n")
    }

    /** Where a tap opens the app: the question page for a cloud question, the session for a Mac one. */
    val route: String get() {
        val session = URLEncoder.encode(sessionId, Charsets.UTF_8)
        return if (onMac) "/code/$session" else "/code/$session/question/${URLEncoder.encode(questionId, Charsets.UTF_8)}"
    }
}

/** How long until the recommended answer is taken, in the words the app's card uses. */
fun decidedIn(leftMs: Long): String {
    val minutes = (leftMs / 60_000).coerceAtLeast(0)
    return when {
        minutes < 1 -> "The recommended answer is taken in under a minute."
        minutes < 60 -> "The recommended answer is taken in $minutes min."
        else -> "The recommended answer is taken in ${minutes / 60} h ${minutes % 60} min."
    }
}

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
