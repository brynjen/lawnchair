package app.lawnchair.nexus

import app.lawnchair.nexus.news.NewsFeed
import app.lawnchair.nexus.news.NewsItem
import app.lawnchair.nexus.news.agoText
import app.lawnchair.nexus.news.newsTags
import app.lawnchair.nexus.news.parseNewsFeed
import app.lawnchair.nexus.news.toJson
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The news feed as the launcher reads it (the server's `NewsFeed`, `POST /news` `feed` and
 * `refresh`): the parser, the "5m ago" lines and the tag chips.
 */
class NewsFeedTest {

    private val full = """
        {"cards":[
          {"id":7,"sourceName":"Hacker News","url":"https://news.ycombinator.com/item?id=1","title":"Open weights close the gap",
           "synopsis":"A new wave of models.","body":"The long text.","publishedAt":"2026-10-14T06:30:00.000Z","tags":["ai","tech"]},
          {"id":8,"sourceName":"arXiv","url":"https://arxiv.org/abs/1","title":"Speaker verification","synopsis":"On device.",
           "body":null,"publishedAt":null,"tags":[]}
        ],"refreshedAt":"2026-10-14T07:00:00.000000Z"}
    """.trimIndent()

    @Test
    fun `a feed reads field for field`() {
        val feed = parseNewsFeed(full)!!
        assertEquals(Instant.parse("2026-10-14T07:00:00Z"), feed.refreshedAt)
        assertEquals(2, feed.items.size)
        assertEquals(
            NewsItem(
                id = 7,
                sourceName = "Hacker News",
                url = "https://news.ycombinator.com/item?id=1",
                title = "Open weights close the gap",
                synopsis = "A new wave of models.",
                body = "The long text.",
                publishedAt = Instant.parse("2026-10-14T06:30:00Z"),
                tags = listOf("ai", "tech"),
            ),
            feed.items[0],
        )
        val second = feed.items[1]
        assertNull(second.body)
        assertNull(second.publishedAt)
        assertEquals(emptyList<String>(), second.tags)
    }

    @Test
    fun `no refreshedAt and an empty list are a feed`() {
        val feed = parseNewsFeed("""{"cards":[],"refreshedAt":null}""")!!
        assertEquals(emptyList<NewsItem>(), feed.items)
        assertNull(feed.refreshedAt)
        assertEquals(NewsFeed.EMPTY, parseNewsFeed("""{"cards":[]}"""))
    }

    @Test
    fun `a card that cannot be shown is skipped, the rest are kept`() {
        val feed = parseNewsFeed(
            """
            {"cards":[
              {"id":1,"title":"Fine"},
              {"title":"No id"},
              {"id":2},
              {"id":3,"title":"   "},
              "junk",
              {"id":4,"title":"Also fine","tags":["AI "," ","ai","Tech"]}
            ]}
            """.trimIndent(),
        )!!
        assertEquals(listOf(1L, 4L), feed.items.map { it.id })
        // Missing text is empty, not null; tags are trimmed, lower-cased and de-duplicated.
        assertEquals("", feed.items[0].synopsis)
        assertEquals("", feed.items[0].url)
        assertEquals(listOf("ai", "tech"), feed.items[1].tags)
    }

    @Test
    fun `a repeated id keeps the first card`() {
        val feed = parseNewsFeed("""{"cards":[{"id":1,"title":"First"},{"id":1,"title":"Second"}]}""")!!
        assertEquals(listOf("First"), feed.items.map { it.title })
    }

    @Test
    fun `a blank body is no body, and an unreadable date is no date`() {
        val item = parseNewsFeed("""{"cards":[{"id":1,"title":"T","body":"  ","publishedAt":"yesterday"}]}""")!!.items.single()
        assertNull(item.body)
        assertNull(item.publishedAt)
    }

    @Test
    fun `dates are read with an offset or as UTC when they carry none`() {
        fun at(text: String) = parseNewsFeed("""{"cards":[{"id":1,"title":"T","publishedAt":"$text"}]}""")!!.items.single().publishedAt
        val expected = Instant.parse("2026-10-14T06:30:00Z")
        assertEquals(expected, at("2026-10-14T06:30:00Z"))
        assertEquals(expected, at("2026-10-14T06:30:00.000000Z"))
        assertEquals(expected, at("2026-10-14T08:30:00+02:00"))
        assertEquals(expected, at("2026-10-14T06:30:00"))
    }

    @Test
    fun `text that is not a feed is null`() {
        assertNull(parseNewsFeed(""))
        assertNull(parseNewsFeed("not json"))
        assertNull(parseNewsFeed("[]"))
        assertNull(parseNewsFeed("""{"refreshedAt":null}"""))
        assertNull(parseNewsFeed("""{"cards":{}}"""))
    }

    @Test
    fun `a feed written out reads back the same`() {
        val feed = parseNewsFeed(full)!!
        assertEquals(feed, parseNewsFeed(feed.toJson()))
        assertEquals(NewsFeed.EMPTY, parseNewsFeed(NewsFeed.EMPTY.toJson()))
    }

    // region ago

    private val now = Instant.parse("2026-10-14T12:00:00Z")

    private fun ago(secondsAgo: Long) = agoText(now.minusSeconds(secondsAgo), now)

    @Test
    fun `ago reads in minutes, hours and days`() {
        assertEquals("just now", ago(0))
        assertEquals("just now", ago(59))
        assertEquals("1m ago", ago(60))
        assertEquals("5m ago", ago(5 * 60 + 30))
        assertEquals("59m ago", ago(59 * 60 + 59))
        assertEquals("1h ago", ago(60 * 60))
        assertEquals("3h ago", ago(3 * 3600 + 1_000))
        assertEquals("23h ago", ago(24 * 3600 - 1))
        assertEquals("1d ago", ago(24 * 3600))
        assertEquals("2d ago", ago(2 * 24 * 3600 + 3600))
        assertEquals("30d ago", ago(30L * 24 * 3600))
    }

    @Test
    fun `ago is empty without a date and just now for a future one`() {
        assertEquals("", agoText(null, now))
        assertEquals("just now", agoText(now.plusSeconds(3_600), now))
    }

    // endregion

    // region tags

    private fun card(id: Long, vararg tags: String) =
        NewsItem(id, "", "", "T$id", "", null, null, tags.toList())

    @Test
    fun `the chips are the union of the tags, most common first, ties alphabetical`() {
        val tags = newsTags(
            listOf(
                card(1, "tech", "ai"),
                card(2, "ai", "research", "science"),
                card(3, "tech", "ai", "business"),
                card(4, "science"),
            ),
        )
        // ai x3, then science x2 and tech x2 (alphabetical), then business and research x1.
        assertEquals(listOf("ai", "science", "tech", "business", "research"), tags)
    }

    @Test
    fun `no cards, no chips`() {
        assertEquals(emptyList<String>(), newsTags(emptyList()))
        assertEquals(emptyList<String>(), newsTags(listOf(card(1))))
    }

    // endregion
}
