package app.lawnchair.nexuslauncher

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lawnchair.nexus.NexusConfig
import app.lawnchair.nexus.net.NexusClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Nexus design tokens (mirrors nexus_mobile core/tokens.dart, dark palette).
private val Bg = Color(0xFF0A0A12)
private val Surface = Color(0xFF14141E)
private val Surface2 = Color(0xFF1C1C2A)
private val BorderC = Color(0x14FFFFFF)
private val TextC = Color(0xFFF0F0F5)
private val Text2 = Color(0x9EF0F0F5)
private val Text3 = Color(0x61F0F0F5)
private val Accent = Color(0xFF7AB8FF)
private val Good = Color(0xFF5EEAD4)
private val OnAccent = Color(0xFF0A1828)

private data class NexusArticle(
    val id: String,
    val title: String,
    val synopsis: String,
    val body: String,
    val source: String,
    val url: String,
    val ago: String,
    val tags: List<String>,
)

private val TAGS = listOf("tech", "ai", "research", "science", "business", "security")

private val MOCK = listOf(
    NexusArticle("1", "Open-weight models close the gap with frontier labs",
        "A new wave of permissively licensed models matches last year's flagship systems on reasoning benchmarks.",
        "A fresh crop of open-weight releases now trades blows with last year's flagship proprietary systems on "
            + "math and coding evaluations. Teams weighing self-hosting are re-running the build-vs-buy math as "
            + "inference costs fall and licenses loosen.",
        "Hacker News", "https://news.ycombinator.com", "42m ago", listOf("ai", "tech")),
    NexusArticle("2", "On-device speaker verification at scale",
        "ECAPA-style embeddings run comfortably on consumer hardware, enabling private wake-word identity.",
        "Speaker-embedding models small enough to run on a phone are making private, on-device wake-word identity "
            + "practical — no audio leaves the device. The approach pairs a compact encoder with a cosine match "
            + "against an enrolled voiceprint.",
        "arXiv", "https://arxiv.org", "3h ago", listOf("research", "ai", "science")),
    NexusArticle("3", "The quiet return of the local-first application",
        "Developers rediscover offline-capable, sync-later architectures as edge hardware gets fast enough.",
        "Local-first design — where the device holds the source of truth and syncs opportunistically — is resurging "
            + "as edge hardware gets fast enough to keep the whole stack on-device. The payoff is instant UX and "
            + "resilience to flaky networks.",
        "The Verge", "https://www.theverge.com", "6h ago", listOf("tech")),
    NexusArticle("4", "Postgres extensions turn the database into a search engine",
        "Vector similarity and graph traversal land in mainline tooling — semantic search without a separate service.",
        "With vector similarity and graph traversal now available as mainline Postgres extensions, small teams can "
            + "ship semantic search without standing up a separate service. One database, one operational surface.",
        "InfoQ", "https://www.infoq.com", "11h ago", listOf("tech", "research")),
    NexusArticle("5", "Chip makers pivot to inference-first accelerators",
        "The next hardware cycle targets cheap, low-latency inference for always-on assistants.",
        "As training budgets consolidate, the next hardware cycle is aimed squarely at cheap, low-latency inference "
            + "for always-on assistants — the workload that actually runs in your pocket and your home.",
        "Bloomberg", "https://www.bloomberg.com", "20h ago", listOf("business", "tech", "ai")),
    NexusArticle("6", "Threat-modelling voice assistants",
        "From replay attacks to prompt injection over audio — the attack surface when your home listens back.",
        "A practical survey of the voice-assistant attack surface: replay attacks, spoofed wake words, and prompt "
            + "injection delivered over audio. The mitigations lean on speaker verification and strict tool gating.",
        "Krebs on Security", "https://krebsonsecurity.com", "1d ago", listOf("security", "ai")),
    NexusArticle("7", "Fusion milestone: net-positive shot sustained for minutes",
        "A tokamak team reports a stability record edging the field closer to continuous operation.",
        "A tokamak team reports sustaining a net-positive plasma for minutes — a stability record that edges the "
            + "field toward continuous operation, though grid-scale power remains years away.",
        "Nature", "https://www.nature.com", "1d ago", listOf("science", "research")),
    NexusArticle("8", "Startups bet on tiny models for the phone in your pocket",
        "Sub-billion-parameter models tuned for narration and intent detection are becoming the glue of mobile AI.",
        "A wave of startups is betting on sub-billion-parameter models fine-tuned for narration and intent "
            + "detection — the quiet glue layer that makes mobile AI products feel instant and private.",
        "TechCrunch", "https://techcrunch.com", "2d ago", listOf("business", "ai", "tech")),
)

/**
 * The Nexus News "-1" panel. Pure Compose, locally-generated mock articles (each links out to its
 * source). Each sub-view is swipe-left dismissable. [onClose] closes the whole panel. A pull-to-
 * refresh triggers a (currently simulated 10s) load that drives the orb's loading animation. Data
 * will later come from a server-backed source (Serverpod → dart-search-engine).
 */
