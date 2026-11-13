package app.lawnchair.nexus.search

import android.content.Context
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Newspaper
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lawnchair.nexus.news.NewsItem
import app.lawnchair.nexus.news.NewsKind
import app.lawnchair.theme.color.tokens.ColorTokens
import com.android.launcher3.R
import com.android.launcher3.util.Themes
import com.android.systemui.shared.system.BlurUtils

/** What a tap on a card of the row does (NexusSearchOpen). */
interface NexusSearchActions {
    fun ask(query: String)
    fun openNote(hit: NexusNoteHit)
    fun openChat(hit: NexusChatHit)
    fun openNews(article: NewsItem)
}

/**
 * The [NexusSearchRow] as a list item of the drawer's search: one horizontal row of cards the height of
 * one card, whatever the row holds, so the apps below it stay where they are while it fills in.
 */
class NexusSearchRowView(context: Context, actions: NexusSearchActions) : FrameLayout(context) {
    private val row = mutableStateOf<NexusSearchRow?>(null)

    init {
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        val colors = RowColors.of(context)
        val inset = context.resources.getDimensionPixelSize(R.dimen.search_decoration_padding)
        addView(
            ComposeView(context).apply {
                setContent {
                    val padding = with(LocalDensity.current) { inset.toDp() }
                    row.value?.let { NexusSearchRowContent(it, colors, actions, padding) }
                }
            },
        )
    }

    fun bind(value: NexusSearchRow) {
        row.value = value
    }
}

/** The drawer's own colours, so the cards read as its result groups do. */
private data class RowColors(val card: Color, val accent: Color, val text: Color, val subtle: Color) {
    companion object {
        fun of(context: Context): RowColors {
            val blur = BlurUtils.supportsBlursOnWindows()
            val group = (if (blur) ColorTokens.GroupHighlightBlur else ColorTokens.GroupHighlight).resolveColor(context)
            return RowColors(
                card = Color(group),
                accent = Color(ColorTokens.ColorAccent.resolveColor(context)),
                text = Color(Themes.getAttrColor(context, android.R.attr.textColorPrimary)),
                subtle = Color(Themes.getAttrColor(context, android.R.attr.textColorSecondary)),
            )
        }
    }
}

private val CardWidth = 208.dp
private val CardHeight = 108.dp
private val CardShape = RoundedCornerShape(20.dp)

/** How many ghost cards stand in for the hits while the server is asked. */
private const val GHOSTS = 2

private sealed interface Card {
    data class Note(val hit: NexusNoteHit) : Card
    data class Chat(val hit: NexusChatHit) : Card
    data class News(val article: NewsItem) : Card
}

@Composable
private fun NexusSearchRowContent(row: NexusSearchRow, colors: RowColors, actions: NexusSearchActions, inset: Dp) {
    val cards = remember(row.hits) {
        row.hits.notes.map { Card.Note(it) } + row.hits.chats.map { Card.Chat(it) } + row.hits.news.map { Card.News(it) }
    }
    LazyRow(
        modifier = Modifier.fillMaxWidth().height(CardHeight + inset * 2),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = inset),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "ask") { AskCard(row.query, colors) { actions.ask(row.query) } }
        if (row.searching) {
            items(GHOSTS, key = { "ghost$it" }) { GhostCard(colors) }
        } else {
            items(cards, key = { it.key }) { card ->
                when (card) {
                    is Card.Note -> HitCard(Icons.Rounded.Description, "Note", card.hit.title, card.hit.snippet, colors) {
                        actions.openNote(card.hit)
                    }
                    is Card.Chat -> HitCard(Icons.Rounded.ChatBubbleOutline, "Chat", card.hit.title, card.hit.snippet, colors) {
                        actions.openChat(card.hit)
                    }
                    is Card.News -> {
                        val paper = card.article.kind == NewsKind.Paper
                        HitCard(
                            if (paper) Icons.Rounded.Science else Icons.Rounded.Newspaper,
                            card.article.sourceName.ifBlank { if (paper) "Paper" else "News" },
                            card.article.title,
                            card.article.synopsis,
                            colors,
                        ) { actions.openNews(card.article) }
                    }
                }
            }
        }
    }
}

private val Card.key: String
    get() = when (this) {
        is Card.Note -> "note:${hit.path}"
        is Card.Chat -> "chat:${hit.conversationId}"
        is Card.News -> "news:${article.id}"
    }

@Composable
private fun AskCard(query: String, colors: RowColors, onClick: () -> Unit) {
    CardFrame(colors.accent.copy(alpha = 0.16f), onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.drawable.ic_nexus), contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Ask Nexus", color = colors.accent, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "“$query”",
            color = colors.text,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun HitCard(icon: ImageVector, kind: String, title: String, snippet: String?, colors: RowColors, onClick: () -> Unit) {
    CardFrame(colors.card, onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = colors.subtle, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(5.dp))
            Text(kind, color = colors.subtle, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            title,
            color = colors.text,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            maxLines = if (snippet == null) 3 else 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (snippet != null) {
            Spacer(Modifier.height(2.dp))
            Text(snippet, color = colors.subtle, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A card's place while the server is asked: the shape of a hit, gently breathing, never a spinner. */
@Composable
private fun GhostCard(colors: RowColors) {
    val breath by rememberInfiniteTransition(label = "ghost").animateFloat(
        initialValue = 0.45f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "ghost",
    )
    val bar = colors.subtle.copy(alpha = 0.18f * breath)
    CardFrame(colors.card.copy(alpha = colors.card.alpha * breath), onClick = null) {
        Box(Modifier.width(56.dp).height(10.dp).clip(RoundedCornerShape(5.dp)).background(bar))
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth(0.85f).height(12.dp).clip(RoundedCornerShape(6.dp)).background(bar))
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth(0.6f).height(12.dp).clip(RoundedCornerShape(6.dp)).background(bar))
    }
}

@Composable
private fun CardFrame(background: Color, onClick: (() -> Unit)?, content: @Composable () -> Unit) {
    Column(
        Modifier
            .width(CardWidth)
            .height(CardHeight)
            .clip(CardShape)
            .background(background)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) { content() }
}
