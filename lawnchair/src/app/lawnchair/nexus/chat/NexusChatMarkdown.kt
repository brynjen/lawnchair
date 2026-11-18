package app.lawnchair.nexus.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** A part of an answer, as [markdownBlocks] reads it. */
internal sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class Item(val marker: String, val depth: Int, val text: String) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class Code(val text: String) : MdBlock
    data class Table(val rows: List<List<String>>) : MdBlock
    data object Rule : MdBlock
}

/**
 * An answer's markdown: headings, paragraphs, lists, quotes, code, tables as monospace lines, and
 * bold, italic, struck, inline code and links (bare URLs too) that open in the browser. While it
 * [streaming], it is redrawn at most every 80 ms, with an unclosed fence or backtick closed for drawing.
 */
@Composable
internal fun ChatMarkdown(markdown: String, streaming: Boolean, modifier: Modifier = Modifier) {
    val latest by rememberUpdatedState(markdown)
    var shown by remember { mutableStateOf(markdown) }
    LaunchedEffect(streaming) {
        while (streaming) {
            shown = latest
            delay(80)
        }
    }
    val text = if (streaming) shown else markdown
    val blocks = remember(text, streaming) { markdownBlocks(if (streaming) closeDanglingMarkdown(text) else text) }
    val context = LocalContext.current
    val onLink = remember(context) { { url: String -> openLink(context, url) } }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { MarkdownBlockView(it, onLink) }
    }
}

private val Prose = TextStyle(color = TextC, fontSize = PROSE.sp, lineHeight = (PROSE * 1.5f).sp)

@Composable
private fun MarkdownBlockView(block: MdBlock, onLink: (String) -> Unit) {
    when (block) {
        is MdBlock.Heading -> BasicText(
            text = inline(block.text, onLink),
            modifier = Modifier.padding(top = 4.dp),
            style = when (block.level) {
                1 -> Prose.copy(fontSize = (PROSE + 6).sp, lineHeight = ((PROSE + 6) * 1.3f).sp, fontWeight = FontWeight.Bold)
                2 -> Prose.copy(fontSize = (PROSE + 3).sp, lineHeight = ((PROSE + 3) * 1.3f).sp, fontWeight = FontWeight.Bold)
                else -> Prose.copy(fontWeight = FontWeight.SemiBold)
            },
        )
        is MdBlock.Paragraph -> BasicText(inline(block.text, onLink), style = Prose)
        is MdBlock.Item -> Row(Modifier.padding(start = (block.depth * 16).dp)) {
            BasicText(block.marker, Modifier.padding(end = 8.dp), style = Prose)
            BasicText(inline(block.text, onLink), Modifier.weight(1f), style = Prose)
        }
        is MdBlock.Quote -> BasicText(
            text = inline(block.text, onLink),
            modifier = Modifier
                .drawBehind { drawRect(BorderC, size = Size(3.dp.toPx(), size.height)) }
                .padding(start = 12.dp),
            style = Prose.copy(color = Text2),
        )
        is MdBlock.Code -> CodeBox(block.text)
        is MdBlock.Table -> CodeBox(tableLines(block.rows))
        MdBlock.Rule -> Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp).background(BorderC))
    }
}

@Composable
private fun CodeBox(code: String) {
    val shape = RoundedCornerShape(6.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Surface2)
            .border(1.dp, BorderC, shape)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        SelectionContainer { BasicText(code, style = Mono) }
    }
}

private val FENCE = Regex("""^\s*(```|~~~)""")
private val HEADING = Regex("""^\s{0,3}(#{1,6})\s+(.*?)(\s+#+)?\s*$""")
private val RULE = Regex("""^\s{0,3}([-*_])(\s*\1){2,}\s*$""")
private val ITEM = Regex("""^(\s*)([-*+]|\d{1,9}[.)])\s+(.*)$""")
private val QUOTE = Regex("""^\s{0,3}>\s?(.*)$""")
private val TABLE_RULE = Regex("""^\s*\|?(\s*:?-+:?\s*\|)+\s*(:?-+:?\s*)?$""")