@Composable
fun NexusNewsScreen(onClose: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var activeTag by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<NexusArticle?>(null) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent,
            onPrimary = OnAccent,
            background = Bg,
            surface = Surface,
            onSurface = TextC,
        ),
    ) {
        Box(Modifier.fillMaxSize().background(Bg)) {
            val article = selected
            if (article != null) {
                DetailsScreen(article, onBack = { selected = null })
            } else {
                ListScreen(
                    query = query,
                    onQuery = { query = it },
                    activeTag = activeTag,
                    onTag = { activeTag = it },
                    loading = loading,
                    onRefresh = {
                        scope.launch {
                            loading = true
                            delay(10_000)
                            loading = false
                        }
                    },
                    onOpen = { selected = it },
                    onClose = onClose,
                )
            }
        }
    }
}

@Composable
private fun ListScreen(
    query: String,
    onQuery: (String) -> Unit,
    activeTag: String?,
    onTag: (String?) -> Unit,
    loading: Boolean,
    onRefresh: () -> Unit,
    onOpen: (NexusArticle) -> Unit,
    onClose: () -> Unit,
) {
    val filtered = MOCK.filter { a ->
        (activeTag == null || a.tags.contains(activeTag)) &&
            (query.isBlank() ||
                a.title.contains(query, true) ||
                a.synopsis.contains(query, true))
    }

    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars)
            .swipeToDismiss(toRight = false, onDismiss = onClose),
    ) {
        Header(loading)
        Spacer(Modifier.height(20.dp))
        SearchField(query, onQuery)
        Spacer(Modifier.height(12.dp))
        TagRow(activeTag, onTag)
        Spacer(Modifier.height(8.dp))
        PullToRefreshBox(
            isRefreshing = loading,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            if (filtered.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (query.isBlank()) "No news right now." else "No news matches \"$query\".",
                        style = TextStyle(color = Text3, fontSize = 15.sp),
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item(key = "eir") { EirCard() }
                    items(filtered, key = { it.id }) { NewsCard(it, query) { onOpen(it) } }
                }
            }
        }
    }
}

@Composable
private fun Header(loading: Boolean) {
    // Live connection dot: while the News overlay is on screen, actively poll the server
    // (this is the one place active polling is allowed — the user is looking at dynamic content).
    // teal = online, red = offline/unreachable, grey = not configured / unknown. The effect is
    // cancelled automatically when the overlay leaves composition, so it never polls in the
    // background.
    val context = LocalContext.current
    var online by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) {
        val client = NexusClient()
        while (true) {
            val status = withContext(Dispatchers.IO) { NexusConfig.read(context) }
            online = if (status.enabled) {
                withContext(Dispatchers.IO) { client.ping(status.serverUrl, NexusConfig.accessToken) }
            } else {
                null
            }
            delay(10_000)
        }
    }
    val dotColor = when (online) {
        true -> Good
        false -> Color(0xFFFF6B6B)
        null -> Text3
    }
    Column(Modifier.fillMaxWidth().padding(16.dp, 12.dp, 16.dp, 0.dp)) {
        Box(Modifier.fillMaxWidth()) {
            Text(
                "Nexus News",
                style = TextStyle(color = TextC, fontSize = 18.sp, fontWeight = FontWeight.Medium),
                modifier = Modifier.align(Alignment.Center),
            )
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(dotColor),
            )
        }
        Spacer(Modifier.height(20.dp))
        NeuralOrb(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .size(112.dp),
            loading = loading,
        )
    }
}

@Composable
private fun SearchField(value: String, onValueChange: (String) -> Unit) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(color = TextC, fontSize = 15.sp),
            cursorBrush = SolidColor(Accent),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Surface2)
                .border(1.dp, BorderC, RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp),
            decorationBox = { inner ->
                if (value.isEmpty()) {
                    Text("Search news", style = TextStyle(color = Text3, fontSize = 15.sp))
                }
                inner()
            },
        )
    }
}

@Composable
private fun TagRow(activeTag: String?, onSelect: (String?) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { TagPill("All", activeTag == null) { onSelect(null) } }
        items(TAGS, key = { it }) { tag ->
            TagPill(tag.replaceFirstChar { it.uppercase() }, activeTag == tag) {
                onSelect(if (activeTag == tag) null else tag)
            }
        }
    }
}

@Composable
private fun TagPill(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(99.dp))
            .background(if (selected) Accent else Surface2)
            .border(1.dp, if (selected) Color.Transparent else BorderC, RoundedCornerShape(99.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            label,
            style = TextStyle(
                color = if (selected) OnAccent else TextC,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            ),
        )
    }
}

