package app.lawnchair.nexus

import app.lawnchair.nexus.news.NewsClaim
import app.lawnchair.nexus.news.NewsFeed
import app.lawnchair.nexus.news.NewsItem
import app.lawnchair.nexus.news.NewsKind
import app.lawnchair.nexus.news.NewsSection
import app.lawnchair.nexus.news.authorLine
import app.lawnchair.nexus.news.compactCount
import app.lawnchair.nexus.news.matches
import app.lawnchair.nexus.news.parseNewsFeed
import app.lawnchair.nexus.news.plus
import app.lawnchair.nexus.news.toJson
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The page's sections, its paper cards' fields on the wire, and the helpers the list is built from. */
class NewsSectionTest {

    private fun item(
        id: Long,
        title: String = "Title $id",
        synopsis: String = "",
        body: String? = null,
        source: String = "Source",
        keyPoints: List<String> = emptyList(),
        authors: List<String> = emptyList(),
    ) = NewsItem(id, source, "https://example.com/$id", title, synopsis, body, null, emptyList(), keyPoints = keyPoints, authors = authors)

    @Test
    fun `the sections are named for the server`() {
        assertEquals(listOf("now", "ai", "papers"), NewsSection.entries.map { it.wire })
        assertEquals(listOf("Now", "AI", "Papers"), NewsSection.entries.map { it.label })
    }

    @Test
    fun `a search reads the title, summary, key points, text, source and authors`() {
        assertTrue(item(1).matches(""))
        assertTrue(item(1).matches("  "))
        assertTrue(item(1, title = "Statsbudsjettet").matches("budsjett"))
        assertTrue(item(1, synopsis = "mer til forsvar").matches("FORSVAR"))
        assertTrue(item(1, keyPoints = listOf("Økte bevilgninger")).matches("bevilgninger"))
        assertTrue(item(1, body = "Teksten nevner regjeringen").matches("regjeringen"))
        assertTrue(item(1, source = "Anthropic").matches("anthropic"))
        assertTrue(item(1, authors = listOf("Ada Lovelace")).matches("lovelace"))
        assertFalse(item(1).matches("ingenting"))
    }

    @Test
    fun `more cards go after these, none twice, and the cursor moves on`() {
        val first = NewsFeed(listOf(item(1), item(2)), Instant.parse("2026-10-14T07:00:00Z"), nextCursor = "a")
        val more = NewsFeed(listOf(item(2), item(3)), null, nextCursor = null)
        val both = first.plus(more)
        assertEquals(listOf(1L, 2L, 3L), both.items.map { it.id })
        assertNull(both.nextCursor)
        assertEquals(first.refreshedAt, both.refreshedAt)
    }

    @Test
    fun `authors are three and et al`() {
        assertEquals("", item(1).authorLine())
        assertEquals("A, B", item(1, authors = listOf("A", "B")).authorLine())
        assertEquals("A, B, C", item(1, authors = listOf("A", "B", "C")).authorLine())
        assertEquals("A, B, C et al.", item(1, authors = listOf("A", "B", "C", "D")).authorLine())
    }

    @Test
    fun `counts are compact`() {
        assertEquals("950", compactCount(950))
        assertEquals("1.2k", compactCount(1200))
        assertEquals("12k", compactCount(12_345))
    }

    @Test
    fun `a paper's fields read from the wire and survive the saved copy`() {
        val json = """
            {"cards":[{"id":9,"sourceName":"arXiv","url":"https://arxiv.org/abs/2610.1","title":"Scaling agents","synopsis":"They scale.",
              "kind":"paper","tags":["ai"],"alsoIn":["Hugging Face"],"authors":["Ada Lovelace","Alan Turing"],"categories":["cs.AI","cs.CL"],
              "arxivId":"2610.1","upvotes":51,"codeUrl":"https://github.com/a/b","codeStars":1200,
              "claims":[{"claim":"X is 14% better.","evidence":"Table 2."},{"claim":"Y is faster"},{"nothing":1}]}],
             "refreshedAt":null,"nextCursor":"2026-10-14T06:00:00.000Z|8"}
        """.trimIndent()
        val feed = parseNewsFeed(json)!!
        val paper = feed.items.single()
        assertEquals(NewsKind.Paper, paper.kind)
        assertEquals(listOf("Hugging Face"), paper.alsoIn)
        assertEquals(listOf("Ada Lovelace", "Alan Turing"), paper.authors)
        assertEquals(listOf("cs.AI", "cs.CL"), paper.categories)
        assertEquals("2610.1", paper.arxivId)
        assertEquals(51, paper.upvotes)
        assertEquals("https://github.com/a/b", paper.codeUrl)
        assertEquals(1200, paper.codeStars)
        assertEquals(listOf(NewsClaim("X is 14% better.", "Table 2."), NewsClaim("Y is faster", "")), paper.claims)
        assertEquals("2026-10-14T06:00:00.000Z|8", feed.nextCursor)

        assertEquals(feed, parseNewsFeed(feed.toJson()))
    }

    @Test
    fun `an older server's card has none of the new fields, and a code link that is not http is none`() {
        val old = parseNewsFeed("""{"cards":[{"id":1,"title":"T","codeUrl":"javascript:alert(1)"}]}""")!!.items.single()
        assertEquals(emptyList<String>(), old.authors)
        assertEquals(emptyList<String>(), old.alsoIn)
        assertEquals(0, old.upvotes)
        assertNull(old.codeUrl)
        assertNull(old.arxivId)
        assertEquals(emptyList<NewsClaim>(), old.claims)
        assertNull(parseNewsFeed("""{"cards":[]}""")!!.nextCursor)
    }
}
