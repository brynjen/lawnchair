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
 * Serverpod `msm` → `data.o.data` envelope and reduced to the kinds the text chat cares
 * about. `audio`/`heartbeat`/`userPromptRequest` kinds are intentionally not surfaced.
 */
sealed interface NexusTurnEvent {
    /** Append [text] to the running answer. */
    data class TextDelta(val text: String) : NexusTurnEvent

    /** Narrator pre-roll text (short "thinking out loud" line). */
    data class Narrator(val text: String) : NexusTurnEvent

    /** Web/search citations for the answer. */
    data class Sources(val sources: List<NexusSource>) : NexusTurnEvent

    /** Terminal — the turn finished successfully. */
    data object Completed : NexusTurnEvent

    /** Terminal — the turn failed (or the socket/omsr rejected it). */
    data class Failed(val message: String) : NexusTurnEvent
}

/** A single citation from a `searchSources` event. */
data class NexusSource(
    val title: String,
    val url: String,
)
