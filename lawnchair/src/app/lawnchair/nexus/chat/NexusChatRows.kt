package app.lawnchair.nexus.chat

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.Public
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lawnchair.nexus.net.NexusSource

// The transcript's look, after nexus-chat's docs/anatomy.md: its colour roles are this overlay's
// palette (panel and surface Surface2, border BorderC, text TextC, muted Text2, faint Text3, Accent, Bad).
internal const val PROSE = 14.5f
internal const val SMALL = 12.5f
internal const val CAPTION = 11f
internal val Mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = SMALL.sp, lineHeight = (SMALL * 1.45f).sp, color = TextC)

private const val ARROW = "arrow"

/** One row of the transcript; [image] draws a picture the turn made. */
@Composable
internal fun ChatRowView(row: ChatRow, image: @Composable (String) -> Unit) {
    when (row) {
        is RunRow -> RunView(row)
        is BlockRow -> when (val block = row.block) {
            is UserTurn -> UserBubble(block.text)
            is AgentText -> ChatMarkdown(block.markdown, block.streaming, Modifier.padding(vertical = 6.dp))
            is Reasoning -> ReasoningLine(block)
            is ToolCall -> RunView(RunRow(listOf(block)))
            is Notice -> NoticeLine(block)
            is ImageBlock -> image(block.url)
            is LinksBlock -> SourceList(block.sources, Modifier.padding(vertical = 6.dp))
        }
    }
}

/** A sent turn waiting for its first word back. */
@Composable
internal fun PendingLine() {
    BasicText(
        text = "Thinking",
        modifier = Modifier.padding(vertical = 4.dp),
        style = TextStyle(fontSize = PROSE.sp, lineHeight = (PROSE * 1.45f).sp).copy(brush = travellingHighlight()),
    )
}

internal fun openLink(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}

@Composable
private fun UserBubble(text: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.CenterEnd) {
        BasicText(
            text = text,
            modifier = Modifier
                .widthIn(max = 560.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Surface2)
                .padding(horizontal = 14.dp, vertical = 9.dp),
            style = TextStyle(color = TextC, fontSize = PROSE.sp, lineHeight = (PROSE * 1.45f).sp),
        )
    }
}

@Composable
private fun RunView(run: RunRow) {
    var open by remember { mutableStateOf(false) }
    val sources = run.sources
    Column(Modifier.fillMaxWidth()) {
        WorkLine(run.title, open, live = run.live, failed = run.failed) { open = !open }
        if (sources.isNotEmpty()) SourceHosts(sources, Modifier.padding(top = 2.dp, bottom = 6.dp))
        if (open) CallCard(run.calls)
    }
}

/** A line of work: muted, red when it failed, a highlight travelling over it while it runs. */
@Composable
private fun WorkLine(title: String, open: Boolean, live: Boolean, failed: Boolean, onToggle: () -> Unit) {
    val style = TextStyle(
        color = if (failed && !live) Bad else Text2,
        fontSize = PROSE.sp,
        lineHeight = (PROSE * 1.45f).sp,
    )
    BasicText(
        text = withArrow(title),
        modifier = Modifier.clickable(onClick = onToggle).padding(vertical = 4.dp),
        style = if (live) style.copy(brush = travellingHighlight()) else style,
        inlineContent = arrow(open, 16.sp),
    )
}

@Composable
private fun CallCard(calls: List<ToolCall>) {
    val shape = RoundedCornerShape(10.dp)
    Column(
        Modifier
            .padding(top = 4.dp, bottom = 8.dp)
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, BorderC, shape),
    ) {
        calls.forEachIndexed { i, call ->
            if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(BorderC))
            key(call.id) { CallRow(call) }
        }
    }
}

@Composable
private fun CallRow(call: ToolCall) {
    var open by remember { mutableStateOf(false) }
    val style = TextStyle(color = if (call.failed) Bad else Text2, fontSize = PROSE.sp, lineHeight = (PROSE * 1.4f).sp)
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { open = !open }
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(
                text = call.title,
                modifier = Modifier.weight(1f),
                style = if (call.live) style.copy(brush = travellingHighlight()) else style,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            ArrowIcon(open, Modifier.size(18.dp))
        }
        if (open) CallDetail(call, Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp))
    }
}

@Composable
private fun CallDetail(call: ToolCall, modifier: Modifier) {
    val summary = call.summary?.trim().orEmpty()
    Column(modifier.fillMaxWidth()) {
        if (call.reasoning.isNotEmpty()) ReasoningToggle(call.reasoning, Modifier.padding(bottom = 4.dp))
        if (call.live) ElbowLine(call.progress ?: "Running…", Text2)
        if (summary.isNotEmpty()) ElbowLine(summary, if (call.failed) Bad else TextC)
        if (call.sources.isNotEmpty()) SourceList(call.sources, Modifier.padding(top = 4.dp, start = 16.dp))
    }
}

