package app.lawnchair.nexus

import app.lawnchair.nexus.search.NexusChatHit
import app.lawnchair.nexus.search.NexusNoteHit
import app.lawnchair.nexus.search.NexusSearchHits
import app.lawnchair.nexus.search.NexusSearchRow
import app.lawnchair.nexus.search.nexusSearchRow
import app.lawnchair.nexus.search.parseQuickSearch
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The Nexus row of the app drawer's search: the server's `QuickSearchResult` as the launcher reads it,
 * the row as it develops while the person types, and the app's route to a chat it opens.
 */
class NexusSearchTest {
    @Test
    fun `reads notes, chats and news, skipping a hit it could not open`() {
        val hits = parseQuickSearch(
            """
            {"notes":[{"path":"Prosjekter/Plan.md","title":"Plan","snippet":"om planen","updatedAt":"2026-11-13T10:00:00.000Z"},
                      {"title":"no path"}],
             "chats":[{"conversationId":7,"title":"Middag","snippet":null,"agentId":"coder/generalist","lastActivity":"2026-11-12T18:00:00.000Z"},
                      {"conversationId":8,"title":""}],
             "news":[{"id":3,"sourceName":"NRK","url":"https://nrk.no/a","title":"Strømstøtten","synopsis":"Kort.","tags":[]},
                     {"id":4}]}
            """.trimIndent(),
        )!!
        assertEquals(
            listOf(NexusNoteHit("Prosjekter/Plan.md", "Plan", "om planen", Instant.parse("2026-11-13T10:00:00Z"))),
            hits.notes,
        )
        assertEquals(
            listOf(NexusChatHit(7, "Middag", null, "coder/generalist", Instant.parse("2026-11-12T18:00:00Z"))),
            hits.chats,
        )
        assertEquals(listOf(3L), hits.news.map { it.id })
    }

    @Test
    fun `a missing list is empty, and what is not an object is no answer`() {
        assertEquals(NexusSearchHits.EMPTY, parseQuickSearch("{}"))
        assertNull(parseQuickSearch("[]"))
        assertNull(parseQuickSearch("not json"))
    }

    @Test
    fun `a chat opens at its route, its title and agent encoded`() {
        assertEquals("/chat?c=7&title=Middag+%26+mer", chatRoute(7, "Middag & mer", null))
        assertEquals("/chat?c=7&title=X&agent=coder%2Fgeneralist", chatRoute(7, "X", "coder/generalist"))
    }

    private val signedIn = NexusStatus(
        installed = true,
        configured = true,
        serverUrl = "http://box.test:8080/",
        presenceOnline = true,
        presenceEpochMs = 0,
        whisperReady = true,
        ttsReady = true,
        llmReady = true,
        signedIn = true,
        onboarded = true,
        authKey = "key",
    )

    private val found = NexusSearchHits(
        notes = listOf(NexusNoteHit("Plan.md", "Plan", null, null)),
        chats = emptyList(),
        news = emptyList(),
    )

    private class Answers(private val answer: () -> NexusSearchHits) : StubNexusTransport() {
        val asked = mutableListOf<String>()

        override suspend fun quickSearch(base: String, token: String, query: String, limit: Int): NexusSearchHits {
            asked += query
            return answer()
        }
    }

    private fun rows(query: String, status: NexusStatus, transport: Answers): List<NexusSearchRow?> =
        runBlocking { nexusSearchRow(query, { status }, transport).toList() }

    @Test
    fun `the Ask card with ghosts comes first, then what the server found`() {
        val transport = Answers { found }
        assertEquals(
            listOf(
                NexusSearchRow("plan", NexusSearchHits.EMPTY, searching = true),
                NexusSearchRow("plan", found, searching = false),
            ),
            rows("  plan ", signedIn, transport),
        )
        assertEquals(listOf("plan"), transport.asked)
    }

    @Test
    fun `one letter is the Ask card alone, and the server is not asked`() {
        val transport = Answers { found }
        assertEquals(listOf(NexusSearchRow("p", NexusSearchHits.EMPTY, searching = false)), rows("p", signedIn, transport))
        assertEquals(emptyList<String>(), transport.asked)
    }

    @Test
    fun `a search that fails settles on no hits, keeping the row`() {
        val transport = Answers { throw IOException("offline") }
        assertEquals(
            NexusSearchRow("plan", NexusSearchHits.EMPTY, searching = false),
            rows("plan", signedIn, transport).last(),
        )
    }

    @Test
    fun `there is no row signed out, nor for no words`() {
        val transport = Answers { found }
        assertEquals(listOf<NexusSearchRow?>(null), rows("plan", signedIn.copy(signedIn = false), transport))
        assertEquals(listOf<NexusSearchRow?>(null), rows("   ", signedIn, transport))
        assertEquals(emptyList<String>(), transport.asked)
    }
}
