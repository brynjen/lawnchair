package app.lawnchair.nexus

import app.lawnchair.nexus.coder.parseCoderReplies
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** What the person's coding agents answered, as nexus keeps it for the phone and the shade shows it. */
class CoderRepliesTest {

    private val kept = JSONArray(
        """
        [
          {"sessionId":"s 1","sessionTitle":"Payroll export","location":"mac",
           "workspacePath":"/Users/me/payroll","text":"\n\nExported payroll as CSV.\nThe old names stay.",
           "failed":false,"at":"2026-11-15T12:00:00.000Z"},
          {"sessionId":"s_2","sessionTitle":"","location":"cloud","workspacePath":"/workspace/u/app",
           "text":"","failed":true,"at":"2026-11-15T12:01:00.000Z"},
          {"sessionId":"broken"}
        ]
        """.trimIndent(),
    )

    @Test
    fun `reads the host's list and leaves out what it cannot read`() {
        val replies = parseCoderReplies(kept)
        assertEquals(2, replies.size)
        assertEquals(emptyList<Any>(), parseCoderReplies(null))
    }

    @Test
    fun `a reply shows its first line, all of it opened, and opens its session on its own`() {
        val reply = parseCoderReplies(kept)[0]
        assertEquals("Payroll export", reply.title)
        assertEquals("Exported payroll as CSV.", reply.line)
        assertEquals("Exported payroll as CSV.\nThe old names stay.", reply.details.trim())
        assertEquals("/code/s+1?alone=1", reply.route)
    }

    @Test
    fun `a run that failed with nothing said says so`() {
        val reply = parseCoderReplies(kept)[1]
        assertEquals("Your agent", reply.title)
        assertEquals("Stopped with an error", reply.line)
        assertEquals("Stopped with an error", reply.details)
    }

    @Test
    fun `the same session's next reply is another key`() {
        val first = parseCoderReplies(kept)[0]
        assertNotEquals(first.key, first.copy(atMs = first.atMs + 1).key)
    }
}
