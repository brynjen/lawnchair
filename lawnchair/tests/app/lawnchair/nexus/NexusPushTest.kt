package app.lawnchair.nexus

import app.lawnchair.nexus.push.NexusPush
import app.lawnchair.nexus.push.NtfyFrame
import app.lawnchair.nexus.push.PushStats
import app.lawnchair.nexus.push.PushSubscription
import app.lawnchair.nexus.push.decodeNexusPush
import app.lawnchair.nexus.push.parseNtfyFrame
import app.lawnchair.nexus.push.parsePushSubscription
import app.lawnchair.nexus.push.pushRetrySeconds
import app.lawnchair.nexus.push.pushSocketUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The push connection's own words and sums, with no socket: what ntfy sends, where to listen, when to try again. */
class NexusPushTest {

    @Test
    fun `reads ntfy's open, keepalive and message, and ignores the rest`() {
        assertEquals(NtfyFrame.Open, parseNtfyFrame("""{"id":"a","time":1,"event":"open","topic":"t"}"""))
        assertEquals(NtfyFrame.Keepalive, parseNtfyFrame("""{"id":"b","time":2,"event":"keepalive","topic":"t"}"""))
        assertEquals(
            NtfyFrame.Message(id = "c3", title = "Nexus", body = "Hei på deg"),
            parseNtfyFrame("""{"id":"c3","time":3,"event":"message","topic":"t","title":"Nexus","message":"Hei på deg"}"""),
        )
        assertEquals(
            NtfyFrame.Message(id = "c4", title = "", body = "no title"),
            parseNtfyFrame("""{"id":"c4","event":"message","message":"no title"}"""),
        )
        assertEquals(NtfyFrame.Other, parseNtfyFrame("""{"event":"poll_request"}"""))
        assertEquals(NtfyFrame.Other, parseNtfyFrame("""{"event":"message","message":"no id"}"""))
        assertEquals(NtfyFrame.Other, parseNtfyFrame("not json"))
    }

    @Test
    fun `what a push says is read the same whatever carried it`() {
        assertEquals(NexusPush.Notification("Nexus", "Hei på deg"), decodeNexusPush("Nexus", "Hei på deg"))
        assertEquals(NexusPush.Nudge(listOf(12)), decodeNexusPush("", """{"nexus":"banner","v":"0123456789ab","jobs":[12]}"""))
    }

    @Test
    fun `a topic's address becomes its socket, asking for what came since the last message`() {
        assertEquals("ws://192.168.0.74:8087/nexus-abc/ws", pushSocketUrl("http://192.168.0.74:8087/nexus-abc", null))
        assertEquals(
            "wss://notifications.example.net/nexus-abc/ws?since=c3",
            pushSocketUrl(" https://notifications.example.net/nexus-abc/ ", "c3"),
        )
        assertNull(pushSocketUrl("http://192.168.0.74:8087", null))
        assertNull(pushSocketUrl("http://192.168.0.74:8087/a/b", null))
        assertNull(pushSocketUrl("ftp://host/topic", null))
        assertNull(pushSocketUrl("", null))
    }

    @Test
    fun `the server's answer says how and where to listen and on what beat`() {
        assertEquals(
            PushSubscription("ntfy", "https://notifications.example.net/nexus-abc", 90),
            parsePushSubscription(
                """{"__className__":"PushSubscription","transport":"ntfy","url":"https://notifications.example.net/nexus-abc","keepaliveSeconds":90}""",
            ),
        )
        assertEquals("fcm", parsePushSubscription("""{"transport":"fcm","url":"x"}""")!!.transport)
        assertEquals(
            "a server from before the transport was named had only ntfy",
            "ntfy",
            parsePushSubscription("""{"url":"http://h/t","keepaliveSeconds":90}""")!!.transport,
        )
        assertEquals(300, parsePushSubscription("""{"url":"http://h/t","keepaliveSeconds":300}""")!!.keepaliveSeconds)
        assertEquals(90, parsePushSubscription("""{"url":"http://h/t"}""")!!.keepaliveSeconds)
        assertEquals(30, parsePushSubscription("""{"url":"http://h/t","keepaliveSeconds":1}""")!!.keepaliveSeconds)
        assertNull(parsePushSubscription("null"))
        assertNull(parsePushSubscription(""))
        assertNull(parsePushSubscription("""{"keepaliveSeconds":90}"""))
    }

    @Test
    fun `tries again quickly at first, then every two minutes`() {
        assertEquals(listOf(5, 10, 15, 30, 60, 120, 120), (1..7).map(::pushRetrySeconds))
        assertEquals(5, pushRetrySeconds(0))
    }

    @Test
    fun `counts what the connection did and how long it stood`() {
        var clock = 0L
        val stats = PushStats { clock }
        clock = 10_000
        stats.opened()
        clock = 310_000
        stats.keepalive()
        clock = 400_000
        stats.message()
        clock = 500_000
        stats.lost("SocketTimeoutException")
        clock = 505_000
        stats.opened()
        clock = 605_000

        assertEquals(
            listOf(
                "push since=605s connected=true connectedFor=590s",
                "push opens=2 failures=1 keepalives=1 messages=1",
                "push lastEvent=100s ago lastFailure=SocketTimeoutException",
            ),
            stats.lines(),
        )
    }
}
