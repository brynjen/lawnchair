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
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** What a card is: an article, or a research paper (its headings read differently). */
enum class NewsKind(val wire: String) {
    News("news"),
    Paper("paper"),
    ;

    companion object {
        /** A kind this build does not know is read as news. */
        fun fromWire(value: String?): NewsKind = entries.firstOrNull { it.wire == value } ?: News
    }
}

/** One thing a paper claims, and what it offers for it ([evidence] is empty when it only asserts). */
data class NewsClaim(val claim: String, val evidence: String)

/** One number an article states: [value] as it writes it ("14%", "NOK 2.1 bn") and what it measures. */
data class NewsFigure(val value: String, val label: String)

/**
 * One card of the server's news feed (`NewsCard`); [body] is null when only the synopsis was kept.
 * [imageUrl] is a path on the server (`/uploads/system/news/<hash>.jpg`), never an outside address;
 * [imageGenerated] says the box drew the picture instead of taking it from the article.
 */
data class NewsItem(
    val id: Long,
    val sourceName: String,
    val url: String,
    val title: String,
    val synopsis: String,
    val body: String?,
    val publishedAt: Instant?,
    val tags: List<String>,
    val kind: NewsKind = NewsKind.News,
    val keyPoints: List<String> = emptyList(),
    val figures: List<NewsFigure> = emptyList(),
    val imageUrl: String? = null,
    val imageGenerated: Boolean = false,
    /** The other sources that carried the same story ("also in VG, NRK"). */
    val alsoIn: List<String> = emptyList(),
    /** A paper's authors, arXiv classes and id; empty / null for an article. */
    val authors: List<String> = emptyList(),
    val categories: List<String> = emptyList(),
    val arxivId: String? = null,
    /** Hugging Face's upvotes, and the paper's code repository with its stars. */
    val upvotes: Int = 0,
    val codeUrl: String? = null,
    val codeStars: Int? = null,
    val claims: List<NewsClaim> = emptyList(),
    /** The language the article was written in (`no`, `en`, `de` …), when the server could tell. */
    val lang: String? = null,
    /** The title, summary and points are an English translation of an article in another language. */
    val translated: Boolean = false,
)

/**
 * The server's `NewsFeed`, the body of `POST /news` `feed` and `refresh`:
 * `{"cards":[{"id","sourceName","url","title","synopsis","body","publishedAt","tags","kind",
 * "keyPoints":[str],"figures":[{"value","label"}],"imageUrl","imageGenerated","alsoIn":[str],
 * "authors":[str],"categories":[str],"arxivId","upvotes","codeUrl","codeStars",
 * "claims":[{"claim","evidence"}]}],"refreshedAt","nextCursor"}`. [NewsFeed.nextCursor] is where the
 * next page starts, null on the last.
 * The same shape is what the on-device cache keeps ([toJson]), so one parser reads both.
 */
data class NewsFeed(val items: List<NewsItem>, val refreshedAt: Instant?, val nextCursor: String? = null) {
    companion object {
        val EMPTY = NewsFeed(emptyList(), null)
    }
}

/**
 * Reads a feed's JSON defensively: a card with no id or no title is skipped, a repeated id keeps its
 * first card, missing text is empty, tags are lower-cased and de-duplicated. The newer fields may all
 * be absent (an older server, an older cache): no key points, no figures, no picture, kind news. Null
 * when [text] is not an object with a `cards` array.
 */
fun parseNewsFeed(text: String): NewsFeed? {
    val feed = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return null
    val cards = feed["cards"] as? JsonArray ?: return null
    return NewsFeed(
        items = cards.mapNotNull { parseItem(it) }.distinctBy { it.id },
        refreshedAt = parseInstant(feed.text("refreshedAt")),
        nextCursor = feed.text("nextCursor")?.takeIf { it.isNotBlank() },
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
        kind = NewsKind.fromWire(o.text("kind")),
        keyPoints = (o["keyPoints"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { t -> t.isNotEmpty() } },
        figures = (o["figures"] as? JsonArray).orEmpty().mapNotNull { parseFigure(it) },
        imageUrl = o.text("imageUrl")?.trim()?.takeIf { it.isNotEmpty() },
        imageGenerated = (o["imageGenerated"] as? JsonPrimitive)?.booleanOrNull ?: false,
        alsoIn = o.strings("alsoIn"),
        authors = o.strings("authors"),
        categories = o.strings("categories"),
        arxivId = o.text("arxivId")?.trim()?.takeIf { it.isNotEmpty() },
        upvotes = (o["upvotes"] as? JsonPrimitive)?.longOrNull?.toInt() ?: 0,
        codeUrl = o.text("codeUrl")?.trim()?.takeIf { it.startsWith("http") },
        codeStars = (o["codeStars"] as? JsonPrimitive)?.longOrNull?.toInt(),
        claims = (o["claims"] as? JsonArray).orEmpty().mapNotNull { parseClaim(it) },
        lang = o.text("lang")?.trim()?.takeIf { it.isNotEmpty() },
        translated = (o["translated"] as? JsonPrimitive)?.booleanOrNull ?: false,
    )
}

private fun JsonObject.strings(key: String): List<String> = (this[key] as? JsonArray).orEmpty()
    .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { t -> t.isNotEmpty() } }

private fun parseClaim(element: JsonElement): NewsClaim? {
    val o = element as? JsonObject ?: return null
    val claim = o.text("claim")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return NewsClaim(claim, o.text("evidence")?.trim().orEmpty())
}

private fun parseFigure(element: JsonElement): NewsFigure? {
    val o = element as? JsonObject ?: return null
    val value = o.text("value")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return NewsFigure(value, o.text("label")?.trim().orEmpty())
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
                put("kind", item.kind.wire)
                putJsonArray("keyPoints") { item.keyPoints.forEach { add(JsonPrimitive(it)) } }
                putJsonArray("figures") {
                    item.figures.forEach { figure ->
                        addJsonObject {
                            put("value", figure.value)
                            put("label", figure.label)
                        }
                    }
                }
                put("imageUrl", item.imageUrl)
                put("imageGenerated", item.imageGenerated)
                putJsonArray("alsoIn") { item.alsoIn.forEach { add(JsonPrimitive(it)) } }
                putJsonArray("authors") { item.authors.forEach { add(JsonPrimitive(it)) } }
                putJsonArray("categories") { item.categories.forEach { add(JsonPrimitive(it)) } }
                put("arxivId", item.arxivId)
                put("upvotes", item.upvotes)
                put("codeUrl", item.codeUrl)
                put("codeStars", item.codeStars)
                put("lang", item.lang)
                put("translated", item.translated)
                putJsonArray("claims") {
                    item.claims.forEach { claim ->
                        addJsonObject {
                            put("claim", claim.claim)
                            put("evidence", claim.evidence)
                        }
                    }
                }
            }
        }
    }
    put("refreshedAt", refreshedAt?.toString())
    put("nextCursor", nextCursor)
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
