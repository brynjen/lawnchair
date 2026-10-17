package app.lawnchair.nexuslauncher

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
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
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lawnchair.nexus.LocalNexusColors
import app.lawnchair.nexus.NexusColors
import app.lawnchair.nexus.NexusConfig
import app.lawnchair.nexus.NexusLock
import app.lawnchair.nexus.NexusLockNudge
import app.lawnchair.nexus.net.NexusClient
import app.lawnchair.nexus.news.NewsCache
import app.lawnchair.nexus.news.NewsClaim
import app.lawnchair.nexus.news.NewsFeed
import app.lawnchair.nexus.news.NewsFigure
import app.lawnchair.nexus.news.NewsImageCache
import app.lawnchair.nexus.news.NewsItem
import app.lawnchair.nexus.news.NewsKind
import app.lawnchair.nexus.news.NewsSection
import app.lawnchair.nexus.news.authorLine
import app.lawnchair.nexus.news.compactCount
import app.lawnchair.nexus.news.matches
import app.lawnchair.nexus.news.quickQuestions
import app.lawnchair.nexus.news.plus
import app.lawnchair.nexus.news.NewsPictures
import app.lawnchair.nexus.news.RefreshResult
import app.lawnchair.nexus.news.message
import app.lawnchair.nexus.news.NewsRepository
import app.lawnchair.nexus.news.agoText
import app.lawnchair.nexus.news.newsTags
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// The app's colours (nexus_mobile core/tokens.dart), in the person's mode: read where they are drawn.
private val Bg: Color @Composable get() = LocalNexusColors.current.bg
private val Surface: Color @Composable get() = LocalNexusColors.current.surface
private val Surface2: Color @Composable get() = LocalNexusColors.current.surface2
private val BorderC: Color @Composable get() = LocalNexusColors.current.border
private val TextC: Color @Composable get() = LocalNexusColors.current.text
private val Text2: Color @Composable get() = LocalNexusColors.current.text2
private val Text3: Color @Composable get() = LocalNexusColors.current.text3
private val Accent: Color @Composable get() = LocalNexusColors.current.accent
private val Good: Color @Composable get() = LocalNexusColors.current.good
private val OnAccent: Color @Composable get() = LocalNexusColors.current.onAccent

/** The accent for small text: the light palette's pale blue is too faint at that size on white. */
private val AccentInk: Color
    @Composable get() = LocalNexusColors.current.let { if (it.isDark) it.accent else it.accentDeep }

/** What a refresh says under the tag row, and for how long. */
private class RefreshNote(val text: String, val problem: Boolean) // not a data class: each refresh is a new note, even with the same words

private const val NOTE_MS = 4_000L

/** How often the feed is read again while the launcher lives; a nightly sweep makes this plenty. */
private const val NEWS_REFRESH_MS = 15 * 60_000L

/** The header orb's size, and how much height it takes in the layout (the rest overlaps its neighbours). */
private val HEADER_ORB = 240.dp
private val HEADER_ORB_SLOT = 172.dp
private const val HEADER_ORB_GLOW = 0.95f

/**
 * What the Nexus app says right now: the lock, the server and session key the news is under, and
 * whether the person's theme (their choice in the app, or the system's) is dark.
 */
private data class NewsGate(val lock: NexusLock, val serverUrl: String, val authKey: String, val dark: Boolean)

/** Whether the system is in dark mode now, read from the resources so it is current without a recreate. */
private fun systemDark(context: android.content.Context): Boolean =
    context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
        android.content.res.Configuration.UI_MODE_NIGHT_YES

/**
 * The Nexus News "-1" panel: the server's news feed (each card links out to its source), shown from
 * the copy the phone saved of it while the server is asked again. Each sub-view is swipe-left
 * dismissable. [onClose] closes the whole panel. A pull-to-refresh asks the server to sweep its
 * sources and drives the orb's loading animation. [onDark] is told whether the page is dark (it follows
 * the person's theme), for the status bar.
 */
