package app.lawnchair.nexus

import app.lawnchair.nexus.buds.BudsAction
import app.lawnchair.nexus.buds.NexusMicPrefs
import app.lawnchair.nexus.buds.NexusReadyCues
import app.lawnchair.nexus.buds.speechLanguageOf
import app.lawnchair.nexus.buds.SilenceEndDetector
import app.lawnchair.nexus.buds.Tap
import app.lawnchair.nexus.buds.appTakesTap
import app.lawnchair.nexus.buds.applyGain
import app.lawnchair.nexus.buds.budsAction
import app.lawnchair.nexus.buds.micGain
import app.lawnchair.nexus.chat.TurnPhase
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test

/** The earbud double-tap path: the mic settings it reads, when a turn ends, and what a tap does. */
class NexusBudsTest {

    // region the Microphone settings the app saves

    private fun prefs(vararg entries: Pair<String, Any?>): NexusMicPrefs {
        val map = entries.toMap()
        return NexusMicPrefs.from { key -> map[key] }
    }

    @Test
    fun `nothing saved is the app's defaults`() {
        val p = prefs()
        assertEquals("auto", p.source)
        assertEquals(50, p.sensitivity)
        assertEquals("balanced", p.vad)
        assertFalse(p.noiseSuppression)
        assertFalse(p.echoCancel)
        assertFalse(p.boost)
        assertTrue("on unless switched off", p.earbudTrigger)
    }

    @Test
    fun `the double tap switched off in the app is off here`() {
        assertFalse(prefs("flutter.settings.mic.earbudTrigger" to false).earbudTrigger)
    }

    @Test
    fun `the saved values are read under the keys the app writes them`() {
        val p = prefs(
            "flutter.settings.mic.source" to "phone",
            "flutter.settings.mic.sensitivityV2" to 80L, // shared_preferences stores an int as a Long
            "flutter.settings.mic.vad" to "sensitive",
            "flutter.settings.mic.noiseSuppressionV2" to true,
            "flutter.settings.mic.echoCancelV2" to true,
            "flutter.settings.mic.boostV2" to true,
            "flutter.settings.mic.earbudTrigger" to true,
        )
        assertEquals("phone", p.source)
        assertEquals(80, p.sensitivity)
        assertEquals("sensitive", p.vad)
        assertTrue(p.noiseSuppression && p.echoCancel && p.boost && p.earbudTrigger)
    }

    @Test
    fun `a value of the wrong type is the default, not a crash`() {
        val p = prefs("flutter.settings.mic.sensitivityV2" to "loud", "flutter.settings.mic.boostV2" to 1L)
        assertEquals(50, p.sensitivity)
        assertFalse(p.boost)
    }

    @Test
    fun `the pickup level is the same gain here as in the app`() {
        assertEquals(1.0, micGain(50), 1e-9)
        assertEquals(2.0, micGain(75), 1e-9)
        assertEquals(4.0, micGain(100), 1e-9)
        assertEquals(0.25, micGain(0), 1e-9)
        assertEquals(micGain(0), micGain(-5), 1e-9)
        assertEquals(micGain(100), micGain(500), 1e-9)
    }

    @Test
    fun `the end-of-speech setting is how long a pause may be, or none`() {
        assertEquals(800L, prefs("flutter.settings.mic.vad" to "sensitive").silenceMs)
        assertEquals(1400L, prefs("flutter.settings.mic.vad" to "balanced").silenceMs)
        assertNull(prefs("flutter.settings.mic.vad" to "conservative").silenceMs)
        assertEquals(1400L, prefs("flutter.settings.mic.vad" to "nonsense").silenceMs)
    }

    // endregion

    // region gain on the samples

