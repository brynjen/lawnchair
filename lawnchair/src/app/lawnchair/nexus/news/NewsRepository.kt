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

    fun fileFor(serverUrl: String, authKey: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest("$serverUrl\u0000$authKey".toByteArray())
        return File(dir, PREFIX + digest.joinToString("") { "%02x".format(it) } + SUFFIX)
    }

    fun read(serverUrl: String, authKey: String): NewsFeed? =
        runCatching { parseNewsFeed(fileFor(serverUrl, authKey).readText()) }.getOrNull()

    fun write(serverUrl: String, authKey: String, feed: NewsFeed) {
        runCatching {
            dir.mkdirs()
            val file = fileFor(serverUrl, authKey)
            // Through a temporary name, so a write cut short never replaces a good copy.
            val partial = File(dir, file.name + ".tmp")
            partial.writeText(feed.toJson())
            if (!partial.renameTo(file)) partial.delete()
            dir.listFiles()
                ?.filter { it.name.startsWith(PREFIX) && it.name != file.name }
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

    /** The saved copy for this server and person, or null. */
    suspend fun saved(): NewsFeed? = withContext(Dispatchers.IO) { cache.read(serverUrl, authKey) }

    /** The server's feed, saved; null when it could not be had. */
    suspend fun load(): NewsFeed? = calls.withLock { fetch() }

    /**
     * Asks the server to sweep its sources now (it may take many seconds), then loads the feed, and
     * says what came of it ([refreshResult], against what the page [shown]). If the sweep fails or
     * times out the feed is loaded anyway; if only the load fails the sweep's own answer, the same
     * shape, is used; if both fail the result is [RefreshResult.Unreachable] and the saved copy stays.
     */
    suspend fun sweep(shown: NewsFeed?): RefreshResult = calls.withLock {
        val swept = attempt(SWEEP_TIMEOUT_MS) { transport.newsRefresh(serverUrl, authKey) }
        refreshResult(shown, fetch() ?: swept?.also { save(it) })
    }

    private suspend fun fetch(): NewsFeed? =
        attempt(LOAD_TIMEOUT_MS) { transport.newsFeed(serverUrl, authKey, LIMIT) }?.also { save(it) }

    private suspend fun save(feed: NewsFeed) = withContext(Dispatchers.IO) { cache.write(serverUrl, authKey, feed) }

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
