package app.lawnchair.nexus.net

import kotlinx.coroutines.flow.Flow

/**
 * The subset of the Nexus wire protocol the chat controller drives — the seam that lets a fake
 * implementation ([FakeNexusTransport]) stand in for the real [NexusClient] while the server is
 * unreachable, without the controller knowing the difference.
 *
 * [base] and [token] are threaded through every call so a single transport instance can serve any
 * server (the real client is stateless; the fake ignores them).
 */
interface NexusTransport {

    /** Create a conversation and return its id. */
    suspend fun createConversation(base: String, token: String): Int

    /**
     * Fire the LLM turn. Fire-and-forget by contract: the server blocks for the whole LLM+TTS
     * pipeline (well past the ~20s RPC timeout), so callers must not await completion here — drive
     * completion off [streamTurn] instead. [turnId] correlates the resulting stream events.
     */
    suspend fun submitTurn(base: String, token: String, conversationId: Int, userText: String, turnId: String)

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
}