@Composable
fun NexusNewsScreen(
    onClose: () -> Unit,
    onDark: (Boolean) -> Unit = {},
    onDiscuss: (NewsItem) -> Unit = {},
    onAsk: (NewsItem, String) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    // Whether the Nexus app is signed in and set up, and whether it (or, on System, the phone) is
    // dark, re-read while the panel is open: signing in lets the news in, and a change of theme
    // repaints it, without closing it. Until signed in nothing of the news shows.
    val gate by produceState(NewsGate(NexusLock.NeedsSignIn, "", "", dark = true)) {
        while (true) {
            value = withContext(Dispatchers.IO) {
                NexusConfig.read(context).let {
                    NewsGate(it.lock, it.serverUrl, it.authKey, it.themeMode.isDark(systemDark(context)))
                }
            }
            delay(2_000)
        }
    }
    val colors = NexusColors.of(gate.dark)
    // Said to the overlay, which keeps the status bar legible on the page.
    LaunchedEffect(colors.isDark) { onDark(colors.isDark) }

    CompositionLocalProvider(LocalNexusColors provides colors) {
        MaterialTheme(
            colorScheme = if (colors.isDark) {
                darkColorScheme(
                    primary = colors.accent,
                    onPrimary = colors.onAccent,
                    background = colors.bg,
                    surface = colors.surface,
                    onSurface = colors.text,
                )
            } else {
                lightColorScheme(
                    primary = colors.accent,
                    onPrimary = colors.onAccent,
                    background = colors.bg,
                    surface = colors.surface,
                    onSurface = colors.text,
                )
            },
        ) {
            Box(Modifier.fillMaxSize().background(Bg)) {
                if (gate.lock != NexusLock.None) {
                    LockedScreen(gate.lock, onClose)
                } else {
                    // Everything the page holds (the feed, the search, the open article) is one person's
                    // on one server: signing out or in as someone else starts it over.
                    key(gate.serverUrl, gate.authKey) { NewsPanel(gate.serverUrl, gate.authKey, onClose, onDiscuss, onAsk) }
                }
            }
        }
    }
}

