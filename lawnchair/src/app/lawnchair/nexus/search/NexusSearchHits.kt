package app.lawnchair.nexus.search

import app.lawnchair.nexus.news.NewsItem
import app.lawnchair.nexus.news.parseInstant
import app.lawnchair.nexus.news.parseNewsItem
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** A note of the person's vault (`QuickNoteHit`): [path] is what opens it, [title] its file name. */
data class NexusNoteHit(
    val path: String,
    val title: String,
    val snippet: String?,
    val updatedAt: Instant?,
)

/** A chat of the person's (`QuickChatHit`); [agentId] is set for a direct session with an agent. */
data class NexusChatHit(
    val conversationId: Int,
    val title: String,
    val snippet: String?,
    val agentId: String?,
    val lastActivity: Instant?,
)

/** What the server's quick search found (`QuickSearchResult`): a few of each, best first. */
data class NexusSearchHits(
    val notes: List<NexusNoteHit>,
    val chats: List<NexusChatHit>,
    val news: List<NewsItem>,
) {
    val isEmpty: Boolean get() = notes.isEmpty() && chats.isEmpty() && news.isEmpty()

    companion object {
        val EMPTY = NexusSearchHits(emptyList(), emptyList(), emptyList())
    }
}

/**
 * Reads the body of `POST /quickSearch {"method":"search"}`:
 * `{"notes":[{"path","title","snippet","updatedAt"}],"chats":[{"conversationId","title","snippet",
 * "agentId","lastActivity"}],"news":[<NewsCard>]}`. A hit without what opens it (a path, a
 * conversation id) or without a title is skipped, and a missing list is empty. Null when [text] is
 * not a JSON object.
 */
fun parseQuickSearch(text: String): NexusSearchHits? {
    val o = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return null
    fun list(key: String) = (o[key] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
    return NexusSearchHits(
        notes = list("notes").mapNotNull { n ->
            val path = n.text("path")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            NexusNoteHit(
                path = path,
                title = n.text("title")?.takeIf { it.isNotBlank() } ?: path,
                snippet = n.text("snippet")?.takeIf { it.isNotBlank() },
                updatedAt = parseInstant(n.text("updatedAt")),
            )
        },
        chats = list("chats").mapNotNull { c ->
            val id = (c["conversationId"] as? JsonPrimitive)?.longOrNull?.toInt() ?: return@mapNotNull null
            NexusChatHit(
                conversationId = id,
                title = c.text("title")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null,
                snippet = c.text("snippet")?.takeIf { it.isNotBlank() },
                agentId = c.text("agentId")?.takeIf { it.isNotBlank() },
                lastActivity = parseInstant(c.text("lastActivity")),
            )
        },
        news = (o["news"] as? JsonArray).orEmpty().mapNotNull { parseNewsItem(it) },
    )
}

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