/** [markdown] as blocks, line by line; inline marks stay in the text for [inline]. */
internal fun markdownBlocks(markdown: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val paragraph = mutableListOf<String>()
    fun flush() {
        if (paragraph.isNotEmpty()) blocks += MdBlock.Paragraph(paragraph.joinToString(" "))
        paragraph.clear()
    }
    val lines = markdown.lines()
    var i = 0
    var inItem = false
    while (i < lines.size) {
        val line = lines[i]
        val fence = FENCE.find(line)?.groupValues?.get(1)
        val heading = HEADING.matchEntire(line)
        val item = ITEM.matchEntire(line)
        val quote = QUOTE.matchEntire(line)
        when {
            line.isBlank() -> flush()
            fence != null -> {
                flush()
                val code = mutableListOf<String>()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith(fence)) code += lines[i++]
                blocks += MdBlock.Code(code.joinToString("\n"))
            }
            heading != null -> {
                flush()
                blocks += MdBlock.Heading(heading.groupValues[1].length, heading.groupValues[2])
            }
            RULE.matches(line) -> {
                flush()
                blocks += MdBlock.Rule
            }
            line.trimStart().startsWith("|") -> {
                flush()
                val rows = mutableListOf<List<String>>()
                while (i < lines.size && lines[i].trimStart().startsWith("|")) {
                    if (!TABLE_RULE.matches(lines[i])) rows += cells(lines[i])
                    i++
                }
                i--
                blocks += MdBlock.Table(rows)
            }
            item != null -> {
                flush()
                val (indent, marker, text) = item.destructured
                val bullet = if (marker[0].isDigit()) marker else "•"
                blocks += MdBlock.Item(bullet, indent.length / 2, text.trim())
            }
            quote != null -> {
                flush()
                val last = blocks.lastOrNull()
                val text = quote.groupValues[1].trim()
                if (last is MdBlock.Quote && i > 0 && lines[i - 1].isNotBlank()) {
                    blocks[blocks.lastIndex] = last.copy(text = "${last.text} $text")
                } else {
                    blocks += MdBlock.Quote(text)
                }
            }
            inItem && paragraph.isEmpty() -> {
                val last = blocks.last() as MdBlock.Item
                blocks[blocks.lastIndex] = last.copy(text = "${last.text} ${line.trim()}")
            }
            else -> paragraph += line.trim()
        }
        inItem = blocks.lastOrNull() is MdBlock.Item && paragraph.isEmpty() && line.isNotBlank()
        i++
    }
    flush()
    return blocks
}

private fun cells(line: String): List<String> =
    line.trim().removePrefix("|").removeSuffix("|").split("|").map { it.trim().replace("**", "").replace("`", "") }

private fun tableLines(rows: List<List<String>>): String {
    val widths = IntArray(rows.maxOfOrNull { it.size } ?: 0)
    rows.forEach { row -> row.forEachIndexed { c, cell -> widths[c] = maxOf(widths[c], cell.length) } }
    val lines = rows.map { row -> row.mapIndexed { c, cell -> cell.padEnd(widths[c]) }.joinToString("  ").trimEnd() }
    val rule = widths.joinToString("  ") { "─".repeat(it) }
    return (lines.take(1) + (if (lines.size > 1) listOf(rule) else emptyList()) + lines.drop(1)).joinToString("\n")
}

/** While text streams: an unclosed ``` fence or ` is closed, for drawing only. */
internal fun closeDanglingMarkdown(text: String): String {
    var result = text
    if (Regex("""^\s*```""", RegexOption.MULTILINE).findAll(result).count() % 2 == 1) {
        if (!result.endsWith("\n")) result += "\n"
        result += "```"
    }
    val outsideFences = result.replace(Regex("""```[\s\S]*?```"""), "")
    if (Regex("""(?<!`)`(?!`)""").findAll(outsideFences).count() % 2 == 1) result += "`"
    return result
}

private val Bold = SpanStyle(fontWeight = FontWeight.Bold)
private val Italic = SpanStyle(fontStyle = FontStyle.Italic)
private val Struck = SpanStyle(textDecoration = TextDecoration.LineThrough)
private val InlineCode = SpanStyle(fontFamily = FontFamily.Monospace, fontSize = (PROSE - 1).sp, background = Surface2)
private val LinkLook = TextLinkStyles(SpanStyle(color = Accent, textDecoration = TextDecoration.Underline))
private const val ESCAPED = "\\`*_{}[]()#+-.!|~<>"