@Composable
private fun NewsPanel(
    serverUrl: String,
    authKey: String,
    onClose: () -> Unit,
    onDiscuss: (NewsItem) -> Unit,
    onAsk: (NewsItem, String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var activeTag by remember { mutableStateOf<String?>(null) }
    var section by remember { mutableStateOf(NewsSection.Now) }
    var loadingMore by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<NewsItem?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    // What the last pull-to-refresh came to, said for a few seconds under the tag row.
    var note by remember { mutableStateOf<RefreshNote?>(null) }
    var noteVisible by remember { mutableStateOf(false) }
    // Null only until the saved copy or the first answer; after that the page always has a feed to
    // show, an empty one when there is nothing saved and no network.
    var feed by remember { mutableStateOf<NewsFeed?>(null) }
    // What the server found for the words in the search box (in everything it holds, not only the pages
    // loaded here), and the words it was asked for. Null until it answers, or when it could not.
    var found by remember { mutableStateOf<Pair<String, NewsFeed>?>(null) }
    // Where the list is scrolled to. The list leaves the screen while an article is open; this stays, so
    // back lands where the person was. Another section is another list, and starts at the top.
    val listState = remember(section) { LazyListState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val transport = remember { NexusClient() }
    val news = remember {
        NewsRepository(transport, NewsCache(File(context.cacheDir, "nexus-news")), serverUrl, authKey)
    }
    val pictures = remember {
        NewsPictures(transport, NewsImageCache(File(context.cacheDir, "nexus-news-images")), serverUrl, authKey)
    }
    // For the "5m ago" lines; read again whenever the feed is.
    val now = remember(feed) { Instant.now() }

    // The saved copy first, so the page is never blank while the server answers; then the server,
    // again every NEWS_REFRESH_MS. A failed read leaves what is shown as it is.
    LaunchedEffect(news, section) {
        activeTag = null
        feed = news.saved(section.wire)
        while (true) {
            feed = news.load(section.wire) ?: feed ?: NewsFeed.EMPTY
            delay(NEWS_REFRESH_MS)
        }
    }

    // Another section is another search.
    LaunchedEffect(section) { found = null }

    // New words are a new list: from the top.
    LaunchedEffect(query) { listState.scrollToItem(0) }

    // The server searches once the person has stopped typing; until it answers (or if it cannot) the
    // page filters what it already holds.
    LaunchedEffect(news, section, query) {
        val words = query.trim()
        if (words.isEmpty()) {
            found = null
            return@LaunchedEffect
        }
        delay(SEARCH_DEBOUNCE_MS)
        news.search(section.wire, words)?.let { found = words to it }
    }

    LaunchedEffect(note) {
        if (note != null) {
            delay(NOTE_MS)
            noteVisible = false
        }
    }

    val article = selected
    if (article != null) {
        DetailsScreen(article, now, pictures, onBack = { selected = null }, onDiscuss = onDiscuss, onAsk = onAsk)
    } else {
        // The server's answer to the words as they are now, if it has come.
        val hits = found?.takeIf { (words, _) -> words == query.trim() }?.second
        ListScreen(
            listState = listState,
            items = (hits ?: feed)?.items,
            serverHits = hits != null,
            onDiscuss = onDiscuss,
            now = now,
            pictures = pictures,
            query = query,
            onQuery = { query = it },
            activeTag = activeTag,
            onTag = { activeTag = it },
            refreshing = refreshing,
            note = note,
            noteVisible = noteVisible,
            onRefresh = {
                scope.launch {
                    refreshing = true
                    val result = news.sweep(feed, section.wire)
                    feed = when (result) {
                        is RefreshResult.New -> result.feed
                        is RefreshResult.UpToDate -> result.feed
                        RefreshResult.Unreachable -> feed ?: NewsFeed.EMPTY
                    }
                    refreshing = false
                    note = RefreshNote(result.message(), problem = result is RefreshResult.Unreachable)
                    noteVisible = true
                }
            },
            section = section,
            onSection = { section = it },
            canLoadMore = (hits ?: feed)?.nextCursor != null,
            loadingMore = loadingMore,
            onLoadMore = {
                val searched = found?.takeIf { (words, _) -> words == query.trim() }
                val shown = searched?.second ?: feed
                val cursor = shown?.nextCursor
                if (shown != null && cursor != null && !loadingMore) {
                    scope.launch {
                        loadingMore = true
                        val more = news.more(section.wire, cursor, searched?.first)
                        // A section or a search changed meanwhile: that page is not for what is shown now.
                        if (searched != null) {
                            if (found?.second === shown) found = searched.first to (more?.let { shown.plus(it) } ?: shown)
                        } else if (feed === shown) {
                            feed = more?.let { shown.plus(it) } ?: shown
                        }
                        loadingMore = false
                    }
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
        NeuralOrb(modifier = Modifier.size(112.dp), loading = false, dark = LocalNexusColors.current.isDark)
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
    listState: LazyListState,
    items: List<NewsItem>?,
    /** [items] are the server's answer to [query]: they are not filtered again here. */
    serverHits: Boolean,
    onDiscuss: (NewsItem) -> Unit,
    now: Instant,
    pictures: NewsPictures,
    query: String,
    onQuery: (String) -> Unit,
    activeTag: String?,
    onTag: (String?) -> Unit,
    refreshing: Boolean,
    note: RefreshNote?,
    noteVisible: Boolean,
    onRefresh: () -> Unit,
    section: NewsSection,
    onSection: (NewsSection) -> Unit,
    canLoadMore: Boolean,
    loadingMore: Boolean,
    onLoadMore: () -> Unit,
    onOpen: (NewsItem) -> Unit,
    onClose: () -> Unit,
) {
    val tags = remember(items) { newsTags(items.orEmpty()) }
    // A tag the latest feed no longer carries is no filter at all.
    val tag = activeTag?.takeIf { it in tags }
    val filtered = items.orEmpty().filter { a ->
        (tag == null || a.tags.contains(tag)) && (serverHits || a.matches(query))
    }

    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars)
            .swipeToDismiss(toRight = false, onDismiss = onClose),
    ) {
        // The orb loads for a pull and for the very first look, when there is nothing else to show.
        Header(refreshing || items == null)
        SearchField(query, onQuery)
        Spacer(Modifier.height(12.dp))
        SectionRow(section, onSection)
        Spacer(Modifier.height(10.dp))
        TagRow(tags, tag, onTag)
        Spacer(Modifier.height(8.dp))
        // The note floats over the top of the list, so it never pushes a card down.
        Box(Modifier.fillMaxSize()) {
            PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = onRefresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                LazyColumn(
                    state = listState,
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
                                        when (section) {
                                            NewsSection.Papers -> "No papers yet.\nThe box reads them overnight."
                                            else -> "No news right now.\nPull down to refresh."
                                        }
                                    } else {
                                        "Nothing matches \"$query\"."
                                    },
                                    style = TextStyle(color = Text3, fontSize = 15.sp),
                                )
                            }
                        }
                    }
                    items(filtered, key = { it.id }) { NewsCard(it, now, pictures, query, onDiscuss = { onDiscuss(it) }) { onOpen(it) } }
                    if (canLoadMore) {
                        item(key = "more") {
                            // Composed only when the list reaches its end: that asks for the next page.
                            LaunchedEffect(Unit) { onLoadMore() }
                            Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                                Text(
                                    if (loadingMore) "Loading more…" else "",
                                    style = TextStyle(color = Text3, fontSize = 13.sp),
                                )
                            }
                        }
                    }
                }
            }
            // Fades in and out; the last note is kept while it fades.
            val noteAlpha by animateFloatAsState(
                targetValue = if (noteVisible) 1f else 0f,
                animationSpec = tween(if (noteVisible) 200 else 600),
                label = "refreshNote",
            )
            if (note != null && noteAlpha > 0f) {
                RefreshNotePill(
                    note,
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 8.dp)
                        .graphicsLayer { alpha = noteAlpha },
                )
            }
        }
    }
}

