package app.lawnchair.nexus

import app.lawnchair.nexus.net.ChatSubject
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
import org.junit.Assert.assertFalse
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

    @Test
    fun `an ordinary conversation has a null title and no subject`() {
        val seen = LinkedBlockingQueue<Seen>()
        val base = serve(200, "41".toByteArray(), seen)

        val id = runBlocking { NexusClient().createConversation(base, "session-key") }

        assertEquals(41, id)
        val request = seen.poll(5, TimeUnit.SECONDS)!!
        assertEquals("POST /voiceSquad HTTP/1.1", request.line)
        val body = JSONObject(request.body)
        assertEquals("createConversation", body.getString("method"))
        assertTrue(body.isNull("title"))
        assertFalse(body.has("subjectKind"))
        assertFalse(body.has("subjectId"))
    }

    @Test
    fun `a conversation about an article names it and takes its title`() {
        val seen = LinkedBlockingQueue<Seen>()
        val base = serve(200, "42".toByteArray(), seen)

        val id = runBlocking {
            NexusClient().createConversation(base, "k", ChatSubject("news", 1234L, "Regjeringen la fram budsjettet"))
        }

        assertEquals(42, id)
        val body = JSONObject(seen.poll(5, TimeUnit.SECONDS)!!.body)
        assertEquals("createConversation", body.getString("method"))
        assertEquals("Regjeringen la fram budsjettet", body.getString("title"))
        assertEquals("news", body.getString("subjectKind"))
        assertEquals(1234L, body.getLong("subjectId"))
    }

    @Test
    fun `a turn says nothing of voice unless it is asked to be text`() {
        val plain = LinkedBlockingQueue<Seen>()
        runBlocking { NexusClient().submitTurn(serve(200, ByteArray(0), plain), "k", 7, "Hei", "turn-1") }
        val unsaid = JSONObject(plain.poll(5, TimeUnit.SECONDS)!!.body)
        assertEquals("submitTurn", unsaid.getString("method"))
        assertEquals(7, unsaid.getInt("conversationId"))
        assertFalse("the server decides", unsaid.has("voice"))

        val text = LinkedBlockingQueue<Seen>()
        runBlocking { NexusClient().submitTurn(serve(200, ByteArray(0), text), "k", 7, "Hei", "turn-2", voice = false) }
        val said = JSONObject(text.poll(5, TimeUnit.SECONDS)!!.body)
        assertFalse(said.getBoolean("voice"))
    }

    @Test
    fun `a section and a cursor go with the feed call, and only when they are asked for`() {
        val plain = LinkedBlockingQueue<Seen>()
        runBlocking { NexusClient().newsFeed(serve(200, feedJson.toByteArray(), plain), "k", 50) }
        val unasked = JSONObject(plain.poll(5, TimeUnit.SECONDS)!!.body)
        assertFalse(unasked.has("section"))
        assertFalse(unasked.has("cursor"))

        val paged = LinkedBlockingQueue<Seen>()
        runBlocking {
            NexusClient().newsFeed(serve(200, feedJson.toByteArray(), paged), "k", 50, cursor = "2026-10-14T06:00:00.000Z|8", section = "papers")
        }
        val asked = JSONObject(paged.poll(5, TimeUnit.SECONDS)!!.body)
        assertEquals("papers", asked.getString("section"))
        assertEquals("2026-10-14T06:00:00.000Z|8", asked.getString("cursor"))
        assertEquals(50, asked.getInt("limit"))
    }

    @Test
    fun `a search goes with the feed call as the query, and only when there is one`() {
        val plain = LinkedBlockingQueue<Seen>()
        runBlocking { NexusClient().newsFeed(serve(200, feedJson.toByteArray(), plain), "k", 50) }
        assertFalse(JSONObject(plain.poll(5, TimeUnit.SECONDS)!!.body).has("query"))

        val searched = LinkedBlockingQueue<Seen>()
        runBlocking {
            NexusClient().newsFeed(serve(200, feedJson.toByteArray(), searched), "k", 50, section = "ai", query = "forsvar")
        }
        val asked = JSONObject(searched.poll(5, TimeUnit.SECONDS)!!.body)
        assertEquals("forsvar", asked.getString("query"))
        assertEquals("ai", asked.getString("section"))
    }
}