@Composable
private fun ElbowLine(text: String, color: Color) {
    Row(Modifier.padding(top = 1.dp)) {
        BasicText("⎿ ", style = Mono.copy(color = Text3))
        BasicText(text, Modifier.weight(1f), style = Mono.copy(color = color))
    }
}

@Composable
private fun ReasoningToggle(texts: List<String>, modifier: Modifier) {
    var open by remember { mutableStateOf(false) }
    Column(modifier) {
        BasicText(
            text = withArrow("Reasoning"),
            modifier = Modifier.clickable { open = !open },
            style = TextStyle(color = Text3, fontSize = CAPTION.sp),
            inlineContent = arrow(open, 14.sp),
        )
        if (open) texts.forEach { ReasoningText(it, Modifier.padding(top = 4.dp)) }
    }
}

@Composable
private fun ReasoningText(text: String, modifier: Modifier) {
    BasicText(
        text = text.trim(),
        modifier = modifier,
        style = TextStyle(color = Text3, fontSize = SMALL.sp, lineHeight = (SMALL * 1.45f).sp, fontStyle = FontStyle.Italic),
    )
}

/** Reasoning that led to no call: "Thought ›", or "Thinking" while it streams. */
@Composable
private fun ReasoningLine(block: Reasoning) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        WorkLine(if (block.streaming) "Thinking" else "Thought", open, live = block.streaming, failed = false) { open = !open }
        if (open) ReasoningText(block.text, Modifier.padding(bottom = 6.dp))
    }
}

@Composable
private fun NoticeLine(notice: Notice) {
    BasicText(
        text = buildAnnotatedString {
            if (notice.error) {
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace, fontSize = CAPTION.sp, color = Bad)) { append("error  ") }
            }
            append(notice.text)
        },
        modifier = Modifier.padding(vertical = 6.dp),
        style = TextStyle(color = if (notice.error) Bad else Text2, fontSize = SMALL.sp, lineHeight = (SMALL * 1.45f).sp),
    )
}

/** The hosts of a run's links as chips, at most four and then "+N"; a chip opens that host's first link. */
@Composable
private fun SourceHosts(sources: List<NexusSource>, modifier: Modifier) {
    val context = LocalContext.current
    val hosts = sources.distinctBy { it.site }
    val style = TextStyle(color = Text2, fontSize = CAPTION.sp)
    val shape = RoundedCornerShape(4.dp)
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        hosts.take(4).forEach { source ->
            Row(
                Modifier
                    .clip(shape)
                    .border(1.dp, BorderC, shape)
                    .clickable { openLink(context, source.url) }
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(Icons.Outlined.Public, null, Modifier.size(12.dp), colorFilter = ColorFilter.tint(Text3))
                Spacer(Modifier.width(4.dp))
                BasicText(source.site, style = style)
            }
        }
        if (hosts.size > 4) BasicText("+${hosts.size - 4}", Modifier.align(Alignment.CenterVertically), style = style)
    }
}

/** Links one to a line: the title underlined in the accent, then the host. */
@Composable
private fun SourceList(sources: List<NexusSource>, modifier: Modifier) {
    val context = LocalContext.current
    Column(modifier.fillMaxWidth()) {
        sources.forEach { source ->
            BasicText(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(color = Accent, textDecoration = TextDecoration.Underline)) { append(source.label) }
                    if (source.title.isNotBlank()) withStyle(SpanStyle(color = Text3)) { append("  ${source.site}") }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { openLink(context, source.url) }
                    .padding(vertical = 3.dp),
                style = TextStyle(fontSize = SMALL.sp, lineHeight = (SMALL * 1.4f).sp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun withArrow(title: String): AnnotatedString = buildAnnotatedString {
    append(title)
    append(" ")
    appendInlineContent(ARROW, "›")
}

private fun arrow(open: Boolean, size: TextUnit): Map<String, InlineTextContent> = mapOf(
    ARROW to InlineTextContent(Placeholder(size, size, PlaceholderVerticalAlign.TextCenter)) {
        ArrowIcon(open, Modifier.fillMaxSize())
    },
)

@Composable
private fun ArrowIcon(open: Boolean, modifier: Modifier) {
    Image(
        imageVector = if (open) Icons.Filled.ExpandMore else Icons.Filled.ChevronRight,
        contentDescription = null,
        modifier = modifier,
        colorFilter = ColorFilter.tint(Text3),
    )
}

/** Muted text with the accent travelling across it, for what is still running (no spinner). */
@Composable
private fun travellingHighlight(): Brush {
    val at by rememberInfiniteTransition(label = "travel").animateFloat(
        initialValue = -0.3f,
        targetValue = 1.3f,
        animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing)),
        label = "at",
    )
    return remember(at) {
        object : ShaderBrush() {
            override fun createShader(size: Size): Shader = LinearGradientShader(
                from = Offset(size.width * (at - 0.3f), 0f),
                to = Offset(size.width * (at + 0.3f), 0f),
                colors = listOf(Text2, Accent, Text2),
            )
        }
    }
}