    private fun pcm(vararg samples: Int): ByteArray {
        val out = ByteArray(samples.size * 2)
        samples.forEachIndexed { i, s ->
            out[i * 2] = (s and 0xFF).toByte()
            out[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
        }
        return out
    }

    @Test
    fun `unity gain hands back the same bytes`() {
        val bytes = pcm(1000, -1000)
        assertSame(bytes, applyGain(bytes, bytes.size, 1.0))
    }

    @Test
    fun `gain scales the samples and clips instead of wrapping`() {
        assertArrayEquals(pcm(2000, -4000), applyGain(pcm(1000, -2000), 4, 2.0))
        assertArrayEquals(pcm(32767, -32768), applyGain(pcm(30000, -30000), 4, 4.0))
    }

    @Test
    fun `only the bytes that were read are scaled`() {
        val buffer = pcm(1000, 1000)
        val out = applyGain(buffer, 2, 2.0)
        assertEquals(2, out.size)
        assertArrayEquals(pcm(2000), out)
    }

    // endregion

    // region when a hands-free turn ends

    @Test
    fun `a pause after speech ends the turn`() {
        val d = SilenceEndDetector(silenceMs = 1_000, noSpeechMs = 8_000, maxMs = 60_000, threshold = 0.1f)
        assertFalse(d.onLevel(0.02f, 0))
        assertFalse(d.onLevel(0.4f, 500)) // speech
        assertFalse(d.onLevel(0.4f, 900))
        assertFalse(d.onLevel(0.02f, 1_500)) // 600 ms of quiet: not yet
        assertTrue(d.onLevel(0.02f, 1_950)) // 1050 ms
    }

    @Test
    fun `speech again before the pause is over starts the count again`() {
        val d = SilenceEndDetector(silenceMs = 1_000, noSpeechMs = 8_000, maxMs = 60_000, threshold = 0.1f)
        d.onLevel(0.4f, 0)
        assertFalse(d.onLevel(0.02f, 900))
        assertFalse(d.onLevel(0.4f, 950))
        assertFalse(d.onLevel(0.02f, 1_900))
        assertTrue(d.onLevel(0.02f, 2_000))
    }

    @Test
    fun `quiet before anyone has spoken ends it only at the no-speech limit`() {
        val d = SilenceEndDetector(silenceMs = 1_000, noSpeechMs = 8_000, maxMs = 60_000, threshold = 0.1f)
        assertFalse(d.onLevel(0.01f, 7_900))
        assertTrue(d.onLevel(0.01f, 8_000))
    }

    @Test
    fun `without a pause length only the longest turn ends it`() {
        val d = SilenceEndDetector(silenceMs = null, noSpeechMs = 8_000, maxMs = 60_000, threshold = 0.1f)
        d.onLevel(0.4f, 0)
        assertFalse(d.onLevel(0.01f, 30_000))
        assertTrue(d.onLevel(0.01f, 60_000))
    }

    // endregion

    // region who takes a tap

    @Test
    fun `the app takes a double tap when its Home screen is in front, and the background does not`() {
        assertTrue(appTakesTap(Tap.Double, appResumed = true, homeReady = true, busy = false))
        assertFalse(appTakesTap(Tap.Double, appResumed = false, homeReady = true, busy = false))
        assertFalse(appTakesTap(Tap.Double, appResumed = true, homeReady = false, busy = false))
    }

    @Test
    fun `a single tap is the app's only while a turn is going`() {
        assertFalse(appTakesTap(Tap.Single, appResumed = true, homeReady = true, busy = false))
        assertTrue(appTakesTap(Tap.Single, appResumed = true, homeReady = true, busy = true))
    }

    // endregion

    // region what a tap on the earbuds does

    @Test
    fun `a double tap starts a turn when nothing is going on`() {
        for (phase in listOf(TurnPhase.Idle, TurnPhase.Done, TurnPhase.Failed)) {
            assertEquals(phase.name, BudsAction.StartListening, budsAction(Tap.Double, phase))
        }
    }

    @Test
    fun `a double tap while listening sends what was said`() {
        assertEquals(BudsAction.FinishListening, budsAction(Tap.Double, TurnPhase.Listening))
    }

    @Test
    fun `a double tap while Nexus works or speaks cuts in and listens`() {
        assertEquals(BudsAction.InterruptAndListen, budsAction(Tap.Double, TurnPhase.Thinking))
        assertEquals(BudsAction.InterruptAndListen, budsAction(Tap.Double, TurnPhase.Speaking))
    }

    @Test
    fun `a double tap answers a question Nexus asked`() {
        assertEquals(BudsAction.StartListening, budsAction(Tap.Double, TurnPhase.AwaitingAnswer))
    }

    @Test
    fun `a single tap stops a turn that is going, and is left to the music app otherwise`() {
        for (phase in listOf(TurnPhase.Listening, TurnPhase.Thinking, TurnPhase.Speaking)) {
            assertEquals(phase.name, BudsAction.Cancel, budsAction(Tap.Single, phase))
        }
        for (phase in listOf(TurnPhase.Idle, TurnPhase.Done, TurnPhase.Failed, TurnPhase.AwaitingAnswer)) {
            assertEquals(phase.name, BudsAction.Ignore, budsAction(Tap.Single, phase))
        }
    }

    // endregion

    // region the spoken line for the earbuds

    @Test
    fun `the Language setting is the code speech is told in`() {
        assertEquals("no", speechLanguageOf("Norsk"))
        assertEquals("en", speechLanguageOf("English"))
        assertNull(speechLanguageOf("Automatic"))
        assertNull(speechLanguageOf(null))
        assertNull(speechLanguageOf("English (US)"))
    }

    @Test
    fun `a line fetched ahead is there at once, and the next is fetched behind it`() = runBlocking {
        var asks = 0
        val cues = NexusReadyCues(fetch = { asks++; byteArrayOf(asks.toByte()) }, language = { "no" })
        cues.prefetch().join()
        assertArrayEquals(byteArrayOf(1), cues.take(waitMs = 0))
        kotlinx.coroutines.delay(50)
        assertEquals("the next one is on its way", 2, asks)
    }

    @Test
    fun `a line that is too slow is not waited for and is there next time`() = runBlocking {
        val gate = kotlinx.coroutines.CompletableDeferred<ByteArray?>()
        val cues = NexusReadyCues(fetch = { gate.await() }, language = { null })
        assertNull(cues.take(waitMs = 30))
        gate.complete(byteArrayOf(9))
        kotlinx.coroutines.delay(50)
        assertArrayEquals(byteArrayOf(9), cues.take(waitMs = 0))
    }

    @Test
    fun `a server that has no such line is asked once, not on every tap`() = runBlocking {
        var asks = 0
        val cues = NexusReadyCues(fetch = { asks++; throw java.io.IOException("404") }, language = { null })
        assertNull(cues.take(waitMs = 50))
        assertNull(cues.take(waitMs = 50))
        assertEquals(1, asks)
    }

    // endregion
}
