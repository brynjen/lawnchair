package app.lawnchair.nexus.net

import app.lawnchair.nexus.box.SubstrateFrame
import app.lawnchair.nexus.news.NewsFeed
import kotlinx.coroutines.flow.Flow

/**
 * The subset of the Nexus wire protocol the chat controller drives — the seam that lets a fake
 * implementation ([FakeNexusTransport]) stand in for the real [NexusClient] while the server is
 * unreachable, without the controller knowing the difference.
 *
 * [base] and [token] (the signed-in person's session key) are threaded through every call so a
 * single transport instance can serve any server (the real client is stateless; the fake ignores
 * them).
 */
interface NexusTransport {

    /** Create a conversation and return its id; with a [subject] it is about that article. */
    suspend fun createConversation(base: String, token: String, subject: ChatSubject? = null): Int

    /**
     * Fire the LLM turn. Fire-and-forget by contract: the server blocks for the whole LLM+TTS
     * pipeline (well past the ~20s RPC timeout), so callers must not await completion here — drive
     * completion off [streamTurn] instead. [turnId] correlates the resulting stream events. [voice]
     * false asks for a text reply with no speech; null leaves it to the server (voice, except in a
     * conversation about an article).
     */
    suspend fun submitTurn(
        base: String,
        token: String,
        conversationId: Int,
        userText: String,
        turnId: String,
        voice: Boolean? = null,
    )

    /**
     * Answer the question an [NexusTurnEvent.UserPrompt] asked. The waiting turn resumes on the
     * already-open [streamTurn]; no new turn starts.
     */
    suspend fun answerUserPrompt(base: String, token: String, conversationId: Int, promptId: String, answer: String)

    /** Cancel the in-flight turn (barge-in). [heardThroughSegmentIndex] = last segment the user heard. */
    suspend fun cancelTurn(base: String, token: String, conversationId: Int, heardThroughSegmentIndex: Int? = null)

    /**
     * Open the long-lived per-conversation turn stream. [onReady] fires once the server acknowledges
     * the stream so the caller can `submitTurn` only after the handshake. The flow completes on the
     * terminal `completed`/`failed` event.
     */
    fun streamTurn(
        base: String,
        token: String,
        conversationId: Int,
        onReady: () -> Unit = {},
    ): Flow<NexusTurnEvent>

    /**
     * Stream microphone PCM16 (16kHz mono) to whisper and emit transcription events. Consumes
     * [pcm16Chunks] to completion; when that flow ends, an end-of-stream sentinel is sent so the
     * server finalizes the transcript. Implements the chunk-0 gate internally.
     */
    fun streamTranscription(
        base: String,
        token: String,
        recordingId: String,
        sessionId: String,
        pcm16Chunks: Flow<ByteArray>,
        language: String? = null,
    ): Flow<TranscriptionEvent>

    /**
     * Subscribe to what the whole box is doing (`substrate.watch`): the slot table, one whole
     * snapshot per frame, the first right away. Completes or throws when the stream is lost; the
     * caller reconnects (see `boxOrbStates`).
     */
    fun watchBox(base: String, token: String): Flow<SubstrateFrame>

    /**
     * The news feed (`POST /news {"method":"feed","limit":n}`), newest first as the server orders it.
     * [section] is `now`, `ai` or `papers` (none: everything); [cursor] is a page's `nextCursor`, for
     * the page after it.
     */
    suspend fun newsFeed(base: String, token: String, limit: Int, cursor: String? = null, section: String? = null): NewsFeed

    /**
     * Ask the server to sweep its news sources now and return the feed (`POST /news
     * {"method":"refresh"}`). May take many seconds.
     */
    suspend fun newsRefresh(base: String, token: String): NewsFeed

    /**
     * A news picture's bytes: [path] is the server's own (`/uploads/system/news/<hash>.jpg`), fetched
     * with the session key like any upload. Throws for a path that is not this server's.
     */
    suspend fun newsImage(base: String, token: String, path: String): ByteArray
}

/**
 * The server refused the session key (HTTP 401): the person was signed out there, or the key was
 * revoked. Distinct from a network failure so the chat can say to sign in again rather than
 * "couldn't reach Nexus".
 */
class NexusUnauthorizedException : java.io.IOException("HTTP 401: the session is no longer valid")