@Composable
private fun RefreshNotePill(note: RefreshNote, modifier: Modifier = Modifier) {
    val colors = LocalNexusColors.current
    val shape = RoundedCornerShape(99.dp)
    Row(
        modifier
            .clip(shape)
            .background(colors.surface3)
            .border(1.dp, colors.borderStrong, shape)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(if (note.problem) colors.warn else colors.good))
        Spacer(Modifier.width(8.dp))
        Text(note.text, style = TextStyle(color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.Medium))
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
        // The orb is drawn at HEADER_ORB but takes only HEADER_ORB_SLOT of height: the swarm sits in
        // the middle of its view and the view's soft margin may overlap the title and the search
        // field. Its ink follows the page's mode, not the system's: they differ when the person
        // chose a theme in the app, and light ink on a light page is a faint speck.
        Box(Modifier.fillMaxWidth().height(HEADER_ORB_SLOT), contentAlignment = Alignment.Center) {
            NeuralOrb(
                modifier = Modifier.requiredSize(HEADER_ORB),
                loading = loading,
                dark = LocalNexusColors.current.isDark,
                glowIntensity = HEADER_ORB_GLOW,
                prominent = true,
            )
        }
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

/** The sections as three tabs: the chosen one in ink with an underline, the others quiet. */
@Composable
private fun SectionRow(section: NewsSection, onSelect: (NewsSection) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
        NewsSection.entries.forEach { entry ->
            val selected = entry == section
            Column(
                Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onSelect(entry) },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    entry.label,
                    style = TextStyle(
                        color = if (selected) TextC else Text3,
                        fontSize = 16.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    ),
                    modifier = Modifier.padding(vertical = 4.dp),
                )
                Box(
                    Modifier
                        .height(2.dp)
                        .width(24.dp)
                        .clip(RoundedCornerShape(99.dp))
                        .background(if (selected) Accent else Color.Transparent),
                )
            }
        }
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

