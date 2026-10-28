package app.lawnchair.nexus

import app.lawnchair.nexus.news.NewsCache
import app.lawnchair.nexus.news.NewsFeed
import app.lawnchair.nexus.news.NewsItem
import app.lawnchair.nexus.news.NewsRepository
import app.lawnchair.nexus.news.RefreshResult
import app.lawnchair.nexus.news.message
import app.lawnchair.nexus.news.refreshResult
import app.lawnchair.nexus.net.NexusTransport
import app.lawnchair.nexus.net.NexusUnauthorizedException
import java.io.File
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The News page's safety net: the saved copy is keyed to one server and one person, it is the page's
 * first picture, and a failed refresh leaves it where it is.
 */
class NewsRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val url = "https://nexus.example"
    private val key = "session-key-1"

    private fun feed(vararg ids: Long) = NewsFeed(
        items = ids.map {
            NewsItem(
                id = it,
                sourceName = "Source",
                url = "https://example.com/$it",
                title = "Title $it",
                synopsis = "Synopsis",
                body = null,
                publishedAt = Instant.parse("2026-10-14T06:00:00Z"),
                tags = listOf("ai"),
            )
        },
        refreshedAt = Instant.parse("2026-10-14T07:00:00Z"),
    )

    // region the cache

    @Test
    fun `the same server and key name the same file, anything else another`() {
        val cache = NewsCache(tmp.root)
        assertEquals(cache.fileFor(url, key), cache.fileFor(url, key))
        assertNotEquals(cache.fileFor(url, key), cache.fileFor(url, "session-key-2"))
        assertNotEquals(cache.fileFor(url, key), cache.fileFor("https://other.example", key))
        // The two halves cannot trade characters across their boundary.
        assertNotEquals(cache.fileFor("ab", "c"), cache.fileFor("a", "bc"))
    }

    @Test
    fun `the file name is a digest, naming neither the server nor the key`() {
        val name = NewsCache(tmp.root).fileFor(url, key).name
        assertTrue(name, Regex("news-[0-9a-f]{64}\\.json").matches(name))
        assertFalse(name.contains("nexus"))
        assertFalse(name.contains("session"))
    }

    @Test
    fun `a saved feed reads back the same, and only for its own server and person`() {
        val cache = NewsCache(tmp.root)
        assertNull(cache.read(url, key))
        cache.write(url, key, feed(1, 2))
        assertEquals(feed(1, 2), cache.read(url, key))
        assertNull(cache.read(url, "someone-else"))
        assertNull(cache.read("https://other.example", key))
    }

    @Test
    fun `writing for a new session leaves no file of the old one behind`() {
        val cache = NewsCache(tmp.root)
        cache.write(url, "old-key", feed(1))
        cache.write(url, "new-key", feed(2))
        assertEquals(listOf(cache.fileFor(url, "new-key").name), tmp.root.list()!!.toList())
        assertNull(cache.read(url, "old-key"))
    }

    @Test
    fun `a cache that is damaged or in a directory that is not there is a miss`() {
        val cache = NewsCache(File(tmp.root, "nexus-news"))
        assertNull(cache.read(url, key))
        cache.write(url, key, feed(1)) // creates the directory
        cache.fileFor(url, key).writeText("{\"cards\": [")
        assertNull(cache.read(url, key))
    }

    // endregion

    @Test
    fun `each section has its own file, and the files of another person go`() {
        val cache = NewsCache(tmp.root)
        assertNotEquals(cache.fileFor(url, key, "ai"), cache.fileFor(url, key, "papers"))
        assertNotEquals(cache.fileFor(url, key), cache.fileFor(url, key, "ai"))
        assertEquals(cache.fileFor(url, key), cache.fileFor(url, key, null))

        cache.write(url, "old-key", feed(1), "now")
        cache.write(url, key, feed(2), "now")
        cache.write(url, key, feed(3), "ai")
        assertEquals(feed(2), cache.read(url, key, "now"))
        assertEquals(feed(3), cache.read(url, key, "ai"))
        assertNull(cache.read(url, key, "papers"))
        assertNull(cache.read(url, "old-key", "now"))
        assertEquals(2, tmp.root.list()!!.size)
    }

        // region the repository

    /** A transport that answers the two news calls from [onFeed] and [onRefresh]; the rest is not used here. */
    private class News(
        val onFeed: () -> NewsFeed,
        val onRefresh: () -> NewsFeed = { throw IOException("no sweep") },
    ) : StubNexusTransport() {
        var feedCalls = 0
        var refreshCalls = 0

        /** The (section, cursor) of each feed call. */
        val asked = mutableListOf<Pair<String?, String?>>()

        /** The query of each feed call (null for a plain one). */
        val queries = mutableListOf<String?>()

        override suspend fun newsFeed(base: String, token: String, limit: Int, cursor: String?, section: String?, query: String?): NewsFeed {
            feedCalls++
            asked += section to cursor
            queries += query
            return onFeed()
        }

        override suspend fun newsRefresh(base: String, token: String): NewsFeed {
            refreshCalls++
            return onRefresh()
        }

        /** The (id, favorite) of each star call, and what the server answers. */
        val stars = mutableListOf<Pair<Long, Boolean>>()
        var onFavorite: (Boolean) -> Boolean = { it }

        override suspend fun newsFavorite(base: String, token: String, id: Long, favorite: Boolean): Boolean {
            stars += id to favorite
            return onFavorite(favorite)
        }

        /** The same for the Read mark. */
        val reads = mutableListOf<Pair<Long, Boolean>>()
        var onRead: (Boolean) -> Boolean = { it }

        override suspend fun newsRead(base: String, token: String, id: Long, read: Boolean): Boolean {
            reads += id to read
            return onRead(read)
        }
    }

    private fun repository(transport: NexusTransport, authKey: String = key) =
        NewsRepository(transport, NewsCache(tmp.root), url, authKey)

    @Test
    fun `the saved copy comes first, before any call to the server`() = runBlocking {
        NewsCache(tmp.root).write(url, key, feed(1, 2))
        val transport = News(onFeed = { error("not asked yet") })
        assertEquals(feed(1, 2), repository(transport).saved())
        assertEquals(0, transport.feedCalls)
    }

    @Test
    fun `nothing saved is null`() = runBlocking {
        assertNull(repository(News(onFeed = { feed(1) })).saved())
    }

    @Test
    fun `a load returns the server's feed and saves it`() = runBlocking {
        val repo = repository(News(onFeed = { feed(3, 4) }))
        assertEquals(feed(3, 4), repo.load())
        assertEquals(feed(3, 4), repo.saved())
    }

    @Test
    fun `a failed load is null and keeps the saved copy`() = runBlocking {
        NewsCache(tmp.root).write(url, key, feed(1, 2))
        for (failure in listOf<() -> NewsFeed>(
            { throw IOException("no network") },
            { throw NexusUnauthorizedException() },
            { throw IllegalStateException("unreadable") },
        )) {
            val repo = repository(News(onFeed = failure))
            assertNull(repo.load())
            assertEquals(feed(1, 2), repo.saved())
        }
    }

    @Test
    fun `another person's session never reads the saved copy`() = runBlocking {
        NewsCache(tmp.root).write(url, key, feed(1, 2))
        assertNull(repository(News(onFeed = { feed(9) }), authKey = "someone-else").saved())
    }

    @Test
    fun `a sweep asks the server to refresh, then loads the feed`() = runBlocking {
        val transport = News(onFeed = { feed(5, 6) }, onRefresh = { feed(5) })
        val repo = repository(transport)
        assertEquals(RefreshResult.New(feed(5, 6), 2), repo.sweep(shown = null))
        assertEquals(1, transport.refreshCalls)
        assertEquals(1, transport.feedCalls)
        assertEquals(feed(5, 6), repo.saved())
    }

    @Test
    fun `a sweep that brings nothing the page lacks is up to date`() = runBlocking {
        val repo = repository(News(onFeed = { feed(5, 6) }, onRefresh = { feed(5, 6) }))
        assertEquals(RefreshResult.UpToDate(feed(5, 6)), repo.sweep(shown = feed(5, 6)))
        assertEquals(RefreshResult.New(feed(5, 6), 1), repo.sweep(shown = feed(5)))
    }

    @Test
    fun `a sweep that fails still loads the feed`() = runBlocking {
        val transport = News(onFeed = { feed(7) }) // onRefresh throws
        assertEquals(RefreshResult.New(feed(7), 1), repository(transport).sweep(shown = feed(1)))
        assertEquals(1, transport.feedCalls)
    }

    @Test
    fun `a sweep whose load fails uses the sweep's own answer`() = runBlocking {
        val transport = News(onFeed = { throw IOException("dropped") }, onRefresh = { feed(8) })
        val repo = repository(transport)
        assertEquals(RefreshResult.New(feed(8), 1), repo.sweep(shown = null))
        assertEquals(feed(8), repo.saved())
    }

    @Test
    fun `a sweep that fails altogether is unreachable and keeps the saved copy`() = runBlocking {
        NewsCache(tmp.root).write(url, key, feed(1, 2))
        val repo = repository(News(onFeed = { throw IOException("offline") }))
        assertEquals(RefreshResult.Unreachable, repo.sweep(shown = feed(1, 2)))
        assertEquals(feed(1, 2), repo.saved())
    }

    // region the stars

    private fun starred(vararg ids: Long) = feed(*ids).let { f -> f.copy(items = f.items.map { it.copy(favorite = true) }) }

    @Test
    fun `a star is asked of the server and the saved copies follow it`() = runBlocking {
        val cache = NewsCache(tmp.root)
        cache.write(url, key, feed(1, 2), "now")
        cache.write(url, key, starred(7), "saved")
        val transport = News(onFeed = { error("not asked") })
        val repo = repository(transport)

        assertEquals(true, repo.setFavorite(feed(1).items.single(), true))
        assertEquals(listOf(1L to true), transport.stars)
        // The card is starred where the person saw it, and the saved copy has it, newest first.
        assertEquals(listOf(true, false), cache.read(url, key, "now")!!.items.map { it.favorite })
        assertEquals(listOf(1L, 7L), cache.read(url, key, "saved")!!.items.map { it.id }.sorted())
        assertTrue(cache.read(url, key, "saved")!!.items.all { it.favorite })

        assertEquals(false, repo.setFavorite(feed(1).items.single(), false))
        assertEquals(listOf(false, false), cache.read(url, key, "now")!!.items.map { it.favorite })
        assertEquals(listOf(7L), cache.read(url, key, "saved")!!.items.map { it.id })
    }

    @Test
    fun `a star the server could not take leaves everything as it was`() = runBlocking {
        val cache = NewsCache(tmp.root)
        cache.write(url, key, feed(1), "now")
        cache.write(url, key, starred(7), "saved")
        for (failure in listOf<(Boolean) -> Boolean>(
            { throw IOException("offline") },
            { throw NexusUnauthorizedException() },
        )) {
            val transport = News(onFeed = { error("not asked") }).also { it.onFavorite = failure }
            assertNull(repository(transport).setFavorite(feed(1).items.single(), true))
        }
        assertEquals(listOf(false), cache.read(url, key, "now")!!.items.map { it.favorite })
        assertEquals(listOf(7L), cache.read(url, key, "saved")!!.items.map { it.id })
    }

    @Test
    fun `the server's answer is what the saved copies keep, and no saved copy is made up`() = runBlocking {
        val cache = NewsCache(tmp.root)
        cache.write(url, key, feed(1), "now")
        // The article is gone on the server: asked to star it, the answer is no.
        val transport = News(onFeed = { error("not asked") }).also { it.onFavorite = { false } }

        assertEquals(false, repository(transport).setFavorite(feed(1).items.single(), true))
        assertEquals(listOf(false), cache.read(url, key, "now")!!.items.map { it.favorite })
        assertNull(cache.read(url, key, "saved"))
    }

    @Test
    fun `a read mark is asked of the server and the saved copies follow it, the read copy gaining the card`() = runBlocking {
        val cache = NewsCache(tmp.root)
        cache.write(url, key, feed(1, 2), "now")
        cache.write(url, key, starred(1), "saved")
        cache.write(url, key, feed(7), "read")
        val transport = News(onFeed = { error("not asked") })
        val repo = repository(transport)

        assertEquals(true, repo.setRead(feed(1).items.single(), true))
        assertEquals(listOf(1L to true), transport.reads)
        assertEquals(listOf(true, false), cache.read(url, key, "now")!!.items.map { it.read })
        assertEquals(listOf(true), cache.read(url, key, "saved")!!.items.map { it.read })
        assertEquals(listOf(1L, 7L), cache.read(url, key, "read")!!.items.map { it.id }.sorted())

        assertEquals(false, repo.setRead(feed(1).items.single(), false))
        assertEquals(listOf(false, false), cache.read(url, key, "now")!!.items.map { it.read })
        assertEquals(listOf(7L), cache.read(url, key, "read")!!.items.map { it.id })
    }

    @Test
    fun `a read mark the server could not take leaves everything as it was`() = runBlocking {
        val cache = NewsCache(tmp.root)
        cache.write(url, key, feed(1), "now")
        cache.write(url, key, feed(7), "read")
        val transport = News(onFeed = { error("not asked") }).also { it.onRead = { throw IOException("offline") } }

        assertNull(repository(transport).setRead(feed(1).items.single(), true))
        assertEquals(listOf(false), cache.read(url, key, "now")!!.items.map { it.read })
        assertEquals(listOf(7L), cache.read(url, key, "read")!!.items.map { it.id })
    }

    // endregion

    // region what a refresh says

    @Test
    fun `new cards are told by id, wherever they sit`() {
        assertEquals(RefreshResult.New(feed(3, 1, 2), 1), refreshResult(shown = feed(1, 2), fresh = feed(3, 1, 2)))
        assertEquals(RefreshResult.New(feed(1, 2), 2), refreshResult(shown = null, fresh = feed(1, 2)))
        assertEquals(RefreshResult.New(feed(1, 2), 2), refreshResult(shown = NewsFeed.EMPTY, fresh = feed(1, 2)))
    }

    @Test
    fun `nothing new is up to date, even when cards left the feed`() {
        assertEquals(RefreshResult.UpToDate(feed(2, 1)), refreshResult(shown = feed(1, 2), fresh = feed(2, 1)))
        assertEquals(RefreshResult.UpToDate(feed(2)), refreshResult(shown = feed(1, 2), fresh = feed(2)))
        assertEquals(RefreshResult.UpToDate(NewsFeed.EMPTY), refreshResult(shown = null, fresh = NewsFeed.EMPTY))
    }

    @Test
    fun `no feed at all is unreachable`() {
        assertEquals(RefreshResult.Unreachable, refreshResult(shown = feed(1), fresh = null))
        assertEquals(RefreshResult.Unreachable, refreshResult(shown = null, fresh = null))
    }

    @Test
    fun `the page's words for each`() {
        assertEquals("1 new article", RefreshResult.New(feed(1), 1).message())
        assertEquals("2 new articles", RefreshResult.New(feed(1, 2), 2).message())
        assertEquals("Up to date", RefreshResult.UpToDate(feed(1)).message())
        assertEquals("Couldn't reach Nexus. Showing saved articles.", RefreshResult.Unreachable.message())
    }

    // endregion

    // endregion

    @Test
    fun `a section is asked for and saved by itself`() = runBlocking {
        val transport = News(onFeed = { feed(5, 6) })
        val repo = repository(transport)

        assertEquals(feed(5, 6), repo.load("papers"))
        assertEquals(listOf<Pair<String?, String?>>("papers" to null), transport.asked)
        assertEquals(feed(5, 6), repo.saved("papers"))
        assertNull(repo.saved("ai"))
        assertNull(repo.saved())
    }

    @Test
    fun `the next page is asked for by its cursor and is not saved over the first`() = runBlocking {
        val transport = News(onFeed = { feed(7) })
        NewsCache(tmp.root).write(url, key, feed(1, 2), "ai")
        val repo = repository(transport)

        assertEquals(feed(7), repo.more("ai", "cursor-1"))
        assertEquals(listOf<Pair<String?, String?>>("ai" to "cursor-1"), transport.asked)
        assertEquals(feed(1, 2), repo.saved("ai"))
    }

    @Test
    fun `a page that cannot be had is nothing, not an error`() = runBlocking {
        assertNull(repository(News(onFeed = { throw IOException("down") })).more("ai", "c"))
    }

    @Test
    fun `a section whose load fails after a sweep does not take the sweep's page of everything`() = runBlocking {
        var loads = 0
        val transport = News(
            onFeed = { if (loads++ == 0) throw IOException("down") else feed(1) },
            onRefresh = { feed(8, 9) },
        )
        val repo = repository(transport)

        assertEquals(RefreshResult.Unreachable, repo.sweep(shown = null, section = "papers"))
        assertNull(repo.saved("papers"))
        assertEquals(RefreshResult.New(feed(8, 9), 2), repository(News(onFeed = { throw IOException("down") }, onRefresh = { feed(8, 9) })).sweep(shown = null))
    }

    @Test
    fun `a search asks the server for the query in the section, and is not saved`() = runBlocking {
        val transport = News(onFeed = { feed(5, 6) })
        val repo = repository(transport)
        assertEquals(feed(5, 6), repo.search("ai", "forsvar"))
        assertEquals(listOf<String?>("forsvar"), transport.queries)
        assertEquals(listOf<Pair<String?, String?>>("ai" to null), transport.asked)
        assertNull(repo.saved("ai"))
    }

    @Test
    fun `a search that fails is null`() = runBlocking {
        assertNull(repository(News(onFeed = { throw IOException("no network") })).search(null, "x"))
    }

    @Test
    fun `the next page of a search carries the query and the cursor`() = runBlocking {
        val transport = News(onFeed = { feed(7) })
        repository(transport).more("now", "2026-10-14T06:00:00.000Z|8", "forsvar")
        assertEquals(listOf<String?>("forsvar"), transport.queries)
        assertEquals(listOf<Pair<String?, String?>>("now" to "2026-10-14T06:00:00.000Z|8"), transport.asked)
    }
}
