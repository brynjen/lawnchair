package app.lawnchair.nexus.news

import app.lawnchair.nexus.net.NexusTransport
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The last feed the server gave, as a JSON file in [dir] (the app's cache directory, so the system
 * may clear it and nothing depends on it). One file per server and signed-in person: the name is a
 * SHA-256 of both, so a different person or server never reads another's news, and writing a new
 * one removes the others (a new sign-in is a new session key, which would otherwise leave a file
 * behind each time). Every failure is a miss; the cache is only ever a head start.
 */
class NewsCache(private val dir: File) {

    private fun stem(serverUrl: String, authKey: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest("$serverUrl\u0000$authKey".toByteArray())
        return PREFIX + digest.joinToString("") { "%02x".format(it) }
    }

    /** The file of this person's feed on this server; each [section] (`now`, `ai`, `papers`) has its own. */
    fun fileFor(serverUrl: String, authKey: String, section: String? = null): File =
        File(dir, stem(serverUrl, authKey) + (section?.let { "-$it" } ?: "") + SUFFIX)

    fun read(serverUrl: String, authKey: String, section: String? = null): NewsFeed? =
        runCatching { parseNewsFeed(fileFor(serverUrl, authKey, section).readText()) }.getOrNull()

    fun write(serverUrl: String, authKey: String, feed: NewsFeed, section: String? = null) {
        runCatching {
            dir.mkdirs()
            val file = fileFor(serverUrl, authKey, section)
            // Through a temporary name, so a write cut short never replaces a good copy.
            val partial = File(dir, file.name + ".tmp")
            partial.writeText(feed.toJson())
            if (!partial.renameTo(file)) partial.delete()
            // Anything of another server or person goes; the other sections of this one stay.
            val mine = stem(serverUrl, authKey)
            dir.listFiles()
                ?.filter { it.name.startsWith(PREFIX) && !it.name.startsWith(mine) }
                ?.forEach { it.delete() }
        }
    }

    private companion object {
        const val PREFIX = "news-"
        const val SUFFIX = ".json"
    }
}

/**
 * The News page's data: what the phone saved of this person's feed, and the server's latest, saved
 * as it arrives. The page shows [saved] first and refreshes behind it; a failed refresh (no network,
 * a 401, a timeout, a feed that will not parse) returns null and leaves the saved copy where it is,
 * so the page never has an error to show. Calls are serialised, so a slow one cannot land after a
 * newer one and overwrite it.
 */
class NewsRepository(
    private val transport: NexusTransport,
    private val cache: NewsCache,
    private val serverUrl: String,
    private val authKey: String,
) {
    private val calls = Mutex()

    /** The saved copy of [section] for this server and person, or null. */
    suspend fun saved(section: String? = null): NewsFeed? =
        withContext(Dispatchers.IO) { cache.read(serverUrl, authKey, section) }

    /** The server's first page of [section], saved; null when it could not be had. */
    suspend fun load(section: String? = null): NewsFeed? = calls.withLock { fetch(section) }

    /**
     * The page of [section] after [cursor] (a `nextCursor`), not saved: the saved copy is the first
     * page. Null when it could not be had.
     */
    suspend fun more(section: String?, cursor: String): NewsFeed? = calls.withLock {
        attempt(LOAD_TIMEOUT_MS) { transport.newsFeed(serverUrl, authKey, LIMIT, cursor, section) }
    }

    /**
     * Asks the server to sweep its sources now (it may take many seconds), then loads the feed, and
     * says what came of it ([refreshResult], against what the page [shown]). If the sweep fails or
     * times out the feed is loaded anyway; if only the load fails the sweep's own answer, the same
     * shape, is used; if both fail the result is [RefreshResult.Unreachable] and the saved copy stays.
     */
    suspend fun sweep(shown: NewsFeed?, section: String? = null): RefreshResult = calls.withLock {
        val swept = attempt(SWEEP_TIMEOUT_MS) { transport.newsRefresh(serverUrl, authKey) }
        // The sweep's own answer is the page of everything: it stands in only for that page.
        refreshResult(shown, fetch(section) ?: if (section == null) swept?.also { save(it, null) } else null)
    }

    private suspend fun fetch(section: String?): NewsFeed? =
        attempt(LOAD_TIMEOUT_MS) { transport.newsFeed(serverUrl, authKey, LIMIT, null, section) }?.also { save(it, section) }

    private suspend fun save(feed: NewsFeed, section: String?) =
        withContext(Dispatchers.IO) { cache.write(serverUrl, authKey, feed, section) }

    /** Null on any failure or when [timeoutMs] passes: the socket has no read timeout of its own. */
    private suspend fun attempt(timeoutMs: Long, call: suspend () -> NewsFeed): NewsFeed? =
        try {
            withTimeoutOrNull(timeoutMs) { call() }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }

    companion object {
        /** How many cards one `feed` call asks for. */
        const val LIMIT = 50
        private const val LOAD_TIMEOUT_MS = 30_000L
        private const val SWEEP_TIMEOUT_MS = 90_000L
    }
}
