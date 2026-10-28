package app.lawnchair.nexus

import app.lawnchair.nexus.news.NewsFeed
import app.lawnchair.nexus.news.NewsFigure
import app.lawnchair.nexus.news.NewsItem
import app.lawnchair.nexus.news.NewsKind
import app.lawnchair.nexus.news.agoText
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

    // region the summary fields

    private val rich = """
        {"cards":[{"id":5,"sourceName":"arXiv cs.LG","url":"https://arxiv.org/abs/1","title":"Sparse experts","synopsis":"S",
          "body":"B","publishedAt":null,"tags":["ai"],"kind":"paper",
          "keyPoints":["Routes each token to two experts"," ","Halves latency"],
          "figures":[{"value":"41 → 14 ms","label":"latency per token"},{"value":"","label":"no number"},{"value":"3x"},
                     {"label":"no value"},"junk"],
          "imageUrl":"/uploads/system/news/rig-pic1.jpg","imageGenerated":true}],"refreshedAt":null}
    """.trimIndent()

    @Test
    fun `kind, key points, figures and the picture read field for field`() {
        val item = parseNewsFeed(rich)!!.items.single()
        assertEquals(NewsKind.Paper, item.kind)
        assertEquals(listOf("Routes each token to two experts", "Halves latency"), item.keyPoints)
        assertEquals(listOf(NewsFigure("41 → 14 ms", "latency per token"), NewsFigure("3x", "")), item.figures)
        assertEquals("/uploads/system/news/rig-pic1.jpg", item.imageUrl)
        assertEquals(true, item.imageGenerated)
    }

    @Test
    fun `an older server or cache with none of them reads as a plain news card`() {
        val item = parseNewsFeed("""{"cards":[{"id":1,"title":"T"}]}""")!!.items.single()
        assertEquals(NewsKind.News, item.kind)
        assertEquals(emptyList<String>(), item.keyPoints)
        assertEquals(emptyList<NewsFigure>(), item.figures)
        assertNull(item.imageUrl)
        assertEquals(false, item.imageGenerated)
    }

    @Test
    fun `explicit nulls and blanks read as absent`() {
        val item = parseNewsFeed(
            """{"cards":[{"id":1,"title":"T","kind":null,"keyPoints":null,"figures":null,"imageUrl":"  ","imageGenerated":null}]}""",
        )!!.items.single()
        assertEquals(NewsKind.News, item.kind)
        assertEquals(emptyList<String>(), item.keyPoints)
        assertEquals(emptyList<NewsFigure>(), item.figures)
        assertNull(item.imageUrl)
        assertEquals(false, item.imageGenerated)
        assertNull(parseNewsFeed("""{"cards":[{"id":1,"title":"T","imageUrl":null}]}""")!!.items.single().imageUrl)
    }

    @Test
    fun `a kind this build does not know is news, and a wrong-typed flag is false`() {
        val item = parseNewsFeed("""{"cards":[{"id":1,"title":"T","kind":"podcast","imageGenerated":"maybe","keyPoints":"x","figures":{}}]}""")!!
            .items.single()
        assertEquals(NewsKind.News, item.kind)
        assertEquals(false, item.imageGenerated)
        assertEquals(emptyList<String>(), item.keyPoints)
        assertEquals(emptyList<NewsFigure>(), item.figures)
    }

    @Test
    fun `the new fields survive the cache`() {
        val feed = parseNewsFeed(rich)!!
        assertEquals(feed, parseNewsFeed(feed.toJson()))
        // A picture-less card does too, and an old cache written before the fields is still readable.
        val plain = parseNewsFeed(full)!!
        assertEquals(plain, parseNewsFeed(plain.toJson()))
    }

    // endregion

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

    @Test
    fun `an article the server translated says so, and the language it was in`() {
        val feed = parseNewsFeed(
            """{"cards":[{"id":1,"title":"Budget presented","lang":"de","translated":true},
                          {"id":2,"title":"Budsjettet","lang":"no","translated":false},
                          {"id":3,"title":"Plain"}]}""",
        )!!
        assertEquals(true, feed.items[0].translated)
        assertEquals("de", feed.items[0].lang)
        assertEquals(false, feed.items[1].translated)
        assertEquals("no", feed.items[1].lang)
        assertEquals(false, feed.items[2].translated)
        assertNull(feed.items[2].lang)
    }

    @Test
    fun `the saved copy keeps the translated tag`() {
        val feed = NewsFeed(
            items = listOf(
                NewsItem(
                    id = 1, sourceName = "Spiegel", url = "https://example.test/1", title = "Budget presented",
                    synopsis = "S.", body = null, publishedAt = null, tags = emptyList(),
                    lang = "de", translated = true,
                ),
            ),
            refreshedAt = null,
        )
        assertEquals(feed, parseNewsFeed(feed.toJson()))
        assertEquals(true, parseNewsFeed(feed.toJson())!!.items.single().translated)
    }

    @Test
    fun `the star and the read mark come from the wire, survive the saved copy, and are off for a card that does not say`() {
        val feed = parseNewsFeed(
            """{"cards":[{"id":1,"title":"Starred","favorite":true},{"id":2,"title":"Read","read":true},{"id":3,"title":"Older server"}]}""",
        )!!
        assertEquals(listOf(true, false, false), feed.items.map { it.favorite })
        assertEquals(listOf(false, true, false), feed.items.map { it.read })
        assertEquals(feed, parseNewsFeed(feed.toJson()))
        assertEquals(listOf(false, true, false), parseNewsFeed(feed.toJson())!!.items.map { it.read })
    }
}
