package app.lawnchair.nexus

import app.lawnchair.nexus.push.PushEvent
import app.lawnchair.nexus.push.parsePushEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Nexus's one banner: the box on its line, a turn that is going on first, and the buttons that fit. */
class NexusBannerTest {

    private val clock: (Long) -> String = { "14:02" }
    private val idleBuds = NexusBanner.VoiceLine("Double-tap your earbuds to talk to Nexus", inTurn = false)
    private val listening = NexusBanner.PushLine(connectedAt = 1, received = 3)
    private val cancel = NexusBanner.BannerAction("cancelJob:12", "Cancel job…", "Cancel eval/findability?")
    private val max = NexusBanner.BoxLine(
        online = true,
        headline = "Max LLM · eval/findability since 13:10",
        lines = listOf("eval/findability has run for 52 min.", "Voice is off in Max LLM mode."),
        actions = listOf(cancel),
    )

    @Test
    fun `the line is the box, and pulled open the server's lines follow it`() {
        val text = bannerText(idleBuds, listening, max, clock)
        assertEquals("Online · Max LLM · eval/findability since 13:10", text.summary)
        assertEquals(
            listOf(
                "Online · Max LLM · eval/findability since 13:10",
                "eval/findability has run for 52 min.",
                "Voice is off in Max LLM mode.",
            ),
            text.lines,
        )
    }

    @Test
    fun `a server that says nothing of a mode is just online`() {
        assertEquals("Online", bannerText(null, listening, NexusBanner.BoxLine(online = true), clock).summary)
    }

    @Test
    fun `a server that does not answer is offline since then, with nothing old beneath it`() {
        val text = bannerText(null, listening, NexusBanner.BoxLine(online = false, since = 5), clock)
        assertEquals(BannerText("Offline since 14:02", listOf("Offline since 14:02")), text)
    }

    @Test
    fun `a turn that is going on takes the line, and the box stands beneath it`() {
        val text = bannerText(NexusBanner.VoiceLine("Listening… double-tap to send", inTurn = true), listening, max, clock)
        assertEquals("Listening… double-tap to send", text.summary)
        assertEquals("Online · Max LLM · eval/findability since 13:10", text.lines[1])
    }

    @Test
    fun `before the first answer it says only what is not up`() {
        assertEquals(
            BannerText("Notifications: connecting", listOf("Notifications: connecting")),
            bannerText(null, NexusBanner.PushLine(connectedAt = null, received = 0), null, clock),
        )
        assertEquals(BannerText("Nexus", listOf("Nexus")), bannerText(idleBuds, listening, null, clock))
    }

    @Test
    fun `the buttons are the server's, Check now when offline, and Stop first during a turn`() {
        assertEquals(listOf(BannerButton.Server(cancel)), bannerButtons(idleBuds, max))
        assertEquals(listOf(BannerButton.CheckNow), bannerButtons(null, NexusBanner.BoxLine(online = false, since = 5)))
        assertEquals(
            listOf(BannerButton.StopTurn, BannerButton.Server(cancel)),
            bannerButtons(NexusBanner.VoiceLine("Speaking…", inTurn = true), max),
        )
        assertEquals(emptyList<BannerButton>(), bannerButtons(idleBuds, null))
    }

    @Test
    fun `never more than three buttons`() {
        val many = max.copy(actions = List(4) { NexusBanner.BannerAction("a$it", "A$it", null) })
        assertEquals(3, bannerButtons(NexusBanner.VoiceLine("Speaking…", inTurn = true), many).size)
    }

    @Test
    fun `reads the server's banner`() {
        val state = parseBannerState(
            """{"__className__":"BannerState","version":"0123456789ab","headline":"Max LLM","lines":["Voice is off."],""" +
                """"actions":[{"id":"regular","label":"Back to Regular"},{"id":"cancelJob:12","label":"Cancel job…","confirm":"Cancel it?"}]}""",
        )
        assertEquals("Max LLM", state.headline)
        assertEquals(listOf("Voice is off."), state.lines)
        assertEquals(
            listOf(
                NexusBanner.BannerAction("regular", "Back to Regular", null),
                NexusBanner.BannerAction("cancelJob:12", "Cancel job…", "Cancel it?"),
            ),
            state.actions,
        )
        assertNull(state.notice)
    }

    @Test
    fun `reads a job's end, and nothing when it is not this person's`() {
        assertEquals(BannerNotice("eval is done", "passed 41 of 50"), parseBannerNotice("""{"title":"eval is done","text":"passed 41 of 50"}"""))
        assertNull(parseBannerNotice("null"))
    }

    @Test
    fun `the server's nudge is not a notification`() {
        val nudge = """{"id":"abc","event":"message","message":"{\"nexus\":\"banner\",\"v\":\"0123456789ab\",\"jobs\":[12]}"}"""
        assertEquals(PushEvent.Nudge("abc", listOf(12)), parsePushEvent(nudge))
        val plain = """{"id":"abd","event":"message","message":"{\"nexus\":\"banner\",\"v\":\"0123456789ab\"}"}"""
        assertEquals(PushEvent.Nudge("abd", emptyList()), parsePushEvent(plain))
        val unknown = """{"id":"abe","event":"message","message":"{\"nexus\":\"later\"}"}"""
        assertEquals(PushEvent.Other, parsePushEvent(unknown))
        val words = """{"id":"abf","event":"message","title":"Hi","message":"{not ours}"}"""
        assertEquals(PushEvent.Message("abf", "Hi", "{not ours}"), parsePushEvent(words))
    }
}
