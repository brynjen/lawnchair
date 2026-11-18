package app.lawnchair.nexus

import app.lawnchair.nexus.chat.AgentText
import app.lawnchair.nexus.chat.BlockRow
import app.lawnchair.nexus.chat.CallStatus
import app.lawnchair.nexus.chat.LinksBlock
import app.lawnchair.nexus.chat.Notice
import app.lawnchair.nexus.chat.NexusTranscript
import app.lawnchair.nexus.chat.Reasoning
import app.lawnchair.nexus.chat.RunRow
import app.lawnchair.nexus.chat.ToolCall
import app.lawnchair.nexus.chat.UserTurn
import app.lawnchair.nexus.chat.callTitle
import app.lawnchair.nexus.chat.closeDanglingMarkdown
import app.lawnchair.nexus.chat.rowsOf
import app.lawnchair.nexus.chat.site
import app.lawnchair.nexus.chat.workSummary
import app.lawnchair.nexus.net.NexusClient
import app.lawnchair.nexus.net.NexusSource
import app.lawnchair.nexus.net.NexusTurnEvent
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The chat overlay's transcript: the turn events [NexusClient] reads off the stream, and how
 * [NexusTranscript] folds them (nexus-chat's docs/anatomy.md, "What the events say").
 */
class NexusTranscriptTest {

    private fun parse(json: String) = NexusClient().parseTurnEvent(JSONObject(json))

    private fun tool(id: String?, name: String, about: String = "", args: Map<String, String> = emptyMap()) =
        NexusTurnEvent.Narrator(about, narratorKind = "tool", toolName = name, toolCallId = id, toolArguments = args)

    private fun result(id: String, result: String, failed: Boolean = false) =
        NexusTurnEvent.Narrator("", narratorKind = "toolResult", toolCallId = id, toolResult = result, toolFailed = failed)

    private fun thinking(text: String) = NexusTurnEvent.Narrator(text, narratorKind = "thinking")

    private fun fold(vararg events: NexusTurnEvent) =
        events.fold(NexusTranscript().sent("Hi")) { transcript, event -> transcript.on(event) }

    @Test
    fun `a tool chunk is read with its call id, arguments and what it was about`() {
        val event = parse(
            """{"kind":"narratorChunk","narratorKind":"tool","toolName":"web_search","toolCallId":"c1",
               "toolArguments":"{\"query\":\"oslo trams\",\"count\":5}","narratorText":"oslo trams","audible":false,"turnId":"t"}""",
        )
        assertEquals(
            NexusTurnEvent.Narrator(
                "oslo trams",
                narratorKind = "tool",
                audible = false,
                toolName = "web_search",
                turnId = "t",
                toolCallId = "c1",
                toolArguments = mapOf("query" to "oslo trams", "count" to "5"),
            ),
            event,
        )
    }

    @Test
    fun `a tool result is read with its result line and whether it failed`() {
        val event = parse(
            """{"kind":"narratorChunk","narratorKind":"toolResult","toolName":"web_fetch","toolCallId":"c2",
               "toolResult":"HTTP 404","toolFailed":true}""",
        ) as NexusTurnEvent.Narrator
        assertEquals("c2", event.toolCallId)
        assertEquals("HTTP 404", event.toolResult)
        assertEquals(true, event.toolFailed)
    }

    @Test
    fun `search sources are read with their host and the call that found them`() {
        val event = parse(
            """{"kind":"searchSources","toolCallId":"c1","turnId":"t","sources":[
               {"title":"Trams","url":"https://www.ruter.no/trams","host":"ruter.no","snippet":"…"}]}""",
        )
        assertEquals(
            NexusTurnEvent.Sources(listOf(NexusSource("Trams", "https://www.ruter.no/trams", "ruter.no")), "t", "c1"),
            event,
        )
    }

    @Test
    fun `an older server's chunks read with the new fields empty`() {
        val event = parse("""{"kind":"narratorChunk","narratorKind":"tool","toolName":"web_search","narratorText":"x"}""")
            as NexusTurnEvent.Narrator
        assertEquals(null, event.toolCallId)
        assertEquals(emptyMap<String, String>(), event.toolArguments)
        assertEquals(null, event.toolFailed)
        val sources = parse("""{"kind":"searchSources","sources":[{"title":"A","url":"https://a.no"}]}""") as NexusTurnEvent.Sources
        assertEquals(null, sources.toolCallId)
        assertEquals("", sources.sources.single().host)
    }

    @Test
    fun `reasoning goes to the call it led to, and reasoning with no call after it is its own line`() {
        val transcript = fold(
            thinking("Need "),
            thinking("the timetable."),
            tool("c1", "web_search", "oslo trams"),
            result("c1", "5 results"),
            thinking("Enough."),
            NexusTurnEvent.TextDelta("The **tram** runs."),
            NexusTurnEvent.Completed(),
        )
        val blocks = transcript.blocks
        assertTrue(blocks[0] is UserTurn)
        val call = blocks[1] as ToolCall
        assertEquals(listOf("Need the timetable."), call.reasoning)
        assertEquals(CallStatus.Done, call.status)
        assertEquals("5 results", call.summary)
        assertEquals(Reasoning(blocks[2].id, "Enough."), blocks[2])
        assertEquals(AgentText(blocks[3].id, "The **tram** runs."), blocks[3])
    }

    @Test
    fun `consecutive calls fold to one run that reads as clauses with counts`() {
        val transcript = fold(
            tool("c1", "web_search", "oslo trams"),
            result("c1", "5 results"),
            thinking("And the other line."),
            tool("c2", "web_search", "bergen trams"),
            result("c2", "3 results"),
            tool("c3", "web_fetch", args = mapOf("url" to "nrk.no/x")),
            result("c3", "1 page"),
            NexusTurnEvent.TextDelta("Done."),
        )
        val rows = rowsOf(transcript.blocks)
        assertEquals(3, rows.size)
        val run = rows[1] as RunRow
        assertEquals(listOf("c1", "c2", "c3"), run.calls.map { it.callId })
        assertEquals("Searched the web ×2, read a page", run.title)
        assertEquals("Read a page · nrk.no/x", run.calls[2].title)
        assertTrue((rows[2] as BlockRow).block is AgentText)
    }

    @Test
    fun `a running call names the line in its live words`() {
        val transcript = fold(tool("c1", "web_search", "oslo trams"))
        val run = rowsOf(transcript.blocks)[1] as RunRow
        assertTrue(run.live)
        assertEquals("Searching the web for “oslo trams”", run.title)
        val progressed = transcript.on(NexusTurnEvent.Narrator("Reading 3 of 5", narratorKind = "toolProgress", toolName = "web_search"))
        assertEquals("Reading 3 of 5", (progressed.blocks[1] as ToolCall).progress)
    }

    @Test
    fun `links go under the call that found them, else the last web search, else on their own`() {
        val a = NexusSource("A", "https://a.no/1")
        val b = NexusSource("B", "https://b.no/1")
        val c = NexusSource("C", "https://c.no/1")
        val transcript = fold(
            tool("c1", "web_search", "one"),
            tool("c2", "web_fetch", "a.no/1"),
            NexusTurnEvent.Sources(listOf(a, a), toolCallId = "c2"),
            NexusTurnEvent.Sources(listOf(b)),
        )
        assertEquals(listOf(b), (transcript.blocks[1] as ToolCall).sources)
        assertEquals(listOf(a), (transcript.blocks[2] as ToolCall).sources)
        assertEquals(listOf(b, a), (rowsOf(transcript.blocks)[1] as RunRow).sources)

        val loose = fold(NexusTurnEvent.TextDelta("Hi"), NexusTurnEvent.Sources(listOf(c)))
        assertEquals(listOf(c), (loose.blocks.last() as LinksBlock).sources)
    }

    @Test
    fun `a failed call and a failed turn`() {
        val transcript = fold(
            tool("c1", "web_fetch", "x.no"),
            result("c1", "HTTP 404", failed = true),
            tool("c2", "web_search", "y"),
            NexusTurnEvent.Failed("The model is down."),
        )
        val run = rowsOf(transcript.blocks)[1] as RunRow
        assertEquals(listOf(CallStatus.Failed, CallStatus.Failed), run.calls.map { it.status })
        assertTrue(run.failed && !run.live)
        assertEquals("HTTP 404", run.calls[0].summary)
        assertEquals(Notice(transcript.blocks.last().id, "The model is down.", error = true), transcript.blocks.last())
    }

    @Test
    fun `an older server's calls end on toolEnd, and the turn's end settles the rest`() {
        val transcript = fold(
            tool(null, "web_search", "a"),
            tool(null, "web_fetch", "b"),
            NexusTurnEvent.Narrator("failed", narratorKind = "toolEnd", toolName = "web_search"),
            NexusTurnEvent.TextDelta("Hi"),
        )
        assertEquals(CallStatus.Failed, (transcript.blocks[1] as ToolCall).status)
        assertEquals(CallStatus.Running, (transcript.blocks[2] as ToolCall).status)
        assertTrue((transcript.blocks.last() as AgentText).streaming)

        val ended = transcript.on(NexusTurnEvent.Completed())
        assertEquals(CallStatus.Done, (ended.blocks[2] as ToolCall).status)
        assertTrue(!(ended.blocks.last() as AgentText).streaming)
    }

    @Test
    fun `with call ids, the old ending chunks change nothing`() {
        val transcript = fold(
            tool("c1", "web_search", "a"),
            tool("c2", "web_search", "b"),
            result("c1", "2 results"),
            NexusTurnEvent.Narrator("", narratorKind = "toolEnd", toolName = "web_search"),
        )
        assertEquals(CallStatus.Running, (transcript.blocks[2] as ToolCall).status)
    }

    @Test
    fun `titles and summaries in the phone's words`() {
        assertEquals("Searched the web for “oslo trams”", callTitle("web_search", "oslo trams"))
        assertEquals("Reading a page · nrk.no/x", callTitle("web_fetch", "nrk.no/x", running = true))
        assertEquals("Drew a picture", callTitle("image_generate", null))
        assertEquals("Calendar lookup", callTitle("calendar_lookup", null))
        assertEquals(
            "Searched the web, read a page ×2, looked in memory, +1 more",
            workSummary(listOf("web_search", "web_fetch", "web_fetch", "squad_memory", "note_capture")),
        )
        assertEquals("nrk.no", NexusSource("", "https://www.nrk.no/x").site)
    }

    @Test
    fun `an unclosed fence or backtick is closed while text streams`() {
        assertEquals("```kotlin\nval a = 1\n```", closeDanglingMarkdown("```kotlin\nval a = 1"))
        assertEquals("Use `ls`", closeDanglingMarkdown("Use `ls"))
        assertEquals("Use `ls` now", closeDanglingMarkdown("Use `ls` now"))
    }
}
