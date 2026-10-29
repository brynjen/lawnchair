package app.lawnchair.nexus

import app.lawnchair.nexus.box.SubstrateMode
import app.lawnchair.nexus.net.NexusClient
import app.lawnchair.nexus.net.NexusTurnEvent
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The method-stream plumbing of [NexusClient] (`openMethodStream`, behind `streamTurn` and
 * `watchBox`) against a one-connection WebSocket server on loopback that speaks just enough RFC 6455
 * and Serverpod's `omsc` / `omsr` / `msm` / `cmsc` frames.
 */
class NexusClientStreamTest {

    /** What the server end of the socket can do: read what the client sent, say something, hang up. */
    private class Conn(private val socket: Socket) {
        private val input = socket.getInputStream()
        private val output = socket.getOutputStream()

        /** The next text frame the client sent, or null once it closed or the socket did. */
        fun read(): String? = try {
            readFrame()
        } catch (_: java.io.IOException) {
            null
        }

        private fun readFrame(): String? {
            while (true) {
                val b0 = input.read()
                if (b0 < 0) return null
                val opcode = b0 and 0x0F
                val b1 = input.read()
                var len = (b1 and 0x7F).toLong()
                if (len == 126L) len = (input.read() shl 8 or input.read()).toLong()
                if (len == 127L) len = (0 until 8).fold(0L) { acc, _ -> acc shl 8 or input.read().toLong() }
                val mask = ByteArray(4).also { input.readFully(it) }
                val payload = ByteArray(len.toInt()).also { input.readFully(it) }
                for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
                when (opcode) {
                    0x1 -> return String(payload, Charsets.UTF_8)
                    0x8 -> return null
                    else -> Unit // pings and the like
                }
            }
        }

        fun send(text: String) {
            val payload = text.toByteArray(Charsets.UTF_8)
            output.write(0x81)
            when {
                payload.size < 126 -> output.write(payload.size)
                else -> {
                    output.write(126)
                    output.write(payload.size shr 8)
                    output.write(payload.size and 0xFF)
                }
            }
            output.write(payload)
            output.flush()
        }

        /** Drop the connection without a closing handshake. */
        fun hangUp() = socket.close()

        private fun InputStream.readFully(into: ByteArray) {
            var n = 0
            while (n < into.size) {
                val r = read(into, n, into.size - n)
                if (r < 0) throw java.io.EOFException()
                n += r
            }
        }
    }

    private val servers = ArrayList<ServerSocket>()

    @After
    fun tearDown() = servers.forEach { runCatching { it.close() } }

