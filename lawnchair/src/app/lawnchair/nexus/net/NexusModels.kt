package app.lawnchair.nexus.net

/**
 * Readiness/liveness snapshot returned by `POST /presence {"method":"heartbeat"}`.
 * Mirrors the server's `PresenceAck` serializable.
 */
data class PresenceAck(
    val ok: Boolean,
    val serverTime: String?,
    val whisperReady: Boolean,
    val ttsReady: Boolean,
    val ollamaReady: Boolean,
    val allReady: Boolean,
)

/**
 * One streamed value from a `streamTurn` method-stream, already unwrapped from the
 * Serverpod `msm` → `data.o.data` envelope.
 *
 * Every event carries the server's [turnId] (`turn-<micros>`) so the caller can drop events left
 * over from a barged-in/cancelled turn (the `streamTurn` socket is long-lived and survives across
 * turns, so stale events keep flowing until the server notices the cancel). `heartbeat` events are
 * still dropped in the parser; `userPromptRequest` (clarification) is out of scope for now.
 */
sealed interface NexusTurnEvent {
    /** The server turn this event belongs to; null on frames with no turn context. */
    val turnId: String?

    /** Append [text] to the running answer. */
    data class TextDelta(val text: String, override val turnId: String? = null) : NexusTurnEvent

    /** Narrator pre-roll text (short "thinking out loud" line). */
    data class Narrator(
        val text: String,
        val narratorKind: String? = null,
        val audible: Boolean? = null,
        val toolName: String? = null,
        override val turnId: String? = null,
    ) : NexusTurnEvent

    /** Web/search citations for the answer. */
    data class Sources(
        val sources: List<NexusSource>,
        override val turnId: String? = null,
    ) : NexusTurnEvent

    /** A chunk of spoken TTS audio — one sentence of Ogg/Opus, base64-decoded to [opusBytes]. */
    data class Audio(
        val opusBytes: ByteArray,
        val segmentIndex: Int,
        val durationMs: Int,
        val isFinal: Boolean,
        override val turnId: String? = null,
    ) : NexusTurnEvent {
        // ByteArray needs structural equals/hashCode (data classes use identity for arrays).
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Audio) return false
            return segmentIndex == other.segmentIndex &&
                durationMs == other.durationMs &&
                isFinal == other.isFinal &&
                turnId == other.turnId &&
                opusBytes.contentEquals(other.opusBytes)
        }

        override fun hashCode(): Int {
            var result = opusBytes.contentHashCode()
            result = 31 * result + segmentIndex
            result = 31 * result + durationMs
            result = 31 * result + isFinal.hashCode()
            result = 31 * result + (turnId?.hashCode() ?: 0)
            return result
        }
    }

    /** Terminal — the turn finished successfully. */
    data class Completed(override val turnId: String? = null) : NexusTurnEvent

    /** Terminal — the turn failed (or the socket/omsr rejected it). */
    data class Failed(val message: String, override val turnId: String? = null) : NexusTurnEvent
}

/** A single citation from a `searchSources` event. */
data class NexusSource(
    val title: String,
    val url: String,
)

/**
 * One value from the STT (`audio.sendAudioChunk`) stream, reduced to what the caller needs.
 * Partial transcripts arrive with [isFinal] false; the accepted final transcript has [isFinal] true.
 * Server `error` events surface as [isError] with an optional [errorMessage].
 */
data class TranscriptionEvent(
    val text: String,
    val isFinal: Boolean,
    val isError: Boolean = false,
    val errorMessage: String? = null,
)

/**
 * A decoded TTS audio segment queued for playback — the launcher-side analogue of the mobile
 * `AudioSegment`. [bytes] is a complete Ogg/Opus (or, in simulation, WAV) container.
 */
data class AudioSegment(
    val bytes: ByteArray,
    val segmentIndex: Int,
    val durationMs: Int,
    val isFinal: Boolean,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AudioSegment) return false
        return segmentIndex == other.segmentIndex &&
            durationMs == other.durationMs &&
            isFinal == other.isFinal &&
            bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var result = bytes.contentHashCode()
        result = 31 * result + segmentIndex
        result = 31 * result + durationMs
        result = 31 * result + isFinal.hashCode()
        return result
    }
}
