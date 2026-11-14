package app.lawnchair.nexus

import app.lawnchair.nexus.coder.WaitingQuestion
import app.lawnchair.nexus.coder.decidedAt
import app.lawnchair.nexus.coder.parseWaitingQuestions
import java.time.ZoneId
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The questions a person's coding agents wait on, as nexus lists them and the shade shows them. */
class CoderQuestionsTest {

    private val listed = JSONArray(
        """
        [
          {"__className__":"WaitingQuestion","sessionId":"s_1","sessionTitle":"Payroll export",
           "location":"cloud","workspacePath":"/workspace/u/payroll","questionId":"q_1",
           "question":"Keep the old column names?","options":["Keep them","Rename them"],
           "askedAt":"2026-11-14T15:00:00.000Z","timeoutSeconds":600,
           "context":{"__className__":"QuestionContext","request":"Export payroll as CSV."}},
          {"__className__":"WaitingQuestion","sessionId":"s_2","sessionTitle":"",
           "location":"mac","workspacePath":"/Users/me/app","questionId":"q_3",
           "question":"Which simulator?","options":[],
           "askedAt":"2026-11-14T15:01:00.000Z","timeoutSeconds":0},
          {"sessionId":"broken"}
        ]
        """.trimIndent(),
    )

    @Test
    fun `reads the host's list and leaves out what it cannot read`() {
        val (cloud, mac) = parseWaitingQuestions(listed)
        assertEquals(2, parseWaitingQuestions(listed).size)
        assertEquals("s_1/q_1", cloud.key)
        assertEquals(listOf("Keep them", "Rename them"), cloud.options)
        assertFalse(cloud.onMac)
        assertEquals(cloud.askedAtMs + 600_000, cloud.decidedAtMs)
        assertTrue(mac.onMac)
        assertNull(mac.decidedAtMs)
        assertEquals(emptyList<WaitingQuestion>(), parseWaitingQuestions(null))
    }

    @Test
    fun `a cloud question is answered on its page, with its recommendation and its time`() {
        val cloud = parseWaitingQuestions(listed)[0]
        assertEquals("Payroll export", cloud.title)
        assertEquals("Keep the old column names?", cloud.text)
        assertEquals("/code/s_1/question/q_1?alone=1", cloud.route)
        assertEquals(
            "Keep the old column names?\n\nRecommended: Keep them\n\nThe recommended answer is taken at 16:10.",
            cloud.details(ZoneId.of("Europe/Oslo")),
        )
    }

    @Test
    fun `a Mac question only says that the Mac waits`() {
        val mac = parseWaitingQuestions(listed)[1]
        assertEquals("Your agent", mac.title)
        assertEquals("Needs your answer on the Mac", mac.text)
        assertEquals("/code/s_2?alone=1", mac.route)
        assertTrue(mac.details().endsWith("Answer it on the Mac, where the conversation is."))
    }

    @Test
    fun `the time it is taken reads as a clock time where the phone is`() {
        val at = java.time.Instant.parse("2026-11-14T23:05:00Z").toEpochMilli()
        assertEquals("The recommended answer is taken at 00:05.", decidedAt(at, ZoneId.of("Europe/Oslo")))
        assertEquals("The recommended answer is taken at 23:05.", decidedAt(at, ZoneId.of("UTC")))
    }
}