/** "NEWS" or "PAPER": the small label of a card. */
private fun NewsKind.label(): String = when (this) {
    NewsKind.News -> "NEWS"
    NewsKind.Paper -> "PAPER"
}

/** The card's top line: the kind in [kindColor], then "SOURCE  ·  5m ago"; whichever the feed did not give is left out. */
private fun NewsItem.metaLine(now: Instant, kindColor: Color): AnnotatedString = buildAnnotatedString {
    withStyle(SpanStyle(color = kindColor, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp)) {
        append(kind.label())
    }
    // A paper is placed by its arXiv class ("CS.LG"), an article by its source.
    val where = if (kind == NewsKind.Paper && categories.isNotEmpty()) categories.first().uppercase() else sourceName.uppercase()
    // "TRANSLATED" says the words below are an English translation of what the publisher wrote.
    val rest = listOf(where, agoText(publishedAt, now), if (translated) "TRANSLATED" else "").filter { it.isNotBlank() }
    if (rest.isNotEmpty()) append("  ·  " + rest.joinToString("  ·  "))
}

/**
 * A card of the list: the picture across the top when there is one (16:9, edge to edge in the card's
 * rounded shape, its tile there while it loads so nothing moves when it arrives), then the kind,
 * source and age, the title, the summary and the first figures. With no picture, or none to be had, it
 * is a card of text alone: no empty tile.
 */
@Composable
private fun NewsCard(
    article: NewsItem,
    now: Instant,
    pictures: NewsPictures,
    query: String,
    onDiscuss: () -> Unit,
    onClick: () -> Unit,
) {
    val kindColor = AccentInk
    val shape = RoundedCornerShape(22.dp)
    var pictureMissing by remember(article.id) { mutableStateOf(false) }
    // The list is the screen's width less its 16 dp margins; the picture is decoded for that.
    val widthPx = with(LocalDensity.current) { (LocalConfiguration.current.screenWidthDp.dp - 32.dp).roundToPx() }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Surface)
            .border(1.dp, BorderC, shape)
            .clickable(onClick = onClick),
    ) {
        article.imageUrl?.takeIf { !pictureMissing }?.let {
            NewsPicture(
                pictures,
                it,
                widthPx,
                Modifier.fillMaxWidth().background(Surface2),
                aiLabel = article.imageGenerated,
                onMissing = { pictureMissing = true },
            )
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(
                article.metaLine(now, kindColor),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(color = Text3, fontSize = 11.sp),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                highlight(article.title, query, kindColor),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(color = TextC, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
            )
            if (article.kind == NewsKind.Paper && article.authors.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    article.authorLine(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(color = Text3, fontSize = 12.sp),
                )
            }
            if (article.synopsis.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    highlight(article.synopsis, query, kindColor),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(color = Text2, fontSize = 13.sp, lineHeight = 19.sp),
                )
            }
            if (article.kind == NewsKind.Paper) {
                val badges = article.upvotes > 0 || article.codeUrl != null
                if (badges || article.figures.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (article.upvotes > 0) Badge("▲ ${article.upvotes}")
                        if (article.codeUrl != null) {
                            Badge("Code" + (article.codeStars?.let { " ★ ${compactCount(it)}" } ?: ""))
                        }
                        article.figures.take(if (badges) 1 else 2).forEach { FigureChip(it, Modifier.weight(1f, fill = false)) }
                    }
                }
            } else if (article.figures.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    article.figures.take(2).forEach { FigureChip(it, Modifier.weight(1f, fill = false)) }
                }
            }
            if (article.alsoIn.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "Also in " + article.alsoIn.joinToString(", "),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(color = Text3, fontSize = 11.sp),
                )
            }
            if (article.kind == NewsKind.Paper) {
                Spacer(Modifier.height(12.dp))
                Chip("Discuss this paper", onDiscuss)
            }
        }
    }
}

/** A small tappable pill: the Discuss button on a paper card, and a question under the article's. */
@Composable
private fun Chip(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(99.dp))
            .border(1.dp, BorderC, RoundedCornerShape(99.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(label, style = TextStyle(color = TextC, fontSize = 13.sp, fontWeight = FontWeight.Medium))
    }
}

