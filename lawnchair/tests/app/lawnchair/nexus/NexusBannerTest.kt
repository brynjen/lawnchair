package app.lawnchair.nexus

import org.junit.Assert.assertEquals
import org.junit.Test

/** The words of Nexus's one banner: what it is listening for, and a turn that is going on first. */
class NexusBannerTest {

    private val clock: (Long) -> String = { "11:19" }
    private val idleBuds = NexusBanner.VoiceLine("Double-tap your earbuds to talk to Nexus", inTurn = false)

    @Test
    fun `names everything Nexus listens for on one line, and each on its own when opened`() {
        val text = bannerText(idleBuds, NexusBanner.PushLine(connectedAt = 1, received = 3), clock)
        assertEquals("Notifications on · double-tap to talk", text.summary)
        assertEquals(
            listOf("Double-tap your earbuds to talk to Nexus", "Notifications: listening since 11:19, 3 received"),
            text.lines,
        )
    }

    @Test
    fun `a turn that is going on takes the line`() {
        val text = bannerText(
            NexusBanner.VoiceLine("Listening… double-tap to send", inTurn = true),
            NexusBanner.PushLine(connectedAt = 1, received = 0),
            clock,
        )
        assertEquals("Listening… double-tap to send", text.summary)
        assertEquals(listOf("Listening… double-tap to send", "Notifications: listening since 11:19"), text.lines)
    }

    @Test
    fun `says when the push connection is not up, and stands with one service alone`() {
        assertEquals(
            BannerText("Notifications connecting", listOf("Notifications: connecting")),
            bannerText(null, NexusBanner.PushLine(connectedAt = null, received = 0), clock),
        )
        assertEquals(
            BannerText("Double-tap to talk", listOf("Double-tap your earbuds to talk to Nexus")),
            bannerText(idleBuds, null, clock),
        )
    }
}
