package app.lawnchair.nexus.chat

import app.lawnchair.nexus.net.NexusSource
import app.lawnchair.nexus.net.NexusTurnEvent

/** How far a tool call has come. */
enum class CallStatus { Running, Done, Failed }

/**
 * One part of the conversation as the overlay draws it, after nexus-chat's `chat_core` blocks
 * (`docs/anatomy.md` there): the person's turn, the answer, reasoning, a tool call, a notice, and the
 * overlay's own pictures and links that came with no call to put them under.
 */
sealed interface ChatBlock {
    val id: String
}

data class UserTurn(override val id: String, val text: String) : ChatBlock

data class AgentText(override val id: String, val markdown: String, val streaming: Boolean = false) : ChatBlock

data class Reasoning(override val id: String, val text: String, val streaming: Boolean = false) : ChatBlock

/** A tool call: [about] is what it was about (a query, a page), [reasoning] what led to it. */
data class ToolCall(
    override val id: String,
    val callId: String,
    val name: String,
    val about: String? = null,
    val args: Map<String, String> = emptyMap(),
    val status: CallStatus = CallStatus.Running,
    val summary: String? = null,
    val progress: String? = null,
    val sources: List<NexusSource> = emptyList(),
    val reasoning: List<String> = emptyList(),
) : ChatBlock {
    val live: Boolean get() = status == CallStatus.Running
    val failed: Boolean get() = status == CallStatus.Failed
    val title: String get() = callTitle(name, about, running = live)
}

data class Notice(override val id: String, val text: String, val error: Boolean = false) : ChatBlock

data class ImageBlock(override val id: String, val url: String) : ChatBlock

data class LinksBlock(override val id: String, val sources: List<NexusSource>) : ChatBlock

/**
 * The conversation so far, folded from what the person sent and the turn's [NexusTurnEvent]s the way
 * `chat_core`'s `ChatTranscript` folds its events: reasoning is bound to the call it led to, a call's
 * result and links go under it, and a failure fails the calls still running.
 */
data class NexusTranscript(val blocks: List<ChatBlock> = emptyList(), val nextId: Int = 0) {

    fun sent(text: String): NexusTranscript = settled().adding { UserTurn(it, text) }

    fun failed(message: String): NexusTranscript =
        settled(liveCalls = CallStatus.Failed).adding { Notice(it, message, error = true) }

    fun ended(): NexusTranscript = settled(liveCalls = CallStatus.Done)

    fun on(event: NexusTurnEvent): NexusTranscript = when (event) {
        is NexusTurnEvent.TextDelta -> text(event.text)
        is NexusTurnEvent.Narrator -> narrated(event)
        is NexusTurnEvent.Sources -> found(event.sources, event.toolCallId)
        is NexusTurnEvent.Image -> settled().adding { ImageBlock(it, event.url) }
        is NexusTurnEvent.UserPrompt ->
            if (event.question.isEmpty()) this else settled().adding { AgentText(it, event.question) }
        is NexusTurnEvent.Failed -> failed(event.message)
        is NexusTurnEvent.Completed -> ended()
        is NexusTurnEvent.Audio -> this
    }

    private fun narrated(event: NexusTurnEvent.Narrator): NexusTranscript = when (event.narratorKind) {
        "thinking", "reasoning" -> reasoning(event.text)
        "tool" -> event.toolName?.let { started(event.toolCallId.orEmpty(), it, event.text, event.toolArguments) } ?: this
        "toolResult" -> finished(callAt(event.toolCallId), event.toolFailed == true, event.toolResult)
        "toolProgress" -> updating(lastCall { it.live }) { it.copy(progress = event.text) }
        // Servers before the call ids end a call this way; with ids, `toolResult` has ended it.
        "toolEnd", "toolFailed" -> finished(
            lastCall { it.live && it.callId.isEmpty() && it.name == event.toolName },
            failed = event.narratorKind == "toolFailed" || event.text == "failed",
            summary = null,
        )
        else -> this
    }

    private fun text(delta: String): NexusTranscript {
        if (delta.isEmpty()) return this
        val last = blocks.lastOrNull()
        if (last is AgentText && last.streaming) return replacingLast(last.copy(markdown = last.markdown + delta))
        return settled().adding { AgentText(it, delta, streaming = true) }
    }

    private fun reasoning(delta: String): NexusTranscript {
        if (delta.isEmpty()) return this
        val last = blocks.lastOrNull()
        if (last is Reasoning && last.streaming) return replacingLast(last.copy(text = last.text + delta))
        return settled().adding { Reasoning(it, delta, streaming = true) }
    }

    private fun started(callId: String, name: String, detail: String, args: Map<String, String>): NexusTranscript {
        val settled = settled()
        val thought = settled.blocks.takeLastWhile { it is Reasoning }
        val about = detail.trim().ifEmpty { null } ?: args["query"] ?: args["url"]
        return settled.copy(blocks = settled.blocks.dropLast(thought.size)).adding { id ->
            ToolCall(
                id,
                callId = callId,
                name = name,
                about = about,
                args = args,
                reasoning = thought.map { (it as Reasoning).text }.filter { it.isNotBlank() },
            )
        }
    }

    private fun finished(index: Int, failed: Boolean, summary: String?): NexusTranscript = updating(index) {
        it.copy(status = if (failed) CallStatus.Failed else CallStatus.Done, summary = summary ?: it.summary)
    }