/** A small fact about a paper ("▲ 51", "Code ★ 1.2k"), in the shape of a figure chip. */
@Composable
private fun Badge(text: String) {
    val shape = RoundedCornerShape(99.dp)
    Text(
        text,
        maxLines = 1,
        style = TextStyle(color = AccentInk, fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
        modifier = Modifier
            .clip(shape)
            .background(LocalNexusColors.current.accentSoft)
            .padding(horizontal = 9.dp, vertical = 4.dp),
    )
}

/** A number the article states, compact: "14%  accuracy against the baseline", the label cut to fit. */
@Composable
private fun FigureChip(figure: NewsFigure, modifier: Modifier = Modifier) {
    val valueColor = TextC
    val labelColor = Text2
    val shape = RoundedCornerShape(99.dp)
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = valueColor, fontWeight = FontWeight.SemiBold)) { append(figure.value) }
            if (figure.label.isNotBlank()) {
                withStyle(SpanStyle(color = labelColor)) { append("  " + figure.label) }
            }
        },
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        style = TextStyle(color = labelColor, fontSize = 11.sp),
        modifier = modifier
            .clip(shape)
            .background(Surface2)
            .border(1.dp, BorderC, shape)
            .padding(horizontal = 9.dp, vertical = 4.dp),
    )
}

/**
 * What a tap on a card opens: the article as the feed describes it, laid out to be read. A picture
 * across the top (when there is one), then who and when, the title, the summary, the key points, the
 * numbers it states as tiles, the longer text folded to a few lines, and the way to the original. A
 * section the feed left out is left out here; it scrolls, its text scales, and a wide screen keeps
 * the column to a readable width.
 */
