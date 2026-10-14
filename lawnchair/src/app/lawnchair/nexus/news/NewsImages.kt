package app.lawnchair.nexus.news

import app.lawnchair.nexus.net.NexusClient
import app.lawnchair.nexus.net.NexusTransport
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The address of a news picture: the server's own [path] (`/uploads/system/news/<hash>.jpg`) joined to
 * [serverUrl]. The person's session key goes with the request, so nothing that is not this server is
 * ever resolved: an absolute address is kept only when it is the server's own origin, and a
 * protocol-relative one, one that climbs out of its folder, or a blank one is null.
 */
fun resolveNewsImageUrl(serverUrl: String, path: String): String? {
    val p = path.trim()
    if (p.isEmpty() || serverUrl.isBlank()) return null
    if (p.startsWith("http://", ignoreCase = true) || p.startsWith("https://", ignoreCase = true)) {
        return p.takeIf { sameOrigin(serverUrl, it) }
    }
    if (p.startsWith("//") || p.split('/').any { it == ".." }) return null
    return NexusClient.unaryUrl(serverUrl, p)
}

private fun sameOrigin(a: String, b: String): Boolean = runCatching {
    fun origin(u: String): Triple<String, String, Int> {
        val uri = URI(u.trim())
        val scheme = uri.scheme.lowercase()
        return Triple(scheme, uri.host.lowercase(), if (uri.port >= 0) uri.port else if (scheme == "https") 443 else 80)
    }
    origin(a) == origin(b)
}.getOrDefault(false)

/** One saved picture as eviction sees it. */
data class CachedFile(val name: String, val size: Long, val lastModified: Long)

/**
 * Which of [files] to delete so the rest fit in [maxBytes]: the oldest first (ties by name), and no
 * more than it takes.
 */
fun evictionOrder(files: List<CachedFile>, maxBytes: Long): List<String> {
    var total = files.sumOf { it.size }
    val evict = ArrayList<String>()
    for (file in files.sortedWith(compareBy({ it.lastModified }, { it.name }))) {
        if (total <= maxBytes) break
        evict += file.name
        total -= file.size
    }
    return evict
}

/**
 * The `inSampleSize` for decoding a [width] by [height] picture that will be drawn to cover a
 * [boxWidthPx] by [boxHeightPx] box (cropped to fill): the largest power of two that still leaves the
 * decoded picture at least as large as the cover, so it is never enlarged by the decoder and never
 * decoded bigger than it is drawn. At least 1; a picture smaller than its box is decoded whole.
 */
fun sampleSizeFor(width: Int, height: Int, boxWidthPx: Int, boxHeightPx: Int): Int {
    if (width <= 0 || height <= 0 || boxWidthPx <= 0 || boxHeightPx <= 0) return 1
    var sample = 1
    // Doubling is allowed while the picture, halved again, still covers the box on both sides.
    while (width / (sample * 2) >= boxWidthPx && height / (sample * 2) >= boxHeightPx) sample *= 2
    return sample
}

/**
 * The pictures of the news, as the server sent them (JPEGs it already shrank), saved in [dir] (the
 * app's cache directory) so a card never waits on the network for one it has shown before. One file
 * per server, person and path: the name is a SHA-256 of the three, so another person or server never
 * reads them. The folder is kept under [maxBytes] by deleting the oldest files; reading a file counts
 * as using it. Every failure is a miss.
 */
class NewsImageCache(private val dir: File, private val maxBytes: Long = 40L * 1024 * 1024) {

    fun fileFor(serverUrl: String, authKey: String, path: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest("$serverUrl\u0000$authKey\u0000$path".toByteArray())
        return File(dir, PREFIX + digest.joinToString("") { "%02x".format(it) })
    }

    fun read(serverUrl: String, authKey: String, path: String): ByteArray? = runCatching {
        val file = fileFor(serverUrl, authKey, path)
        file.readBytes().also { file.setLastModified(System.currentTimeMillis()) }
    }.getOrNull()

    fun write(serverUrl: String, authKey: String, path: String, bytes: ByteArray) {
        runCatching {
            dir.mkdirs()
            val file = fileFor(serverUrl, authKey, path)
            // Through a temporary name, so a write cut short is never read as a picture.
            val partial = File(dir, file.name + ".tmp")
            partial.writeBytes(bytes)
            if (!partial.renameTo(file)) partial.delete()
            trim()
        }
    }

    private fun trim() {
        val files = dir.listFiles { f -> f.name.startsWith(PREFIX) && !f.name.endsWith(".tmp") } ?: return
        val byName = files.associateBy { it.name }
        evictionOrder(files.map { CachedFile(it.name, it.length(), it.lastModified()) }, maxBytes)
            .forEach { byName[it]?.delete() }
    }

    private companion object {
        const val PREFIX = "img-"
    }
}

/**
 * The news pictures of one server and person: the saved copy, else fetched with the person's session
 * key and saved. A picture that could not be had is not asked for again for a minute, so a list
 * scrolled while offline does not retry every card on every pass. Null for no picture; callers show
 * a placeholder, never an error.
 */
class NewsPictures(
    private val transport: NexusTransport,
    private val cache: NewsImageCache,
    private val serverUrl: String,
    private val authKey: String,
) {
    private val failedAt = ConcurrentHashMap<String, Long>()

    /** What names [path]'s picture for this server and person (a digest: it can key a memory cache too). */
    fun keyFor(path: String): String = cache.fileFor(serverUrl, authKey, path).name

    suspend fun bytes(path: String): ByteArray? = withContext(Dispatchers.IO) {
        if (resolveNewsImageUrl(serverUrl, path) == null) return@withContext null
        cache.read(serverUrl, authKey, path)?.let { return@withContext it }
        val failed = failedAt[path]
        if (failed != null && System.currentTimeMillis() - failed < RETRY_AFTER_MS) return@withContext null
        val fetched = try {
            withTimeoutOrNull(FETCH_TIMEOUT_MS) { transport.newsImage(serverUrl, authKey, path) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        if (fetched == null || fetched.isEmpty()) {
            failedAt[path] = System.currentTimeMillis()
            null
        } else {
            cache.write(serverUrl, authKey, path, fetched)
            fetched
        }
    }

    private companion object {
        const val RETRY_AFTER_MS = 60_000L
        const val FETCH_TIMEOUT_MS = 30_000L
    }
}