@Composable
private fun NewsCard(article: NexusArticle, query: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(Surface)
            .border(1.dp, BorderC, RoundedCornerShape(22.dp))
            .clickable(onClick = onClick)
            .padding(12.dp),
    ) {
        Box(
            Modifier
                .size(76.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Surface2)
                .border(1.dp, BorderC, RoundedCornerShape(14.dp)),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.fillMaxWidth()) {
            Text(
                highlight(article.title, query),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(color = TextC, fontSize = 15.sp, fontWeight = FontWeight.Medium),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                highlight(article.synopsis, query),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(color = Text2, fontSize = 13.sp),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "${article.source.uppercase()}  ·  ${article.ago}",
                style = TextStyle(color = Text3, fontSize = 11.sp),
            )
        }
    }
}

@Composable
private fun DetailsScreen(article: NexusArticle, onBack: () -> Unit) {
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars)
            .swipeToDismiss(toRight = true, onDismiss = onBack),
    ) {
        // Large top bar: back arrow, then the full title (wraps — no ellipsis).
        Column(Modifier.fillMaxWidth().padding(8.dp, 4.dp, 16.dp, 0.dp)) {
            BackArrow(onBack)
            Spacer(Modifier.height(4.dp))
            Text(
                article.title,
                style = TextStyle(color = TextC, fontSize = 24.sp, fontWeight = FontWeight.SemiBold),
                modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 12.dp),
            )
        }
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Text(
                "${article.source.uppercase()}  ·  ${article.ago}",
                style = TextStyle(color = Text3, fontSize = 11.sp),
            )
            Spacer(Modifier.height(16.dp))
            Text(article.body, style = TextStyle(color = Text2, fontSize = 15.sp))
            Spacer(Modifier.height(24.dp))
            Box(
                Modifier
                    .clip(RoundedCornerShape(99.dp))
                    .background(Accent)
                    .clickable {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(article.url))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    }
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            ) {
                Text(
                    "Open source  ↗",
                    style = TextStyle(color = OnAccent, fontSize = 14.sp, fontWeight = FontWeight.Medium),
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(article.url, style = TextStyle(color = Text3, fontSize = 12.sp))
        }
    }
}

/**
 * Horizontal swipe-to-dismiss, coexisting with vertical scrolling children. [toRight] picks the
 * direction: the panel (list) closes on a left-swipe; the details screen "backs" on a right-swipe
 * (Android's back-gesture direction).
 */
private fun Modifier.swipeToDismiss(toRight: Boolean, onDismiss: () -> Unit): Modifier = composed {
    val thresholdPx = with(LocalDensity.current) { 120.dp.toPx() }
    val acc = remember { floatArrayOf(0f) }
    draggable(
        orientation = Orientation.Horizontal,
        state = rememberDraggableState { delta -> acc[0] += delta },
        onDragStopped = { velocity ->
            val dismiss = if (toRight) {
                acc[0] > thresholdPx || velocity > 1500f
            } else {
                acc[0] < -thresholdPx || velocity < -1500f
            }
            acc[0] = 0f
            if (dismiss) onDismiss()
        },
    )
}

@Composable
private fun BackArrow(onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(22.dp)) {
            val w = size.width
            val mid = size.height / 2f
            val left = w * 0.18f
            val right = w * 0.82f
            val head = w * 0.26f
            val sw = 2f * (size.minDimension / 22f) * 1.6f
            drawLine(TextC, Offset(right, mid), Offset(left, mid), sw, StrokeCap.Round)
            drawLine(TextC, Offset(left + head, mid - head), Offset(left, mid), sw, StrokeCap.Round)
            drawLine(TextC, Offset(left + head, mid + head), Offset(left, mid), sw, StrokeCap.Round)
        }
    }
}

// buildAnnotatedString-style highlight of case-insensitive [query] occurrences.
private fun highlight(text: String, query: String): AnnotatedString = buildAnnotatedString {
    val q = query.trim()
    if (q.isEmpty()) {
        append(text)
        return@buildAnnotatedString
    }
    val lower = text.lowercase()
    val needle = q.lowercase()
    var start = 0
    while (true) {
        val idx = lower.indexOf(needle, start)
        if (idx < 0) {
            append(text.substring(start))
            break
        }
        append(text.substring(start, idx))
        withStyle(SpanStyle(color = Accent, fontWeight = FontWeight.Bold)) {
            append(text.substring(idx, idx + needle.length))
        }
        start = idx + needle.length
    }
}

// Text wrappers so we don't depend on Material theming for body text.
@Composable
private fun Text(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) = BasicText(text = text, modifier = modifier, style = style, maxLines = maxLines, overflow = overflow)

@Composable
private fun Text(
    text: AnnotatedString,
    style: TextStyle,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) = BasicText(text = text, modifier = modifier, style = style, maxLines = maxLines, overflow = overflow)
