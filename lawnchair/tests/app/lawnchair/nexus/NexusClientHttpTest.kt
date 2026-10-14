package app.lawnchair.nexus

import app.lawnchair.nexus.net.NexusClient
import app.lawnchair.nexus.net.NexusUnauthorizedException
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The unary calls of [NexusClient] the news uses, against a one-answer HTTP server on loopback. */
class NexusClientHttpTest {

    /** What the stub saw: the request line, the headers (lower-cased names) and the body. */
    private class Seen(val line: String, val headers: Map<String, String>, val body: String)

    private val servers = ArrayList<ServerSocket>()

    @After
    fun tearDown() = servers.forEach { runCatching { it.close() } }

    /** Answers the next request with [status] and [body]; returns the base URL and what the request looked like. */
    private fun serve(status: Int, body: ByteArray, seen: LinkedBlockingQueue<Seen>): String {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).also { servers += it }
        Thread {
            runCatching {
                server.accept().use { socket ->
                    val input = socket.getInputStream()
                    val head = StringBuilder()
                    while (!head.endsWith("\r\n\r\n")) head.append(input.read().toChar())
                    val lines = head.toString().trim().split("\r\n")
                    val headers = lines.drop(1).associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
                    val length = headers["content-length"]?.toInt() ?: 0
                    val received = ByteArray(length).also { var n = 0; while (n < length) n += input.read(it, n, length - n) }
                    seen.add(Seen(lines[0], headers, String(received)))
                    val out = ByteArrayOutputStream()
                    out.write("HTTP/1.1 $status X\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    out.write(body)
                    socket.getOutputStream().write(out.toByteArray())
                    socket.getOutputStream().flush()
                }
            }
        }.also { it.isDaemon = true }.start()
        return "http://127.0.0.1:${server.localPort}"
    }

    private val feedJson = """{"cards":[{"id":1,"title":"T","imageUrl":"/uploads/system/news/a.jpg"}],"refreshedAt":null}"""

    @Test
    fun `a picture is fetched with a GET and the session key`() {
        val seen = LinkedBlockingQueue<Seen>()
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3)
        val base = serve(200, jpeg, seen)

        val bytes = runBlocking { NexusClient().newsImage(base, "session-key", "/uploads/system/news/a.jpg") }

        assertArrayEquals(jpeg, bytes)
        val request = seen.poll(5, TimeUnit.SECONDS)!!
        assertEquals("GET /uploads/system/news/a.jpg HTTP/1.1", request.line)
        assertEquals("Bearer session-key", request.headers["authorization"])
    }

    @Test
    fun `a refused session is a NexusUnauthorizedException, any other failure an IOException`() {
        val refused = serve(401, "Not signed in.".toByteArray(), LinkedBlockingQueue())
        try {
            runBlocking { NexusClient().newsImage(refused, "k", "/uploads/system/news/a.jpg") }
            fail("a 401 must throw")
        } catch (_: NexusUnauthorizedException) {
        }
        val missing = serve(404, "Not found.".toByteArray(), LinkedBlockingQueue())
        try {
            runBlocking { NexusClient().newsImage(missing, "k", "/uploads/system/news/a.jpg") }
            fail("a 404 must throw")
        } catch (e: IOException) {
            assertTrue(e.message, e !is NexusUnauthorizedException && e.message!!.contains("404"))
        }
    }

    @Test
    fun `a picture of another server is refused before any request is made`() {
        val seen = LinkedBlockingQueue<Seen>()
        val base = serve(200, byteArrayOf(1), seen)
        try {
            runBlocking { NexusClient().newsImage(base, "k", "https://elsewhere.example/a.jpg") }
            fail("another origin must throw")
        } catch (_: IOException) {
        }
        assertEquals(null, seen.poll(300, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `the feed is a POST of the feed method and the limit, and is read`() {
        val seen = LinkedBlockingQueue<Seen>()
        val base = serve(200, feedJson.toByteArray(), seen)

        val feed = runBlocking { NexusClient().newsFeed(base, "session-key", 50) }

        assertEquals("/uploads/system/news/a.jpg", feed.items.single().imageUrl)
        val request = seen.poll(5, TimeUnit.SECONDS)!!
        assertEquals("POST /news HTTP/1.1", request.line)
        assertEquals("Bearer session-key", request.headers["authorization"])
        val body = JSONObject(request.body)
        assertEquals("feed", body.getString("method"))
        assertEquals(50, body.getInt("limit"))
    }

    @Test
    fun `a refresh is a POST of the refresh method, and a body that is not a feed fails`() {
        val seen = LinkedBlockingQueue<Seen>()
        val base = serve(200, feedJson.toByteArray(), seen)
        runBlocking { NexusClient().newsRefresh(base, "k") }
        assertEquals("refresh", JSONObject(seen.poll(5, TimeUnit.SECONDS)!!.body).getString("method"))

        val broken = serve(200, "{\"nothing\":1}".toByteArray(), LinkedBlockingQueue())
        try {
            runBlocking { NexusClient().newsFeed(broken, "k", 10) }
            fail("an unreadable feed must throw")
        } catch (_: IOException) {
        }
    }
}
