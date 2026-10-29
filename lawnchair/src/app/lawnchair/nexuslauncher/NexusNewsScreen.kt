package app.lawnchair.nexuslauncher

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.layout.offset
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
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lawnchair.nexus.LocalNexusColors
import app.lawnchair.nexus.NexusColors
import app.lawnchair.nexus.NexusConfig
import app.lawnchair.nexus.NexusLock
import app.lawnchair.nexus.NexusLockNudge
import app.lawnchair.nexus.box.BoxOrbState
import app.lawnchair.nexus.box.boxOrbStates
import app.lawnchair.nexus.net.NexusClient
import app.lawnchair.nexus.net.networkUp
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
import app.lawnchair.nexus.news.NewsRepository
import app.lawnchair.nexus.news.agoText
import app.lawnchair.nexus.news.isIn
import app.lawnchair.nexus.news.updated
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
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
 * dismissable. [onClose] closes the whole panel. The server reads its sources overnight, so there is
 * no pull-to-refresh: the page reads what is filed. [onDark] is told whether the page is dark (it follows
 * the person's theme), for the status bar.
 *
 * The panel is composed for as long as the launcher lives, parked off screen. [open] says whether any of
 * it is on screen: only then does it read the app's config, ask the server for the feed or watch the
 * network. Closed, it does nothing, and the battery is not spent on a page nobody is looking at.
 */
@Composable
fun NexusNewsScreen(
    open: Boolean,
    onClose: () -> Unit,
    onDark: (Boolean) -> Unit = {},
    onDiscuss: (NewsItem) -> Unit = {},
    onAsk: (NewsItem, String) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    // Whether the Nexus app is signed in and set up, and whether it (or, on System, the phone) is
    // dark, re-read while the panel is open: signing in lets the news in, and a change of theme
    // repaints it, without closing it. Until signed in nothing of the news shows.
    val gate by produceState(NewsGate(NexusLock.NeedsSignIn, "", "", dark = true), open) {
        if (!open) return@produceState
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
                    key(gate.serverUrl, gate.authKey) { NewsPanel(open, gate.serverUrl, gate.authKey, onClose, onDiscuss, onAsk) }
                }
            }
        }
    }
}

