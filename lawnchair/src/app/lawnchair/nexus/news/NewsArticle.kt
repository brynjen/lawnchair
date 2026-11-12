package app.lawnchair.nexus.news

/**
 * One block of an article the box wrote up ([NewsItem.articleMarkdown]), in the few Markdown forms its prompt
 * allows: paragraphs, `## ` subheadings, `> ` quotes (the quotation, then `> — Name`), and `- ` or `1. ` lists.
 * The words inside a block may carry `**bold**` and `*italic*` ([inlineRuns]).
 */
sealed interface ArticleBlock {
    data class Paragraph(val text: String) : ArticleBlock
    data class Heading(val text: String) : ArticleBlock

    /** A quotation's lines; the last is often who said it (`— Name, role`), see [isAttribution]. */
    data class Quote(val lines: List<String>) : ArticleBlock
    data class Items(val items: List<String>, val numbered: Boolean) : ArticleBlock
}

/**
 * The blocks of [markdown], in order. A blank line ends a block, and so does a line of another form (a list
 * straight under a paragraph is a list). Anything that is none of the forms is a paragraph, so a stray `#`, a
 * table or a rule never breaks the page; the lines of one paragraph are joined with a space. A list of one
 * dashed line is a paragraph of direct speech.
 */
fun articleBlocks(markdown: String): List<ArticleBlock> {
    val blocks = mutableListOf<ArticleBlock>()
    val lines = mutableListOf<String>()
    var kind: Char? = null
    var numbered = false

    fun flush() {
        when (kind) {
            'p' -> lines.joinToString(" ").trim().takeIf { it.isNotEmpty() }?.let { blocks += ArticleBlock.Paragraph(it) }
            'q' -> lines.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }?.let { blocks += ArticleBlock.Quote(it) }
            'l' -> lines.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }?.let {
                // One dashed line alone is a line of direct speech (Norwegian sets it "– Det var enormt, sier hun."),
                // not a list of one.
                blocks += if (it.size == 1 && !numbered) ArticleBlock.Paragraph("– ${it[0]}") else ArticleBlock.Items(it, numbered)
            }
        }
        lines.clear()
        kind = null
    }

    fun add(next: Char, text: String, isNumbered: Boolean = false) {
        if (kind != next || (next == 'l' && numbered != isNumbered)) {
            flush()
            kind = next
            numbered = isNumbered
        }
        lines += text
    }

    for (raw in markdown.replace("\r\n", "\n").lines()) {
        val line = raw.trim()
        val heading = HEADING.matchEntire(line)
        val bullet = BULLET.matchEntire(line)
        val number = NUMBER.matchEntire(line)
        when {
            line.isEmpty() -> flush()
            heading != null -> {
                flush()
                heading.groupValues[1].trim().takeIf { it.isNotEmpty() }?.let { blocks += ArticleBlock.Heading(it) }
            }
            line.startsWith(">") -> add('q', line.removePrefix(">").trim())
            bullet != null -> add('l', bullet.groupValues[1].trim())
            number != null -> add('l', number.groupValues[1].trim(), isNumbered = true)
            RULE.matches(line) -> flush()
            else -> add('p', line)
        }
    }
    flush()
    return blocks
}

/** Whether a quote's line says who said it rather than what: `— Name`, `– Name` or `- Name`. */
fun isAttribution(line: String): Boolean = line.startsWith("—") || line.startsWith("–") || line.startsWith("- ")

/** A run of a block's words, and how it is set. */
data class InlineRun(val text: String, val bold: Boolean = false, val italic: Boolean = false)

/**
 * The runs of [text]: `**bold**`, `*italic*`, and plain words between them. A lone `*` (a footnote mark, a
 * star in a name) is a plain character.
 */
fun inlineRuns(text: String): List<InlineRun> {
    val runs = mutableListOf<InlineRun>()
    var at = 0
    for (m in EMPHASIS.findAll(text)) {
        if (m.range.first > at) runs += InlineRun(text.substring(at, m.range.first))
        val bold = m.groups[1]?.value
        runs += if (bold != null) InlineRun(bold, bold = true) else InlineRun(m.groups[2]!!.value, italic = true)
        at = m.range.last + 1
    }
    if (at < text.length) runs += InlineRun(text.substring(at))
    return runs
}

private val HEADING = Regex("""^#{1,6}\s+(.*?)\s*#*$""")
private val BULLET = Regex("""^[-*•]\s+(.*)$""")
private val NUMBER = Regex("""^\d{1,2}[.)]\s+(.*)$""")
private val RULE = Regex("""^(-{3,}|\*{3,}|_{3,})$""")
private val EMPHASIS = Regex("""\*\*(\S(?:.*?\S)?)\*\*|\*(\S(?:.*?\S)?)\*""")
