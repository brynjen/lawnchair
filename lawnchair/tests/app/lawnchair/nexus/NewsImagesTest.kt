package app.lawnchair.nexus

import app.lawnchair.nexus.news.CachedFile
import app.lawnchair.nexus.news.NewsImageCache
import app.lawnchair.nexus.news.NewsPictures
import app.lawnchair.nexus.news.evictionOrder
import app.lawnchair.nexus.news.resolveNewsImageUrl
import app.lawnchair.nexus.news.sampleSizeFor
import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The pictures of the news: where they are fetched from (only ever the server), how the saved copies
 * are keyed and kept under a size, how large they are decoded, and that a card never waits on one.
 */
class NewsImagesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val url = "https://nexus.example"
    private val key = "session-key-1"
    private val path = "/uploads/system/news/ab12cd34ef56.jpg"

    // region the address

    @Test
    fun `a server path is joined to the server`() {
        assertEquals("https://nexus.example/uploads/system/news/a.jpg", resolveNewsImageUrl(url, "/uploads/system/news/a.jpg"))
        assertEquals("https://nexus.example/uploads/system/news/a.jpg", resolveNewsImageUrl("$url/", "/uploads/system/news/a.jpg"))
        assertEquals("https://nexus.example/uploads/system/news/a.jpg", resolveNewsImageUrl(url, "uploads/system/news/a.jpg"))
        assertEquals("http://192.168.0.74:8080/uploads/x.jpg", resolveNewsImageUrl("http://192.168.0.74:8080", " /uploads/x.jpg "))
    }

    @Test
    fun `an absolute address is kept only when it is the server's own`() {
        assertEquals("https://nexus.example/uploads/a.jpg", resolveNewsImageUrl(url, "https://nexus.example/uploads/a.jpg"))
        assertEquals("https://nexus.example:443/uploads/a.jpg", resolveNewsImageUrl(url, "https://nexus.example:443/uploads/a.jpg"))
        assertNull(resolveNewsImageUrl(url, "https://elsewhere.example/uploads/a.jpg"))
        assertNull(resolveNewsImageUrl(url, "http://nexus.example/uploads/a.jpg")) // another scheme is another origin
        assertNull(resolveNewsImageUrl(url, "https://nexus.example:8443/uploads/a.jpg"))
        assertNull(resolveNewsImageUrl(url, "https://nexus.example.evil.example/uploads/a.jpg"))
        assertNull(resolveNewsImageUrl(url, "//elsewhere.example/a.jpg"))
    }

    @Test
    fun `a blank, climbing or serverless request resolves to nothing`() {
        assertNull(resolveNewsImageUrl(url, ""))
        assertNull(resolveNewsImageUrl(url, "   "))
        assertNull(resolveNewsImageUrl(url, "/uploads/system/news/../../u/1/secret.jpg"))
        assertNull(resolveNewsImageUrl("", path))
    }

    // endregion

    // region eviction

    private fun file(name: String, size: Long, modified: Long) = CachedFile(name, size, modified)

    @Test
    fun `nothing is evicted while the files fit`() {
        assertEquals(emptyList<String>(), evictionOrder(listOf(file("a", 10, 1), file("b", 10, 2)), maxBytes = 20))
        assertEquals(emptyList<String>(), evictionOrder(emptyList(), maxBytes = 0))
    }

    @Test
    fun `the oldest go first, and no more than it takes`() {
        val files = listOf(file("new", 10, 30), file("old", 10, 10), file("mid", 10, 20))
        assertEquals(listOf("old"), evictionOrder(files, maxBytes = 25))
        assertEquals(listOf("old", "mid"), evictionOrder(files, maxBytes = 10))
        assertEquals(listOf("old", "mid", "new"), evictionOrder(files, maxBytes = 5))
    }

    @Test
    fun `a big old file can be enough, and ties go by name`() {
        assertEquals(listOf("big"), evictionOrder(listOf(file("big", 100, 1), file("s", 5, 2), file("t", 5, 3)), maxBytes = 15))
        assertEquals(listOf("a", "b"), evictionOrder(listOf(file("b", 10, 1), file("a", 10, 1), file("c", 10, 1)), maxBytes = 10))
    }

    // endregion

    // region decoding

    @Test
    fun `a picture is decoded no smaller than the box it covers`() {
        // The 16:9 list card on a 1344 px phone is about 1137 px wide: a 960 px picture is enlarged by
        // the drawing, never by the decoder, and is decoded whole.
        assertEquals(1, sampleSizeFor(960, 540, 1137, 640))
        assertEquals(1, sampleSizeFor(960, 506, 1137, 640))
        // A box the picture is twice as large as: one halving, not two.
        assertEquals(2, sampleSizeFor(960, 540, 480, 270))
        assertEquals(1, sampleSizeFor(960, 540, 481, 270)) // halved it would be smaller than the box
        assertEquals(1, sampleSizeFor(960, 540, 480, 271)) // on either side
        assertEquals(4, sampleSizeFor(4000, 3000, 900, 506)) // 1000x750 still covers; 500x375 does not
        // A picture is decoded for the box's shape too: a tall box needs the height.
        assertEquals(2, sampleSizeFor(1920, 1080, 900, 500))
        assertEquals(1, sampleSizeFor(1920, 1080, 900, 600))
    }

    @Test
    fun `nothing to decode or nowhere to draw it is whole`() {
        assertEquals(1, sampleSizeFor(0, 0, 100, 100))
        assertEquals(1, sampleSizeFor(960, 540, 0, 0))
        assertEquals(1, sampleSizeFor(960, 540, -1, 270))
    }

    // endregion

    // region the saved copies

    private val bytes = byteArrayOf(1, 2, 3, 4, 5)

    @Test
    fun `a saved picture is keyed to its server, person and path`() {
        val cache = NewsImageCache(tmp.root)
        assertEquals(cache.fileFor(url, key, path), cache.fileFor(url, key, path))
        assertNotEquals(cache.fileFor(url, key, path), cache.fileFor(url, "someone-else", path))
        assertNotEquals(cache.fileFor(url, key, path), cache.fileFor("https://other.example", key, path))
        assertNotEquals(cache.fileFor(url, key, path), cache.fileFor(url, key, "/uploads/system/news/other.jpg"))
        assertNotEquals(cache.fileFor("a", "bc", "d"), cache.fileFor("ab", "c", "d"))
        val name = cache.fileFor(url, key, path).name
        assertTrue(name, Regex("img-[0-9a-f]{64}").matches(name))
    }

    @Test
    fun `a picture reads back, and only for its own person`() {
        val cache = NewsImageCache(tmp.root)
        assertNull(cache.read(url, key, path))
        cache.write(url, key, path, bytes)
        assertArrayEquals(bytes, cache.read(url, key, path))
        assertNull(cache.read(url, "someone-else", path))
        assertNull(cache.read("https://other.example", key, path))
    }

    @Test
    fun `the folder is kept under its size by deleting the oldest`() {
        val cache = NewsImageCache(tmp.root, maxBytes = 12)
        cache.write(url, key, "/a", ByteArray(5))
        cache.fileFor(url, key, "/a").setLastModified(1_000)
        cache.write(url, key, "/b", ByteArray(5))
        cache.fileFor(url, key, "/b").setLastModified(2_000)
        assertNotNull(cache.read(url, key, "/a")) // reading uses it: /a is now newer than /b
        cache.write(url, key, "/c", ByteArray(5)) // 15 bytes > 12: the oldest, /b, goes
        assertNull(cache.read(url, key, "/b"))
        assertNotNull(cache.read(url, key, "/a"))
        assertNotNull(cache.read(url, key, "/c"))
    }

    private fun assertNotNull(value: Any?) = assertTrue(value != null)

    // endregion

    // region NewsPictures

    private class Pictures(val onImage: (String) -> ByteArray) : StubNexusTransport() {
        val asked = ArrayList<String>()

        override suspend fun newsImage(base: String, token: String, path: String): ByteArray {
            asked += path
            return onImage(path)
        }
    }

    private fun pictures(transport: StubNexusTransport, authKey: String = key) =
        NewsPictures(transport, NewsImageCache(File(tmp.root, "images")), url, authKey)

    @Test
    fun `a picture is fetched once and then comes from disk`() = runBlocking {
        val transport = Pictures { bytes }
        val pictures = pictures(transport)
        assertArrayEquals(bytes, pictures.bytes(path))
        assertArrayEquals(bytes, pictures.bytes(path))
        assertEquals(listOf(path), transport.asked)
    }

    @Test
    fun `a picture saved on an earlier run is shown without the network`() = runBlocking {
        NewsImageCache(File(tmp.root, "images")).write(url, key, path, bytes)
        val transport = Pictures { throw IOException("offline") }
        assertArrayEquals(bytes, pictures(transport).bytes(path))
        assertEquals(emptyList<String>(), transport.asked)
    }

    @Test
    fun `no network and nothing saved is no picture, and is not asked for again at once`() = runBlocking {
        val transport = Pictures { throw IOException("offline") }
        val pictures = pictures(transport)
        assertNull(pictures.bytes(path))
        assertNull(pictures.bytes(path))
        assertEquals(listOf(path), transport.asked)
        // A different picture is a different question.
        assertNull(pictures.bytes("/uploads/system/news/other.jpg"))
        assertEquals(2, transport.asked.size)
    }

    @Test
    fun `a refused session or an empty answer is no picture`() = runBlocking {
        assertNull(pictures(Pictures { throw app.lawnchair.nexus.net.NexusUnauthorizedException() }).bytes(path))
        assertNull(pictures(Pictures { ByteArray(0) }).bytes(path))
        assertFalse(NewsImageCache(File(tmp.root, "images")).fileFor(url, key, path).exists())
    }

    @Test
    fun `a path that is not the server's is never asked for`() = runBlocking {
        val transport = Pictures { bytes }
        assertNull(pictures(transport).bytes("https://elsewhere.example/a.jpg"))
        assertNull(pictures(transport).bytes("/uploads/../x.jpg"))
        assertEquals(emptyList<String>(), transport.asked)
    }

    @Test
    fun `another person's session does not read the first person's saved picture`() = runBlocking {
        pictures(Pictures { bytes }).bytes(path)
        val other = Pictures { byteArrayOf(9) }
        assertArrayEquals(byteArrayOf(9), pictures(other, authKey = "someone-else").bytes(path))
        assertEquals(listOf(path), other.asked)
    }

    // endregion
}