@Composable
private fun NewsPanel(
    open: Boolean,
    serverUrl: String,
    authKey: String,
    onClose: () -> Unit,
    onDiscuss: (NewsItem) -> Unit,
    onAsk: (NewsItem, String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var section by remember { mutableStateOf(NewsSection.Now) }
    var loadingMore by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<NewsItem?>(null) }
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
    val online = rememberOnline(open)
    val transport = remember { NexusClient() }
    // What the whole box is doing, for the orb above the feed: the substrate's lanes, one stream. Open
    // only while the panel is, like the feed; closed, no socket is held. Passed down as State, not a
    // value, so a lane changing recomposes the orb and not the feed.
    val box = produceState(BoxOrbState.UNKNOWN, open, serverUrl, authKey) {
        if (!open) {
            value = BoxOrbState.UNKNOWN
            return@produceState
        }
        boxOrbStates { transport.watchBox(serverUrl, authKey) }.collect { value = it }
    }
    val news = remember {
        NewsRepository(transport, NewsCache(File(context.cacheDir, "nexus-news")), serverUrl, authKey)
    }
    val pictures = remember {
        NewsPictures(transport, NewsImageCache(File(context.cacheDir, "nexus-news-images")), serverUrl, authKey)
    }
    // For the "5m ago" lines; read again whenever the feed is.
    val now = remember(feed) { Instant.now() }

    // The saved copy first, so the page is never blank while the server answers; then the server,
    // again every NEWS_REFRESH_MS. A failed read leaves what is shown as it is. Only while the panel is
    // open: it reads the server each time it opens, and never while it is out of sight.
    LaunchedEffect(news, section, open) {
        if (!open) return@LaunchedEffect
        feed = news.saved(section.wire)
        while (true) {
            feed = news.load(section.wire) ?: feed ?: NewsFeed.EMPTY
            delay(NEWS_REFRESH_MS)
        }
    }

    // Another filter is another search. Closing and opening the panel is not a change of filter: the
    // chosen one (like the words in the search box) is there when it opens again.
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

    // A star or a Read mark shows at once and the server is told; if it could not be reached the mark
    // goes back to how it was, so what is shown is never a mark that was not kept.
    fun show(id: Long, change: (NewsItem) -> NewsItem) {
        feed = feed?.updated(id, change)
        found = found?.let { (words, hits) -> words to hits.updated(id, change) }
        selected = selected?.takeIf { it.id == id }?.let(change) ?: selected
    }
    val onFavorite: (NewsItem) -> Unit = { item ->
        val wanted = !item.favorite
        show(item.id) { it.copy(favorite = wanted) }
        scope.launch {
            val now = news.setFavorite(item, wanted) ?: !wanted
            show(item.id) { it.copy(favorite = now) }
        }
    }
    val onRead: (NewsItem) -> Unit = { item ->
        val wanted = !item.read
        show(item.id) { it.copy(read = wanted) }
        scope.launch {
            val now = news.setRead(item, wanted) ?: !wanted
            show(item.id) { it.copy(read = now) }
        }
    }

    val article = selected
    if (article != null) {
        DetailsScreen(article, now, pictures, onBack = { selected = null }, onDiscuss = onDiscuss, onAsk = onAsk, onFavorite = onFavorite, onRead = onRead)
    } else {
        // The server's answer to the words as they are now, if it has come.
        val hits = found?.takeIf { (words, _) -> words == query.trim() }?.second
        ListScreen(
            online = online,
            box = box,
            listState = listState,
            items = (hits ?: feed)?.items,
            serverHits = hits != null,
            onDiscuss = onDiscuss,
            onFavorite = onFavorite,
            now = now,
            pictures = pictures,
            query = query,
            onQuery = { query = it },
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
    online: Boolean,
    box: State<BoxOrbState>,
    listState: LazyListState,
    items: List<NewsItem>?,
    /** [items] are the server's answer to [query]: they are not filtered again here. */
    serverHits: Boolean,
    onDiscuss: (NewsItem) -> Unit,
    onFavorite: (NewsItem) -> Unit,
    now: Instant,
    pictures: NewsPictures,
    query: String,
    onQuery: (String) -> Unit,
    section: NewsSection,
    onSection: (NewsSection) -> Unit,
    canLoadMore: Boolean,
    loadingMore: Boolean,
    onLoadMore: () -> Unit,
    onOpen: (NewsItem) -> Unit,
    onClose: () -> Unit,
) {
    // Each filter shows its own cards: a star or a Read mark just made moves a card at once.
    val filtered = items.orEmpty().filter { a -> a.isIn(section) && (serverHits || a.matches(query)) }

    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars)
            .swipeToDismiss(toRight = false, onDismiss = onClose),
    ) {
        // The orb loads for the very first look, when there is nothing else to show.
        Header(items == null, online, box)
        SearchField(query, onQuery)
        Spacer(Modifier.height(12.dp))
        FilterRow(section, onSection)
        Spacer(Modifier.height(8.dp))
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (items != null && filtered.isEmpty()) {
                item(key = "empty") {
                    Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
                        Text(
                            if (query.isBlank()) {
                                when (section) {
                                    NewsSection.Papers -> "No research yet.\nThe box reads papers overnight."
                                    NewsSection.Norway -> "No Norwegian news right now.\nThe box reads the news overnight."
                                    NewsSection.Saved -> "No favorites yet.\nTap the star on an article or paper to keep it here."
                                    NewsSection.Read -> "Nothing read yet.\nMark an article read and it moves here."
                                    else -> "No news right now.\nThe box reads the news overnight."
                                }
                            } else {
                                "Nothing matches \"$query\"."
                            },
                            style = TextStyle(color = Text3, fontSize = 15.sp),
                        )
                    }
                }
            }
            items(filtered, key = { it.id }) {
                NewsCard(it, now, pictures, query, onDiscuss = { onDiscuss(it) }, onFavorite = { onFavorite(it) }) { onOpen(it) }
            }
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
}

/**
 * Whether the phone has a network, for as long as the panel is [active]. The system tells of each
 * change, so nothing is polled, and nothing is registered while the panel is closed; it is read again
 * on opening.
 */
@Composable
private fun rememberOnline(active: Boolean): Boolean {
    val context = LocalContext.current
    val online by produceState(initialValue = true, active) {
        if (!active) return@produceState
        networkUp(context).collect { value = it }
    }
    return online
}

@Composable
private fun Header(loading: Boolean, online: Boolean, box: State<BoxOrbState>) {
    // The connection dot: teal while the phone has a network, red when it has none. The panel only
    // shows for a signed-in person, so that is the whole state until the server can say, in a
    // notification, that it is down (it will turn the dot red then); the dot does not ask the server.
    val dotColor = if (online) Good else Color(0xFFFF6B6B)
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
            HeaderOrb(loading, box)
        }
    }
}

/**
 * The orb above the feed: the whole box, every lane the substrate reports in its own colour, so
 * work this phone did not start (an eval of five trials, a sub-agent) shows here. While the box is at
 * rest, or before the first packet, it draws the page's own state alone: loading for the very first
 * look, idle after. The box is read here, not in [Header], so its packets recompose only the orb.
 */
