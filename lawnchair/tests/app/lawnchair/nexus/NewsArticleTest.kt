package app.lawnchair.nexus

import app.lawnchair.nexus.news.ArticleBlock
import app.lawnchair.nexus.news.InlineRun
import app.lawnchair.nexus.news.articleBlocks
import app.lawnchair.nexus.news.inlineRuns
import app.lawnchair.nexus.news.isAttribution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The article the box wrote up, as the article view reads it: blocks, and the emphasis inside them. */
class NewsArticleTest {

    @Test
    fun `an article reads as its lead, subheadings, quote and list`() {
        val blocks = articleBlocks(
            """
            Norske diplomater forlater Vietnam. **Tolv** ansatte reiser hjem
            i kveld.

            ## Hva skjer nå

            Ambassaden holder stengt.

            > «Vi tar ingen sjanser»
            > — Utenriksministeren

            - Ambassaden er stengt
            - Konsulatet er åpent
            """.trimIndent(),
        )
        assertEquals(
            listOf(
                ArticleBlock.Paragraph("Norske diplomater forlater Vietnam. **Tolv** ansatte reiser hjem i kveld."),
                ArticleBlock.Heading("Hva skjer nå"),
                ArticleBlock.Paragraph("Ambassaden holder stengt."),
                ArticleBlock.Quote(listOf("«Vi tar ingen sjanser»", "— Utenriksministeren")),
                ArticleBlock.Items(listOf("Ambassaden er stengt", "Konsulatet er åpent"), numbered = false),
            ),
            blocks,
        )
    }

    @Test
    fun `a list straight under a paragraph is a list, and a numbered one is numbered`() {
        assertEquals(
            listOf(
                ArticleBlock.Paragraph("Tre ting endres:"),
                ArticleBlock.Items(listOf("Prisen", "Rutene", "Billettene"), numbered = true),
            ),
            articleBlocks("Tre ting endres:\n1. Prisen\n2) Rutene\n3. Billettene"),
        )
    }

    @Test
    fun `one dashed line alone is direct speech, not a list`() {
        assertEquals(
            listOf(ArticleBlock.Paragraph("– Det har vært helt enormt, sier Hauan.")),
            articleBlocks("- Det har vært helt enormt, sier Hauan."),
        )
    }

    @Test
    fun `a rule, blank lines and a bold paragraph are what they are`() {
        assertEquals(
            listOf(ArticleBlock.Paragraph("Første."), ArticleBlock.Paragraph("**Andre** avsnitt.")),
            articleBlocks("\n\nFørste.\n\n---\n\n\n**Andre** avsnitt.\n"),
        )
        assertEquals(emptyList<ArticleBlock>(), articleBlocks("  \n\n"))
    }

    @Test
    fun `emphasis is bold and italic runs, and a lone star is a star`() {
        assertEquals(
            listOf(
                InlineRun("Det var "),
                InlineRun("tolv", bold = true),
                InlineRun(" ansatte, "),
                InlineRun("ifølge NRK", italic = true),
                InlineRun("."),
            ),
            inlineRuns("Det var **tolv** ansatte, *ifølge NRK*."),
        )
        assertEquals(listOf(InlineRun("5 * 3 = 15")), inlineRuns("5 * 3 = 15"))
    }

    @Test
    fun `who said it is told from what was said`() {
        assertTrue(isAttribution("— Utenriksministeren"))
        assertTrue(isAttribution("– Kilde, rolle"))
        assertFalse(isAttribution("«Vi tar ingen sjanser»"))
    }
}
