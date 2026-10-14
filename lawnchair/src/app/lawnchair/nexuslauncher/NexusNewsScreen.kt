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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
import app.lawnchair.nexus.NexusLock
import app.lawnchair.nexus.NexusLockNudge
import app.lawnchair.nexus.net.NexusClient
import app.lawnchair.nexus.news.NewsCache
import app.lawnchair.nexus.news.NewsFeed
import app.lawnchair.nexus.news.NewsItem
import app.lawnchair.nexus.news.NewsRepository
import app.lawnchair.nexus.news.agoText
import app.lawnchair.nexus.news.newsTags
import java.io.File
import java.time.Instant
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

/** How often the feed is read again while the launcher lives; a nightly sweep makes this plenty. */
private const val NEWS_REFRESH_MS = 15 * 60_000L

/** Who the news is for, as the Nexus app says it right now: the lock, and the server and session key it is under. */
private data class NewsGate(val lock: NexusLock, val serverUrl: String, val authKey: String)

/**
 * The Nexus News "-1" panel: the server's news feed (each card links out to its source), shown from
 * the copy the phone saved of it while the server is asked again. Each sub-view is swipe-left
 * dismissable. [onClose] closes the whole panel. A pull-to-refresh asks the server to sweep its
 * sources and drives the orb's loading animation.
 */
@Composable
fun NexusNewsScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    // Whether the Nexus app is signed in and set up, re-read while the panel is open: signing in
    // lets the news in without closing it. Until then nothing of the news shows.
    val gate by produceState(NewsGate(NexusLock.NeedsSignIn, "", "")) {
        while (true) {
            value = withContext(Dispatchers.IO) {
                NexusConfig.read(context).let { NewsGate(it.lock, it.serverUrl, it.authKey) }
            }
            delay(2_000)
        }
    }

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
            if (gate.lock != NexusLock.None) {
                LockedScreen(gate.lock, onClose)
            } else {
                // Everything the page holds (the feed, the search, the open article) is one person's
                // on one server: signing out or in as someone else starts it over.
                key(gate.serverUrl, gate.authKey) { NewsPanel(gate.serverUrl, gate.authKey, onClose) }
            }
        }
    }
}

@Composable
private fun NewsPanel(serverUrl: String, authKey: String, onClose: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var activeTag by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<NewsItem?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    // Null only until the saved copy or the first answer; after that the page always has a feed to
    // show, an empty one when there is nothing saved and no network.
    var feed by remember { mutableStateOf<NewsFeed?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val news = remember {
        NewsRepository(NexusClient(), NewsCache(File(context.cacheDir, "nexus-news")), serverUrl, authKey)
    }
    // For the "5m ago" lines; read again whenever the feed is.
    val now = remember(feed) { Instant.now() }

    // The saved copy first, so the page is never blank while the server answers; then the server,
    // again every NEWS_REFRESH_MS. A failed read leaves what is shown as it is.
    LaunchedEffect(news) {
        feed = news.saved()
        while (true) {
            feed = news.load() ?: feed ?: NewsFeed.EMPTY
            delay(NEWS_REFRESH_MS)
        }
    }

    val article = selected
    if (article != null) {
        DetailsScreen(article, now, onBack = { selected = null })
    } else {
        ListScreen(
            items = feed?.items,
            now = now,
            query = query,
            onQuery = { query = it },
            activeTag = activeTag,
            onTag = { activeTag = it },
            refreshing = refreshing,
            onRefresh = {
                scope.launch {
                    refreshing = true
                    feed = news.sweep() ?: feed ?: NewsFeed.EMPTY
                    refreshing = false
                }
            },
            onOpen = { selected = it },
            onClose = onClose,
        )
    }
}

/**
 * The panel for a phone whose Nexus app is not ready (no server, nobody signed in, onboarding
 * unfinished): the news stays hidden and the one thing to do is said, with a way into the
 * app where that step is.
 */
@Composable
private fun LockedScreen(lock: NexusLock, onClose: () -> Unit) {
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars)
            .swipeToDismiss(toRight = false, onDismiss = onClose)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        NeuralOrb(modifier = Modifier.size(112.dp), loading = false)
        Spacer(Modifier.height(24.dp))
        Text(
            "Nexus News",
            style = TextStyle(color = TextC, fontSize = 18.sp, fontWeight = FontWeight.Medium),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            NexusLockNudge.message(context, lock),
            style = TextStyle(color = Text3, fontSize = 15.sp),
        )
        if (lock != NexusLock.NotInstalled) {
            Spacer(Modifier.height(24.dp))
            Box(
                Modifier
                    .clip(RoundedCornerShape(24.dp))
                    .background(Accent)
                    .clickable { NexusLockNudge.openApp(context) }
                    .padding(24.dp, 12.dp),
            ) {
                Text(
                    "Open Nexus",
                    style = TextStyle(color = OnAccent, fontSize = 15.sp, fontWeight = FontWeight.Medium),
                )
            }
        }
    }
}

@Composable
private fun ListScreen(
    items: List<NewsItem>?,
    now: Instant,
    query: String,
    onQuery: (String) -> Unit,
    activeTag: String?,
    onTag: (String?) -> Unit,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onOpen: (NewsItem) -> Unit,
    onClose: () -> Unit,
) {
    val tags = remember(items) { newsTags(items.orEmpty()) }
    // A tag the latest feed no longer carries is no filter at all.
    val tag = activeTag?.takeIf { it in tags }
    val filtered = items.orEmpty().filter { a ->
        (tag == null || a.tags.contains(tag)) &&
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
        // The orb loads for a pull and for the very first look, when there is nothing else to show.
        Header(refreshing || items == null)
        Spacer(Modifier.height(20.dp))
        SearchField(query, onQuery)
        Spacer(Modifier.height(12.dp))
        TagRow(tags, tag, onTag)
        Spacer(Modifier.height(8.dp))
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "eir") { EirCard() }
                if (items != null && filtered.isEmpty()) {
                    item(key = "empty") {
                        Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
                            Text(
                                if (query.isBlank()) {
                                    "No news right now.\nPull down to refresh."
                                } else {
                                    "No news matches \"$query\"."
                                },
                                style = TextStyle(color = Text3, fontSize = 15.sp),
                            )
                        }
                    }
                }
                items(filtered, key = { it.id }) { NewsCard(it, now, query) { onOpen(it) } }
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
                withContext(Dispatchers.IO) { client.ping(status.serverUrl, status.authKey) }
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
private fun TagRow(tags: List<String>, activeTag: String?, onSelect: (String?) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { TagPill("All", activeTag == null) { onSelect(null) } }
        items(tags, key = { it }) { tag ->
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
private fun NewsCard(article: NewsItem, now: Instant, query: String, onClick: () -> Unit) {
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
            Text(article.meta(now), style = TextStyle(color = Text3, fontSize = 11.sp))
        }
    }
}

/** "SOURCE  ·  5m ago"; whichever half the feed did not give is left out. */
private fun NewsItem.meta(now: Instant): String =
    listOf(sourceName.uppercase(), agoText(publishedAt, now)).filter { it.isNotBlank() }.joinToString("  ·  ")

@Composable
private fun DetailsScreen(article: NewsItem, now: Instant, onBack: () -> Unit) {
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
            Text(article.meta(now), style = TextStyle(color = Text3, fontSize = 11.sp))
            Spacer(Modifier.height(16.dp))
            Text(article.body ?: article.synopsis, style = TextStyle(color = Text2, fontSize = 15.sp))
            Spacer(Modifier.height(24.dp))
            if (article.url.isNotBlank()) {
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
