package app.lawnchair.nexus

import app.lawnchair.nexus.voice.HeadsetCandidate
import app.lawnchair.nexus.voice.pickHeadset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which Bluetooth headset a voice turn listens and speaks through. */
class NexusAudioRouteTest {

    private val le = HeadsetCandidate(id = 1, le = true, name = "Buds LE", address = "AA:BB:CC:00:00:01")
    private val sco = HeadsetCandidate(id = 2, le = false, name = "Buds", address = "AA:BB:CC:00:00:02")

    @Test
    fun `no headset is no choice`() {
        assertNull(pickHeadset(emptyList(), null))
    }

    @Test
    fun `LE Audio wins over the classic call profile`() {
        assertEquals(le, pickHeadset(listOf(sco, le), null))
    }

    @Test
    fun `the headset the person chose wins when it is connected`() {
        assertEquals(sco, pickHeadset(listOf(le, sco), "aa:bb:cc:00:00:02"))
    }

    @Test
    fun `a chosen headset that is not connected is no reason to use none`() {
        assertEquals(le, pickHeadset(listOf(sco, le), "FF:FF:FF:FF:FF:FF"))
    }

    @Test
    fun `the classic profile is used when it is all there is`() {
        assertEquals(sco, pickHeadset(listOf(sco), null))
    }
}
