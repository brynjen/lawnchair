package app.lawnchair.nexus

import app.lawnchair.nexus.cyber.CYBER_ROUTE
import app.lawnchair.nexus.cyber.parseWaitingCards
import app.lawnchair.nexus.push.NexusPush
import app.lawnchair.nexus.push.decodeNexusPush
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Test

/** The cards waiting on the household in nexus-cyber, as nexus lists them and the shade shows them. */
class CyberCardsTest {

    private val listed = JSONArray(
        """
        [
          {"id":"knownBadCall@162.243.103.246@1795467600000","title":"The box called a known-bad address",
           "device":"gpu-box","address":"162.243.103.246","segment":"Home LAN","severity":"high",
           "raisedAt":"2026-11-23T21:00:00.000Z","whatHappened":"…",
           "summary":"gpu-box called 162.243.103.246 (DigitalOcean :443), which Feodo Tracker lists.",
           "evidence":[],"proposed":"…","proposedShort":"…","undo":"…","whyYou":"…","outcome":"…"},
          {"id":"broken"}
        ]
        """.trimIndent(),
    )

    @Test
    fun `reads the host's list and leaves out what it cannot read`() {
        val card = parseWaitingCards(listed).single()
        assertEquals("knownBadCall@162.243.103.246@1795467600000", card.id)
        assertEquals("The box called a known-bad address", card.title)
        assertEquals("gpu-box · High", card.text)
        assertEquals(1795467600000, card.raisedAtMs)
        assertEquals(emptyList<Any>(), parseWaitingCards(null))
    }

    @Test
    fun `the server's nudge is not a notification, and a tap opens Cyber alone`() {
        assertEquals(NexusPush.CyberCards, decodeNexusPush("", """{"nexus":"cyber","v":"0123456789ab"}"""))
        assertEquals("/cyber?alone=1", CYBER_ROUTE)
    }
}