    /** Serves one connection with [script] on its own thread; returns the server's base URL. */
    private fun serve(script: (Conn) -> Unit): String {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).also { servers += it }
        Thread {
            runCatching {
                val socket = server.accept()
                val headers = socket.getInputStream().let { input ->
                    val text = StringBuilder()
                    while (!text.endsWith("\r\n\r\n")) text.append(input.read().toChar())
                    text.toString()
                }
                val key = Regex("Sec-WebSocket-Key: (.+)\r\n", RegexOption.IGNORE_CASE).find(headers)!!.groupValues[1].trim()
                val accept = Base64.getEncoder().encodeToString(
                    MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()),
                )
                socket.getOutputStream().write(
                    ("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                        "Sec-WebSocket-Accept: $accept\r\n\r\n").toByteArray(),
                )
                socket.getOutputStream().flush()
                script(Conn(socket))
            }
        }.also { it.isDaemon = true }.start()
        return "http://127.0.0.1:${server.localPort}"
    }

    private fun omsr(res: String = "success") = """{"type":"omsr","data":{"res":"$res"}}"""
    private fun msm(cid: String, modelJson: String) =
        """{"type":"msm","data":{"cid":"$cid","o":{"className":"X","data":$modelJson}}}"""
    private fun cmsc(cid: String) = """{"type":"cmsc","data":{"cid":"$cid"}}"""
    private fun cidOf(omsc: JSONObject) = omsc.getJSONObject("data").getString("cid")

    private val lanesJson = """{"lanes":"2z000000","dropped":1}"""

    @Test
    fun `watchBox makes the lanes call, answers pings and reads packets until the server ends the stream`() {
        val calls = LinkedBlockingQueue<JSONObject>()
        val pong = LinkedBlockingQueue<String>()
        val base = serve { conn ->
            val omsc = JSONObject(conn.read()!!).also { calls.add(it) }
            val cid = cidOf(omsc)
            conn.send(omsr())
            conn.send("""{"type":"ping","data":{}}""")
            pong.add(conn.read()!!)
            // Spaced out, because the stream keeps only the newest packet when the collector is behind.
            conn.send(msm(cid, lanesJson))
            Thread.sleep(200)
            conn.send(msm(cid, """{"nothing":"like a packet"}""")) // dropped, not fatal
            Thread.sleep(200)
            conn.send(msm(cid, lanesJson.replace("2z", "23")))
            Thread.sleep(200)
            conn.send(cmsc(cid))
        }

        val frames = runBlocking { withTimeout(10_000) { NexusClient().watchBox(base, "session-key").toList() } }

        assertEquals(
            listOf(
                listOf(SubstrateMode.Thinking, SubstrateMode.Unknown),
                listOf(SubstrateMode.Thinking, SubstrateMode.Processing),
            ),
            frames.map { f -> f.slots.map { it.mode } },
        )
        assertEquals(listOf(0, 1), frames[0].slots.map { it.slot })
        assertEquals(8, frames[0].capacity)
        assertEquals(1, frames[0].dropped)

        val call = calls.poll(5, TimeUnit.SECONDS)!!
        assertEquals("omsc", call.getString("type"))
        val data = call.getJSONObject("data")
        assertEquals("substrate", data.getString("en"))
        assertEquals("lanes", data.getString("m"))
        assertEquals("{}", data.getString("args")) // a JSON-encoded string, not an object
        assertEquals("Bearer session-key", data.getString("auth"))
        assertEquals("pong", JSONObject(pong.poll(5, TimeUnit.SECONDS)!!).getString("type"))
    }

    @Test
    fun `watchBox ends when the connection drops`() {
        val base = serve { conn ->
            conn.read()
            conn.send(omsr())
            conn.hangUp()
        }
        // Completes or throws, never hangs: the caller reconnects either way.
        runBlocking { withTimeout(10_000) { runCatching { NexusClient().watchBox(base, "k").toList() } } }
    }

    @Test
    fun `streamTurn is ready on omsr, streams events, ends at the terminal one and closes the stream`() {
        val calls = LinkedBlockingQueue<JSONObject>()
        val afterEnd = LinkedBlockingQueue<String>()
        val base = serve { conn ->
            val omsc = JSONObject(conn.read()!!).also { calls.add(it) }
            val cid = cidOf(omsc)
            conn.send(omsr())
            conn.send(msm(cid, """{"kind":"heartbeat"}"""))
            conn.send(msm(cid, """{"kind":"textDelta","textDelta":"Hello","turnId":"turn-1"}"""))
            conn.send(msm(cid, """{"kind":"completed","turnId":"turn-1"}"""))
            // The client closes the stream once it has the terminal event: a cmsc if it gets flushed
            // before the socket is cancelled, otherwise just the end of the connection.
            afterEnd.add(conn.read() ?: "closed")
        }
        val ready = AtomicInteger()

        val events = runBlocking {
            withTimeout(10_000) { NexusClient().streamTurn(base, "session-key", 7) { ready.incrementAndGet() }.toList() }
        }

        assertEquals(listOf(NexusTurnEvent.TextDelta("Hello", "turn-1"), NexusTurnEvent.Completed("turn-1")), events)
        assertEquals(1, ready.get())
        val omsc = calls.poll(5, TimeUnit.SECONDS)!!
        val data = omsc.getJSONObject("data")
        assertEquals("voiceSquad", data.getString("en"))
        assertEquals("streamTurn", data.getString("m"))
        assertEquals(7, JSONObject(data.getString("args")).getInt("conversationId"))
        assertEquals("Bearer session-key", data.getString("auth"))
        val closed = afterEnd.poll(5, TimeUnit.SECONDS)!!
        if (closed != "closed") {
            assertEquals("cmsc", JSONObject(closed).getString("type"))
            assertEquals(cidOf(omsc), JSONObject(closed).getJSONObject("data").getString("cid"))
        }
    }

    @Test
    fun `streamTurn turns a failed event into the terminal Failed`() {
        val base = serve { conn ->
            val cid = cidOf(JSONObject(conn.read()!!))
            conn.send(omsr())
            conn.send(msm(cid, """{"kind":"failed","errorMessage":"boom","turnId":"t"}"""))
            conn.send(msm(cid, """{"kind":"textDelta","textDelta":"never seen"}"""))
        }
        val events = runBlocking { withTimeout(10_000) { NexusClient().streamTurn(base, "k", 1).toList() } }
        assertEquals(listOf<NexusTurnEvent>(NexusTurnEvent.Failed("boom", "t")), events)
    }

    @Test
    fun `streamTurn reports a rejected stream, a stream the server ended, and a dropped connection as Failed`() {
        val rejected = serve { conn ->
            conn.read()
            conn.send(omsr("denied"))
        }
        val ended = serve { conn ->
            val cid = cidOf(JSONObject(conn.read()!!))
            conn.send(omsr())
            conn.send(cmsc(cid)) // what a refused session key looks like, after omsr success
        }
        val dropped = serve { conn ->
            conn.read()
            conn.send(omsr())
            conn.hangUp()
        }
        fun last(base: String) = runBlocking {
            withTimeout(10_000) { runCatching { NexusClient().streamTurn(base, "k", 1).toList() }.getOrNull() }
        }

        assertEquals(listOf<NexusTurnEvent>(NexusTurnEvent.Failed("stream rejected: denied")), last(rejected))
        assertEquals(listOf<NexusTurnEvent>(NexusTurnEvent.Failed("Nexus closed the stream.")), last(ended))
        // A hang-up has no tidy reason (the socket's own message, or the closing code); it must be Failed.
        val afterDrop = last(dropped)
        assertNotNull(afterDrop)
        assertTrue(afterDrop.toString(), afterDrop!!.size == 1 && afterDrop[0] is NexusTurnEvent.Failed)
    }

    @Test
    fun `cancelling the collection closes the stream`() {
        val closing = LinkedBlockingQueue<String>()
        val base = serve { conn ->
            val cid = cidOf(JSONObject(conn.read()!!))
            conn.send(omsr())
            conn.send(msm(cid, lanesJson))
            closing.add(conn.read() ?: "closed")
        }
        runBlocking { withTimeout(10_000) { NexusClient().watchBox(base, "k").first() } }
        // The socket goes away: a cmsc if it is flushed before the cancel, otherwise the connection ends.
        val closed = closing.poll(5, TimeUnit.SECONDS)!!
        if (closed != "closed") {
            assertEquals("cmsc", JSONObject(closed).getString("type"))
            assertEquals("substrate", JSONObject(closed).getJSONObject("data").getString("en"))
        }
    }
}
