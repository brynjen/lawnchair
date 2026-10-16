package app.lawnchair.nexus

import app.lawnchair.nexus.news.NewsItem
import app.lawnchair.nexus.news.NewsKind
import app.lawnchair.nexus.news.quickQuestions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The questions under an article's Discuss button, each a tap from being asked. */
class NewsQuestionsTest {

    private fun item(kind: NewsKind) = NewsItem(
        id = 1,
        sourceName = "Source",
        url = "https://example.test/1",
        title = "Title",
        synopsis = "Summary.",
        body = null,
        publishedAt = null,
        tags = emptyList(),
        kind = kind,
    )

    @Test
    fun `a paper is asked about its claim, its code and how to test it`() {
        val labels = quickQuestions(item(NewsKind.Paper)).map { it.label }
        assertEquals(listOf("Main claim", "Is there code?", "How to test it"), labels)
    }

    @Test
    fun `an article is asked for the short version, who it touches and the background`() {
        val labels = quickQuestions(item(NewsKind.News)).map { it.label }
        assertEquals(listOf("In three lines", "Who is affected?", "Background"), labels)
    }

    @Test
    fun `every chip asks a whole question, and no two ask the same`() {
        for (kind in NewsKind.values()) {
            val questions = quickQuestions(item(kind)).map { it.question }
            assertTrue(questions.all { it.trim().length > 12 })
            assertEquals(questions.size, questions.toSet().size)
        }
    }
}