    private fun found(sources: List<NexusSource>, callId: String?): NexusTranscript {
        if (sources.isEmpty()) return this
        val index = callAt(callId).takeIf { it >= 0 } ?: lastCall { it.name == "web_search" }
        if (index >= 0) return updating(index) { it.copy(sources = withoutRepeats(it.sources + sources)) }
        val last = blocks.lastOrNull()
        if (last is LinksBlock) return replacingLast(last.copy(sources = withoutRepeats(last.sources + sources)))
        return settled().adding { LinksBlock(it, withoutRepeats(sources)) }
    }

    private fun settled(liveCalls: CallStatus? = null): NexusTranscript = copy(
        blocks = blocks.map { block ->
            when {
                block is AgentText && block.streaming -> block.copy(streaming = false)
                block is Reasoning && block.streaming -> block.copy(streaming = false)
                block is ToolCall && block.live && liveCalls != null -> block.copy(status = liveCalls)
                else -> block
            }
        },
    )

    private fun adding(block: (String) -> ChatBlock): NexusTranscript =
        NexusTranscript(blocks + block("b$nextId"), nextId + 1)

    private fun replacingLast(block: ChatBlock): NexusTranscript = copy(blocks = blocks.dropLast(1) + block)

    private fun updating(index: Int, change: (ToolCall) -> ToolCall): NexusTranscript {
        val call = blocks.getOrNull(index) as? ToolCall ?: return this
        return copy(blocks = blocks.toMutableList().also { it[index] = change(call) })
    }

    private fun callAt(callId: String?): Int =
        if (callId.isNullOrEmpty()) -1 else lastCall { it.callId == callId }

    private fun lastCall(matches: (ToolCall) -> Boolean): Int =
        blocks.indexOfLast { it is ToolCall && matches(it) }
}

/** What the transcript draws, row by row: consecutive calls make one [RunRow]. */
sealed interface ChatRow {
    val key: String
}

data class BlockRow(val block: ChatBlock) : ChatRow {
    override val key: String get() = block.id
}

data class RunRow(val calls: List<ToolCall>) : ChatRow {
    override val key: String get() = "run-${calls.first().id}"
    val live: Boolean get() = calls.any { it.live }
    val failed: Boolean get() = calls.any { it.failed }
    val sources: List<NexusSource> get() = withoutRepeats(calls.flatMap { it.sources })

    /** The running call's title while one runs, else the run in a line ("Searched the web ×2, read a page"). */
    val title: String get() = calls.lastOrNull { it.live }?.title ?: workSummary(calls.map { it.name })
}

fun rowsOf(blocks: List<ChatBlock>): List<ChatRow> {
    val rows = mutableListOf<ChatRow>()
    for (block in blocks) {
        val last = rows.lastOrNull()
        when {
            block !is ToolCall -> rows += BlockRow(block)
            last is RunRow -> rows[rows.lastIndex] = RunRow(last.calls + block)
            else -> rows += RunRow(listOf(block))
        }
    }
    return rows
}

/** A call in a line: "Searched the web for “oslo trams”", "Read a page · nrk.no/x", "Searching the web for …" while it runs. */
fun callTitle(name: String, about: String?, running: Boolean = false): String {
    val (done, doing) = words(name)
    val verb = if (running) doing else done
    val what = about?.trim()
    if (what.isNullOrEmpty()) return verb
    return when (name) {
        "web_search", "site_search", "news_search" -> "$verb for “$what”"
        else -> "$verb · $what"
    }
}

/** The calls as one line, a clause for each kind in the order first seen: "Searched the web ×2, read a page". */
fun workSummary(names: List<String>): String {
    val clauses = names.groupingBy { it }.eachCount().map { (name, count) ->
        if (count == 1) words(name).first else "${words(name).first} ×$count"
    }
    val shown = if (clauses.size > 3) clauses.take(3) + "+${clauses.size - 3} more" else clauses
    return shown.mapIndexed { i, clause -> if (i == 0) clause else clause.replaceFirstChar { it.lowercase() } }
        .joinToString(", ")
}

/** A tool's step in plain words, done and doing (the phone's table); an unknown tool is its name, spaced. */
private fun words(name: String): Pair<String, String> = when (name) {
    "web_search" -> "Searched the web" to "Searching the web"
    "site_search" -> "Searched a site" to "Searching a site"
    "web_fetch", "extract_content" -> "Read a page" to "Reading a page"
    "news_search" -> "Searched the news" to "Searching the news"
    "news_read" -> "Read an article" to "Reading an article"
    "swarm_dispatch" -> "Started a background task" to "Starting a background task"
    "swarm_call" -> "Asked an agent" to "Asking an agent"
    "squad_memory" -> "Looked in memory" to "Looking in memory"
    "note_capture" -> "Wrote a note" to "Writing a note"
    "update_user_profile" -> "Updated what it knows of you" to "Updating what it knows of you"
    "image_generate" -> "Drew a picture" to "Drawing a picture"
    "image_edit" -> "Edited a picture" to "Editing a picture"
    else -> spaced(name).let { it to it }
}

private fun spaced(name: String): String {
    val words = name.replace(Regex("[_\\-.]+"), " ").trim()
    return if (words.isEmpty()) "Used a tool" else words.replaceFirstChar { it.uppercase() }
}

fun withoutRepeats(sources: List<NexusSource>): List<NexusSource> =
    sources.filter { it.url.isNotEmpty() }.distinctBy { it.url }

/** The link's host without `www.`. */
val NexusSource.site: String
    get() = host.ifEmpty { runCatching { java.net.URI(url).host }.getOrNull().orEmpty() }.removePrefix("www.")

val NexusSource.label: String get() = title.trim().ifEmpty { site }
