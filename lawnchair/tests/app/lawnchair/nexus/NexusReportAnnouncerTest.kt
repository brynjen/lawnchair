package app.lawnchair.nexus

import app.lawnchair.nexus.buds.NexusReportAnnouncer
import app.lawnchair.nexus.buds.ReportAction
import app.lawnchair.nexus.buds.reportAction
import app.lawnchair.nexus.net.AudioSegment
import app.lawnchair.nexus.net.NexusTurnEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NexusReportAnnouncerTest {
    @Test
    fun `earbuds in, the report is said whatever was asked`() {
        assertEquals(ReportAction.Speak, reportAction(requestWasSpoken = false, headsetConnected = true, appInFront = true))
        assertEquals(ReportAction.Speak, reportAction(requestWasSpoken = false, headsetConnected = true, appInFront = false))
        assertEquals(ReportAction.Speak, reportAction(requestWasSpoken = true, headsetConnected = true, appInFront = false))
    }

    @Test
    fun `no earbuds, a request that was spoken is answered aloud`() {
        assertEquals(ReportAction.Speak, reportAction(requestWasSpoken = true, headsetConnected = false, appInFront = true))
        assertEquals(ReportAction.Speak, reportAction(requestWasSpoken = true, headsetConnected = false, appInFront = false))
    }

    @Test
    fun `a typed request is answered in the session, and in a notification when the app is not in front`() {
        assertEquals(ReportAction.ShowInSession, reportAction(requestWasSpoken = false, headsetConnected = false, appInFront = true))
        assertEquals(ReportAction.Notify, reportAction(requestWasSpoken = false, headsetConnected = false, appInFront = false))
    }

    private class Run(headset: Boolean, front: Boolean) {
        val said = mutableListOf<Int>()
        var ended = 0
        val notified = mutableListOf<String>()
        val announcer = NexusReportAnnouncer(
            headsetConnected = { headset },
            appInFront = { front },
            speak = { said += it.segmentIndex },
            endSpeech = { ended++ },
            notify = { notified += it },
        )

        fun report(asked: String, id: String = "srv-1") {
            announcer.onEvent(NexusTurnEvent.Narrator(asked, narratorKind = "announce", audible = false, turnId = id))
            announcer.onEvent(NexusTurnEvent.TextDelta("Rapporten ", id))
            announcer.onEvent(NexusTurnEvent.TextDelta("er klar.", id))
            announcer.onEvent(NexusTurnEvent.Audio(byteArrayOf(1), 0, 900, false, id))
            announcer.onEvent(NexusTurnEvent.Audio(byteArrayOf(2), 1, 600, true, id))
            announcer.onEvent(NexusTurnEvent.Completed(id))
        }
    }

    @Test
    fun `a report said to earbuds plays every segment and ends the speech`() {
        val run = Run(headset = true, front = false)
        run.report("text")

        assertEquals(listOf(0, 1), run.said)
        assertEquals(1, run.ended)
        assertTrue(run.notified.isEmpty())
    }

    @Test
    fun `a typed request with no earbuds and the app away is a notification with the words`() {
        val run = Run(headset = false, front = false)
        run.report("text")

        assertTrue(run.said.isEmpty())
        assertEquals(listOf("Rapporten er klar."), run.notified)
    }

    @Test
    fun `a typed request with the app in front is left to the session`() {
        val run = Run(headset = false, front = true)
        run.report("text")

        assertTrue(run.said.isEmpty())
        assertTrue(run.notified.isEmpty())
        assertEquals(0, run.ended)
    }

    @Test
    fun `a spoken request with no earbuds is answered aloud`() {
        val run = Run(headset = false, front = false)
        run.report("voice")

        assertEquals(listOf(0, 1), run.said)
        assertTrue(run.notified.isEmpty())
    }

    @Test
    fun `a turn the person made is not a report`() {
        val run = Run(headset = true, front = false)
        run.report("voice", id = "turn-9")

        assertTrue(run.said.isEmpty())
        assertEquals(0, run.ended)
    }

    @Test
    fun `each report is decided on its own`() {
        val run = Run(headset = false, front = false)
        run.report("text", id = "srv-1")
        run.report("voice", id = "srv-2")

        assertEquals(listOf("Rapporten er klar."), run.notified)
        assertEquals(listOf(0, 1), run.said)
    }
}
