package app.lawnchair.nexus.news

import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** One card of the server's news feed (`NewsCard`); [body] is null when only the synopsis was kept. */
data class NewsItem(
    val id: Long,
    val sourceName: String,
    val url: String,
    val title: String,
    val synopsis: String,
    val body: String?,
    val publishedAt: Instant?,
    val tags: List<String>,
)

/**
 * The server's `NewsFeed`, the body of `POST /news` `feed` and `refresh`:
 * `{"cards":[{"id","sourceName","url","title","synopsis","body","publishedAt","tags"}],"refreshedAt"}`.
 * The same shape is what the on-device cache keeps ([toJson]), so one parser reads both.
 */
data class NewsFeed(val items: List<NewsItem>, val refreshedAt: Instant?) {
    companion object {
        val EMPTY = NewsFeed(emptyList(), null)
    }
}

/**
 * Reads a feed's JSON defensively: a card with no id or no title is skipped, a repeated id keeps its
 * first card, missing text is empty, tags are lower-cased and de-duplicated. Null when [text] is not
 * an object with a `cards` array.
 */
fun parseNewsFeed(text: String): NewsFeed? {
    val feed = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return null
    val cards = feed["cards"] as? JsonArray ?: return null
    return NewsFeed(
        items = cards.mapNotNull { parseItem(it) }.distinctBy { it.id },
        refreshedAt = parseInstant(feed.text("refreshedAt")),
    )
}

private fun parseItem(element: JsonElement): NewsItem? {
    val o = element as? JsonObject ?: return null
    val id = (o["id"] as? JsonPrimitive)?.longOrNull ?: return null
    val title = o.text("title")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return NewsItem(
        id = id,
        sourceName = o.text("sourceName").orEmpty(),
        url = o.text("url").orEmpty(),
        title = title,
        synopsis = o.text("synopsis").orEmpty(),
        body = o.text("body")?.takeIf { it.isNotBlank() },
        publishedAt = parseInstant(o.text("publishedAt")),
        tags = (o["tags"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase()?.takeIf { t -> t.isNotEmpty() } }
            .distinct(),
    )
}

/** The feed in the wire shape [parseNewsFeed] reads (instants as ISO-8601 UTC). */
fun NewsFeed.toJson(): String = buildJsonObject {
    putJsonArray("cards") {
        items.forEach { item ->
            addJsonObject {
                put("id", item.id)
                put("sourceName", item.sourceName)
                put("url", item.url)
                put("title", item.title)
                put("synopsis", item.synopsis)
                put("body", item.body)
                put("publishedAt", item.publishedAt?.toString())
                putJsonArray("tags") { item.tags.forEach { add(JsonPrimitive(it)) } }
            }
        }
    }
    put("refreshedAt", refreshedAt?.toString())
}.toString()

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

/** ISO-8601 as the server writes it (`...Z`); an offset or a zone-less time is read as UTC. Null if unreadable. */
internal fun parseInstant(text: String?): Instant? {
    if (text.isNullOrBlank()) return null
    return runCatching { Instant.parse(text) }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(text).toInstant() }.getOrNull()
        ?: runCatching { LocalDateTime.parse(text).toInstant(ZoneOffset.UTC) }.getOrNull()
}

/**
 * How long ago [publishedAt] was, in the card's style: "just now", "5m ago", "3h ago", "2d ago".
 * Empty without a date; a date in the future (clock skew) reads as "just now".
 */
fun agoText(publishedAt: Instant?, now: Instant): String {
    if (publishedAt == null) return ""
    val minutes = Duration.between(publishedAt, now).toMinutes()
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "${minutes}m ago"
        minutes < 24 * 60 -> "${minutes / 60}h ago"
        else -> "${minutes / (24 * 60)}d ago"
    }
}

/** The filter chips: every tag the cards carry, the most common first, ties alphabetical. */
fun newsTags(items: List<NewsItem>): List<String> =
    items.flatMap { it.tags }
        .groupingBy { it }
        .eachCount()
        .entries
        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        .map { it.key }