private fun inline(text: String, onLink: (String) -> Unit): AnnotatedString =
    buildAnnotatedString { appendInline(text, onLink, inLink = false) }

private fun AnnotatedString.Builder.appendInline(s: String, onLink: (String) -> Unit, inLink: Boolean) {
    fun link(url: String, label: AnnotatedString.Builder.() -> Unit) {
        withLink(LinkAnnotation.Url(url, LinkLook, LinkInteractionListener { onLink(url) })) { label() }
    }
    var i = 0
    while (i < s.length) {
        val c = s[i]
        val next = s.getOrNull(i + 1)
        when {
            c == '\\' && next != null && next in ESCAPED -> {
                append(next)
                i += 2
            }
            c == '`' -> {
                val ticks = s.substring(i).takeWhile { it == '`' }
                val end = s.indexOf(ticks, i + ticks.length)
                if (end < 0) {
                    append(ticks)
                } else {
                    withStyle(InlineCode) { append(s.substring(i + ticks.length, end).trim()) }
                }
                i = if (end < 0) i + ticks.length else end + ticks.length
            }
            (c == '*' || c == '_' || c == '~') && next == c -> {
                val mark = "$c$c"
                val end = s.indexOf(mark, i + 2)
                if (end <= i + 2 || (c == '_' && !wordEdge(s, i - 1))) {
                    append(mark)
                    i += 2
                } else {
                    withStyle(if (c == '~') Struck else Bold) { appendInline(s.substring(i + 2, end), onLink, inLink) }
                    i = end + 2
                }
            }
            (c == '*' || c == '_') && next != null && !next.isWhitespace() && (c == '*' || wordEdge(s, i - 1)) -> {
                val end = italicEnd(s, i + 1, c)
                if (end < 0) {
                    append(c)
                    i++
                } else {
                    withStyle(Italic) { appendInline(s.substring(i + 1, end), onLink, inLink) }
                    i = end + 1
                }
            }
            c == '!' && next == '[' && !inLink -> i++
            c == '[' && !inLink -> {
                val close = s.indexOf("](", i + 1)
                val end = if (close < 0) -1 else s.indexOf(')', close + 2)
                if (end < 0) {
                    append(c)
                    i++
                } else {
                    val url = s.substring(close + 2, end).trim().substringBefore(' ')
                    link(url) { appendInline(s.substring(i + 1, close), onLink, inLink = true) }
                    i = end + 1
                }
            }
            c == '<' && !inLink && (s.startsWith("http://", i + 1) || s.startsWith("https://", i + 1)) && s.indexOf('>', i) > 0 -> {
                val end = s.indexOf('>', i)
                val url = s.substring(i + 1, end)
                link(url) { append(url) }
                i = end + 1
            }
            !inLink && (s.startsWith("http://", i) || s.startsWith("https://", i)) && wordEdge(s, i - 1) -> {
                val end = urlEnd(s, i)
                val url = s.substring(i, end)
                link(url) { append(url) }
                i = end
            }
            else -> {
                append(c)
                i++
            }
        }
    }
}

private fun wordEdge(s: String, at: Int): Boolean = at !in s.indices || !s[at].isLetterOrDigit()

private fun italicEnd(s: String, from: Int, mark: Char): Int {
    for (j in from + 1 until s.length) {
        if (s[j] != mark || s[j - 1].isWhitespace() || s[j - 1] == mark || s.getOrNull(j + 1) == mark) continue
        if (mark == '_' && !wordEdge(s, j + 1)) continue
        return j
    }
    return -1
}

/** Where a bare URL at [start] ends: before whitespace, and before trailing punctuation or an unopened ")". */
private fun urlEnd(s: String, start: Int): Int {
    var end = start
    while (end < s.length && !s[end].isWhitespace() && s[end] !in "<>\"") end++
    while (end > start) {
        val last = s[end - 1]
        val unopened = last == ')' && s.substring(start, end).count { it == '(' } < s.substring(start, end).count { it == ')' }
        if (last !in ".,;:!?'*_~" && !unopened) break
        end--
    }
    return end
}
