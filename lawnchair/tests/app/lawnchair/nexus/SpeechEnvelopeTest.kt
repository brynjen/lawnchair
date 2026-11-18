package app.lawnchair.nexus

import app.lawnchair.nexus.voice.SpeechEnvelope
import app.lawnchair.nexus.voice.SpeechMotion
import app.lawnchair.nexus.voice.speechLevelFor
import app.lawnchair.nexuslauncher.speechBands
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The speaking orb's level from the voice itself, as the app's: "Sure! The weather in Oslo today
 * is mostly cloudy, ..." as nexus-talk spoke it in Ella's voice (Opus, 48 kHz, 20 ms packets,
 * 7.03 s; the same clip as `nexus_mobile/test/fixtures/speech/ella_weather.ogg`). Decoded, it is
 * loud for "Sure!" up to about 0.40 s, silent from about 0.42 to 0.68 s, and speech again from 0.70 s.
 */
class SpeechEnvelopeTest {

    private val clip = File("lawnchair/tests/fixtures/ella_weather.ogg").readBytes()

    private fun mean(envelope: SpeechEnvelope, fromMs: Int, toMs: Int): Double =
        (fromMs until toMs step 10).map { envelope.at(it.toDouble()).toDouble() }.average()

    @Test
    fun `reads every audio packet of a nexus-talk reply`() {
        assertEquals(352, SpeechEnvelope.fromOgg(clip)!!.size)
    }

    @Test
    fun `is loud where the voice speaks and quiet in its pause`() {
        val envelope = SpeechEnvelope.fromOgg(clip)!!
        assertTrue(mean(envelope, 460, 640) < 0.2)
        assertTrue(mean(envelope, 60, 380) > 0.5)
        assertTrue(mean(envelope, 760, 1500) > 0.5)
    }

    @Test
    fun `moves with the syllables, and is silent outside the clip`() {
        val envelope = SpeechEnvelope.fromOgg(clip)!!
        val levels = (760 until 6000 step 20).map { envelope.at(it.toDouble()).toDouble() }
        val mean = levels.average()
        assertTrue(levels.map { (it - mean) * (it - mean) }.average() > 0.02)
        assertEquals(0f, envelope.at(-50.0))
        assertEquals(0f, envelope.at(8000.0))
    }

    @Test
    fun `says nothing of what is not Ogg Opus`() {
        assertNull(SpeechEnvelope.fromOgg(ByteArray(0)))
        assertNull(SpeechEnvelope.fromOgg(ByteArray(64) { 7 }))
        assertNull(SpeechEnvelope.fromOgg(clip.copyOf(60)))
    }

    @Test
    fun `the motion is the app's, Voice when none is stored`() {
        assertEquals(SpeechMotion.VOICE, SpeechMotion.fromName(null))
        assertEquals(SpeechMotion.PUNCHY, SpeechMotion.fromName("punchy"))
        assertEquals(SpeechMotion.VOICE, SpeechMotion.fromName("something new"))
        assertEquals(0.96f, speechLevelFor(SpeechMotion.VOICE, 0.6f), 1e-5f)
        assertEquals(1.32f, speechLevelFor(SpeechMotion.LOUD, 0.6f), 1e-5f)
        assertTrue(speechLevelFor(SpeechMotion.PUNCHY, 0.3f) < speechLevelFor(SpeechMotion.VOICE, 0.3f))
    }

    @Test
    fun `a level over 1 drives the strongest bands to their top`() {
        val full = speechBands(1f, 1.3)
        val driven = speechBands(1.7f, 1.3)
        assertTrue(full.max() < 1f)
        assertEquals(1f, driven.max())
        assertTrue(driven.all { it in 0f..1f })
    }
}