@Composable
private fun DetailsScreen(
    article: NewsItem,
    now: Instant,
    pictures: NewsPictures,
    onBack: () -> Unit,
    onDiscuss: (NewsItem) -> Unit,
    onAsk: (NewsItem, String) -> Unit,
) {
    val context = LocalContext.current
    val paper = article.kind == NewsKind.Paper
    var heroMissing by remember(article.id) { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars)
            .swipeToDismiss(toRight = true, onDismiss = onBack),
    ) {
        Row(Modifier.fillMaxWidth().padding(8.dp, 4.dp, 16.dp, 0.dp), verticalAlignment = Alignment.CenterVertically) {
            BackArrow(onBack)
        }
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(horizontal = 20.dp)) {
                article.imageUrl?.takeIf { !heroMissing }?.let { path ->
                    // The column is at most 640 dp with 20 dp margins: the hero is decoded for that.
                    val widthPx = with(LocalDensity.current) {
                        (minOf(LocalConfiguration.current.screenWidthDp, 640).dp - 40.dp).roundToPx()
                    }
                    NewsPicture(
                        pictures,
                        path,
                        widthPx,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(22.dp))
                            .background(Surface2),
                        aiLabel = article.imageGenerated,
                        onMissing = { heroMissing = true },
                    )
                    Spacer(Modifier.height(18.dp))
                }

                // Who and when.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    KindChip(article.kind)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        article.byline(now),
                        style = TextStyle(color = Text3, fontSize = 12.sp),
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    article.title,
                    style = TextStyle(color = TextC, fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold),
                )

                if (paper && article.authors.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        article.authors.take(8).joinToString(", ") + if (article.authors.size > 8) " et al." else "",
                        style = TextStyle(color = Text2, fontSize = 14.sp, lineHeight = 20.sp),
                    )
                }
                if (paper && (article.categories.isNotEmpty() || article.upvotes > 0 || article.codeUrl != null)) {
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        article.categories.take(3).forEach { Badge(it) }
                        if (article.upvotes > 0) Badge("▲ ${article.upvotes}")
                    }
                }

                if (article.synopsis.isNotBlank()) {
                    Spacer(Modifier.height(18.dp))
                    if (paper) SectionHeading("Summary")
                    Text(article.synopsis, style = TextStyle(color = TextC, fontSize = 17.sp, lineHeight = 26.sp))
                }

                Spacer(Modifier.height(20.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(99.dp))
                        .background(Accent)
                        .clickable { onDiscuss(article) }
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (paper) "Discuss this paper" else "Discuss with Nexus",
                        style = TextStyle(color = OnAccent, fontSize = 15.sp, fontWeight = FontWeight.Medium),
                    )
                }
                // The questions most worth asking, one tap each: they open the same chat, already asked.
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    quickQuestions(article).forEach { q -> Chip(q.label) { onAsk(article, q.question) } }
                }

                if (article.keyPoints.isNotEmpty()) {
                    Spacer(Modifier.height(SECTION_GAP))
                    SectionHeading("Key points")
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        article.keyPoints.forEach { KeyPoint(it) }
                    }
                }

                if (paper && article.claims.isNotEmpty()) {
                    Spacer(Modifier.height(SECTION_GAP))
                    SectionHeading("Claims")
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        article.claims.forEachIndexed { i, claim -> ClaimRow(i + 1, claim) }
                    }
                }

                if (article.figures.isNotEmpty()) {
                    Spacer(Modifier.height(SECTION_GAP))
                    SectionHeading(if (paper) "Results in numbers" else "In numbers")
                    FigureGrid(article.figures)
                }

                article.body?.let {
                    Spacer(Modifier.height(SECTION_GAP))
                    SectionHeading("In more detail")
                    FoldedText(it)
                }

                if (article.url.isNotBlank()) {
                    Spacer(Modifier.height(SECTION_GAP))
                    Box(
                        Modifier
                            .fillMaxWidth()
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
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "Open original  ↗",
                            style = TextStyle(color = OnAccent, fontSize = 15.sp, fontWeight = FontWeight.Medium),
                        )
                    }
                    if (article.codeUrl != null) {
                        Spacer(Modifier.height(10.dp))
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(99.dp))
                                .border(1.dp, BorderC, RoundedCornerShape(99.dp))
                                .clickable {
                                    runCatching {
                                        context.startActivity(
                                            Intent(Intent.ACTION_VIEW, Uri.parse(article.codeUrl))
                                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                        )
                                    }
                                }
                                .padding(horizontal = 20.dp, vertical = 14.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "Code" + (article.codeStars?.let { "  ★ ${compactCount(it)}" } ?: "") + "  ↗",
                                style = TextStyle(color = TextC, fontSize = 15.sp, fontWeight = FontWeight.Medium),
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(article.url, style = TextStyle(color = Text3, fontSize = 12.sp))
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

private val SECTION_GAP = 28.dp

/** How long the search box is left alone before the server is asked. */
private const val SEARCH_DEBOUNCE_MS = 300L

/** "Source  ·  Oct 14, 2026  ·  1h ago"; whichever the feed did not give is left out. */
private fun NewsItem.byline(now: Instant): String = listOf(
    sourceName,
    publishedAt?.let { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withZone(ZoneId.systemDefault()).format(it) }.orEmpty(),
    agoText(publishedAt, now),
    if (translated) "Translated from ${languageLabel(lang)}" else "",
).filter { it.isNotBlank() }.joinToString("  ·  ")

/** The name of the language an article was written in, for "Translated from …". */
private fun languageLabel(lang: String?): String = when (lang) {
    "de" -> "German"
    "fr" -> "French"
    "es" -> "Spanish"
    "it" -> "Italian"
    "nl" -> "Dutch"
    "pt" -> "Portuguese"
    "sv" -> "Swedish"
    "zh" -> "Chinese"
    "ja" -> "Japanese"
    "ko" -> "Korean"
    "ru" -> "Russian"
    "ar" -> "Arabic"
    else -> "another language"
}

@Composable
private fun KindChip(kind: NewsKind) {
    val ink = AccentInk
    Text(
        kind.label(),
        style = TextStyle(color = ink, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp),
        modifier = Modifier
            .clip(RoundedCornerShape(99.dp))
            .background(LocalNexusColors.current.accentSoft)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
private fun SectionHeading(text: String) {
    Text(
        text,
        style = TextStyle(color = TextC, fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
        modifier = Modifier.padding(bottom = 10.dp),
    )
}

@Composable
private fun KeyPoint(text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 9.dp).size(6.dp).clip(CircleShape).background(AccentInk))
        Spacer(Modifier.width(12.dp))
        Text(text, style = TextStyle(color = TextC, fontSize = 15.sp, lineHeight = 22.sp), modifier = Modifier.weight(1f))
    }
}

/** One claim of a paper, numbered, with what the paper offers for it under it. */
@Composable
private fun ClaimRow(number: Int, claim: NewsClaim) {
    Row(verticalAlignment = Alignment.Top) {
        Text(
            "$number",
            style = TextStyle(color = AccentInk, fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
            modifier = Modifier.width(24.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(claim.claim, style = TextStyle(color = TextC, fontSize = 15.sp, lineHeight = 22.sp))
            if (claim.evidence.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(claim.evidence, style = TextStyle(color = Text3, fontSize = 13.sp, lineHeight = 19.sp))
            }
        }
    }
}

/** The numbers as tiles, two to a row: the number large, what it measures small under it. */
@Composable
private fun FigureGrid(figures: List<NewsFigure>) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        figures.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { FigureTile(it, Modifier.weight(1f).fillMaxHeight()) }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun FigureTile(figure: NewsFigure, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier
            .clip(shape)
            .background(Surface)
            .border(1.dp, BorderC, shape)
            .padding(14.dp),
    ) {
        Text(
            figure.value,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(color = AccentInk, fontSize = 24.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
        )
        if (figure.label.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(figure.label, style = TextStyle(color = Text2, fontSize = 12.sp, lineHeight = 16.sp))
        }
    }
}

/** Long text folded to [FOLDED_LINES] lines with "Read more"; a short one is shown whole, with no button. */
@Composable
private fun FoldedText(text: String) {
    var expanded by remember { mutableStateOf(false) }
    var overflows by remember { mutableStateOf(false) }
    Text(
        text,
        maxLines = if (expanded) Int.MAX_VALUE else FOLDED_LINES,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { if (!expanded) overflows = it.hasVisualOverflow },
        style = TextStyle(color = Text2, fontSize = 15.sp, lineHeight = 23.sp),
    )
    if (overflows || expanded) {
        Text(
            if (expanded) "Show less" else "Read more",
            style = TextStyle(color = AccentInk, fontSize = 14.sp, fontWeight = FontWeight.Medium),
            modifier = Modifier
                .padding(top = 8.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable { expanded = !expanded }
                .padding(vertical = 4.dp),
        )
    }
}

private const val FOLDED_LINES = 6

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
    val ink = TextC
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
            drawLine(ink, Offset(right, mid), Offset(left, mid), sw, StrokeCap.Round)
            drawLine(ink, Offset(left + head, mid - head), Offset(left, mid), sw, StrokeCap.Round)
            drawLine(ink, Offset(left + head, mid + head), Offset(left, mid), sw, StrokeCap.Round)
        }
    }
}

// buildAnnotatedString-style highlight of case-insensitive [query] occurrences.
private fun highlight(text: String, query: String, color: Color): AnnotatedString = buildAnnotatedString {
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
        withStyle(SpanStyle(color = color, fontWeight = FontWeight.Bold)) {
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
    onTextLayout: (TextLayoutResult) -> Unit = {},
) = BasicText(
    text = text,
    modifier = modifier,
    style = style,
    maxLines = maxLines,
    overflow = overflow,
    onTextLayout = onTextLayout,
)

@Composable
private fun Text(
    text: AnnotatedString,
    style: TextStyle,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    onTextLayout: (TextLayoutResult) -> Unit = {},
) = BasicText(
    text = text,
    modifier = modifier,
    style = style,
    maxLines = maxLines,
    overflow = overflow,
    onTextLayout = onTextLayout,
)