@Composable
private fun HeaderOrb(loading: Boolean, box: State<BoxOrbState>) {
    val lanes = box.value
    NeuralOrb(
        modifier = Modifier.requiredSize(HEADER_ORB),
        state = if (loading) OrbState.Loading else OrbState.Idle,
        dark = LocalNexusColors.current.isDark,
        glowIntensity = HEADER_ORB_GLOW,
        prominent = true,
        box = lanes.takeIf { it.isKnown && it.slots.isNotEmpty() },
    )
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

/** The filters as one row of pills, the chosen one filled; it scrolls sideways on a narrow screen. */
@Composable
private fun FilterRow(section: NewsSection, onSelect: (NewsSection) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(NewsSection.entries, key = { it.wire }) { entry ->
            FilterPill(entry.label, entry == section) { onSelect(entry) }
        }
    }
}

@Composable
private fun FilterPill(label: String, selected: Boolean, onClick: () -> Unit) {
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
    onFavorite: () -> Unit,
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    article.metaLine(now, kindColor),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(color = Text3, fontSize = 11.sp),
                    modifier = Modifier.weight(1f),
                )
                // Pushed out into the card's margin so the star's edge lines up with the text above.
                StarButton(article.favorite, onFavorite, Modifier.offset(x = 10.dp))
            }
            Spacer(Modifier.height(2.dp))
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

/** A five-pointed star, filled when the article is saved and an outline when it is not. */
@Composable
private fun StarIcon(filled: Boolean, iconSize: Dp, color: Color) {
    Canvas(Modifier.size(iconSize)) {
        val outer = size.minDimension / 2f
        val inner = outer * 0.42f
        val centre = Offset(size.width / 2f, size.height / 2f + outer * 0.06f)
        val path = Path()
        for (i in 0 until 10) {
            val radius = if (i % 2 == 0) outer else inner
            val angle = -PI / 2 + i * PI / 5
            val point = Offset(centre.x + (radius * cos(angle)).toFloat(), centre.y + (radius * sin(angle)).toFloat())
            if (i == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
        }
        path.close()
        if (filled) {
            drawPath(path, color)
            drawPath(path, color, style = Stroke(width = 1.5.dp.toPx(), join = StrokeJoin.Round))
        } else {
            drawPath(path, color, style = Stroke(width = 1.6.dp.toPx(), join = StrokeJoin.Round))
        }
    }
}

/** The star on a card: saves the article to read again, or takes it out of the saved ones. */
@Composable
private fun StarButton(saved: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(width = 44.dp, height = 30.dp)
            .clip(RoundedCornerShape(99.dp))
            .clickable(onClickLabel = if (saved) "Remove from saved" else "Save for later", role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        StarIcon(saved, 20.dp, if (saved) AccentInk else Text3)
    }
}

/** The article view's star with its words: "Save" for one that is not kept, "Saved" for one that is. */
@Composable
private fun SaveButton(saved: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(99.dp))
            .clickable(onClickLabel = if (saved) "Remove from saved" else "Save for later", role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StarIcon(saved, 20.dp, if (saved) AccentInk else Text2)
        Spacer(Modifier.width(8.dp))
        Text(
            if (saved) "Saved" else "Save",
            style = TextStyle(color = if (saved) AccentInk else Text2, fontSize = 14.sp, fontWeight = FontWeight.Medium),
        )
    }
}

/** A circle with a check in it, filled when the article is read. */
@Composable
private fun CheckIcon(done: Boolean, iconSize: Dp, color: Color, tick: Color) {
    Canvas(Modifier.size(iconSize)) {
        val centre = Offset(size.width / 2f, size.height / 2f)
        val radius = size.minDimension / 2f - 1.dp.toPx()
        if (done) {
            drawCircle(color, radius, centre)
        } else {
            drawCircle(color, radius, centre, style = Stroke(width = 1.6.dp.toPx()))
        }
        val path = Path().apply {
            moveTo(size.width * 0.30f, size.height * 0.52f)
            lineTo(size.width * 0.44f, size.height * 0.66f)
            lineTo(size.width * 0.70f, size.height * 0.36f)
        }
        drawPath(path, if (done) tick else color, style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** The article view's Read mark: "Mark read" hides the article from the lists, "Read" takes it back. */
@Composable
private fun ReadButton(read: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(99.dp))
            .clickable(onClickLabel = if (read) "Mark as unread" else "Mark as read", role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CheckIcon(read, 20.dp, if (read) AccentInk else Text2, OnAccent)
        Spacer(Modifier.width(8.dp))
        Text(
            if (read) "Read" else "Mark read",
            style = TextStyle(color = if (read) AccentInk else Text2, fontSize = 14.sp, fontWeight = FontWeight.Medium),
        )
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
    onFavorite: (NewsItem) -> Unit,
    onRead: (NewsItem) -> Unit,
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
            Spacer(Modifier.weight(1f))
            ReadButton(article.read) { onRead(article) }
            SaveButton(article.favorite) { onFavorite(article) }
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
