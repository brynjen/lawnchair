package app.lawnchair.nexus.chat

import app.lawnchair.nexus.net.AudioSegment
import app.lawnchair.nexus.net.NexusSource
import app.lawnchair.nexus.net.NexusTransport
import app.lawnchair.nexus.net.NexusTurnEvent
import app.lawnchair.nexus.voice.NexusAudioCapture
import app.lawnchair.nexus.voice.NexusTtsPlayer
import app.lawnchair.nexus.voice.TokenEnvelope
import app.lawnchair.nexus.voice.TtsEnvelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.max

/** Which entry the overlay was opened in. */
enum class Mode { Text, Voice }

/** The per-turn lifecycle. */
enum class TurnPhase { Idle, Listening, Thinking, Speaking, Done, Failed }

/** How voice mode was entered — drives the stop-button label (tap-to-stop vs release-to-send). */
enum class MicGesture { None, Tap, Hold }

data class ChatLine(val fromUser: Boolean, val text: String)

data class NexusChatUiState(
    val mode: Mode,
    val phase: TurnPhase = TurnPhase.Idle,
    val messages: List<ChatLine> = emptyList(),
    val input: String = "",
    val userTranscript: String = "",
    val sources: List<NexusSource> = emptyList(),
    val micGesture: MicGesture = MicGesture.None,
    val failureSignal: Int = 0,
    val errorMessage: String? = null,
    /** True once dismissal starts; the UI runs its exit animation, then calls back to detach. */
    val dismissing: Boolean = false,
)

/**
 * The Nexus chat/voice state machine, held by [NexusChatOverlay] outside any composition so its
 * non-Compose resources (mic capture, TTS player, OkHttp streams) survive recomposition and IME
 * relayout. The Kotlin analogue of the mobile `VoiceSessionRepositoryImpl` + bloc, reduced to the
 * launcher's needs (no clarification/ask_user; barge-in is a simple cancel-and-relisten).
 *
 * Drives one [NexusTransport] — the real [app.lawnchair.nexus.net.NexusClient] or a fake — so it
 * works identically with the server down.
 */
class NexusChatController(
    private val transport: NexusTransport,
    private val serverUrl: String,
    private val token: String,
    initialMode: Mode,
    initialGesture: MicGesture = MicGesture.None,
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(
        NexusChatUiState(mode = initialMode, micGesture = initialGesture),
    )
    val state: StateFlow<NexusChatUiState> = _state.asStateFlow()

    private val tokenEnvelope = TokenEnvelope()
    private val ttsEnvelope = TtsEnvelope()

    private var conversationId: Int? = null
    private var currentTurnId: String? = null
    private var answerIndex: Int = -1

    private var capture: NexusAudioCapture? = null
    private var ttsPlayer: NexusTtsPlayer? = null
    private var turnJob: Job? = null
    private var listenJob: Job? = null
    private var watchdogJob: Job? = null

    private val busy: Boolean
        get() = _state.value.phase in setOf(TurnPhase.Listening, TurnPhase.Thinking, TurnPhase.Speaking)

    /**
     * Live amplitude for the orb's `externalAmplitude`. Invoked per frame by the orb (not via
     * recomposition): mic loudness while Listening, the TTS/token envelope while Speaking, else null
     * (the orb falls back to its synthetic curves).
     */
    fun externalAmplitude(): Float? = when (_state.value.phase) {
        TurnPhase.Listening -> capture?.amplitude?.value
        TurnPhase.Speaking -> max(ttsEnvelope.level(), tokenEnvelope.level())
        else -> null
    }

    // region text mode

    fun onInput(text: String) = _state.update { it.copy(input = text) }

    fun send() {
        val query = _state.value.input.trim()
        if (query.isEmpty() || busy) return
        _state.update { it.copy(input = "", sources = emptyList()) }
        submitUserTurn(query)
    }

    // endregion

    // region voice mode

    /** Begin recording (permission already ensured by the caller). */
    fun startListening() {
        if (busy) return
        val turnId = newTurnId()
        currentTurnId = turnId
        val recordingId = "rec-$turnId"
        val sessionId = conversationId?.toString() ?: "pending"
        _state.update { it.copy(phase = TurnPhase.Listening, userTranscript = "", errorMessage = null) }

        val cap = NexusAudioCapture()
        capture = cap
        armWatchdog(TurnPhase.Listening, 45_000, "No speech detected.")

        listenJob = scope.launch {
            runCatching {
                transport.streamTranscription(
                    base = serverUrl,
                    token = token,
                    recordingId = recordingId,
                    sessionId = sessionId,
                    pcm16Chunks = cap.start(),
                ).collect { ev ->
                    if (ev.isError) {
                        fail(ev.errorMessage ?: "Transcription failed.")
                        return@collect
                    }
                    if (!ev.isFinal) {
                        _state.update { it.copy(userTranscript = ev.text) }
                    } else {
                        _state.update { it.copy(userTranscript = ev.text) }
                        val text = ev.text.trim()
                        if (text.isEmpty()) {
                            // Silence — nothing to send; return to idle.
                            reset(TurnPhase.Idle)
                        } else {
                            submitUserTurn(text)
                        }
                    }
                }
            }.onFailure { if (_state.value.phase == TurnPhase.Listening) fail(it.message ?: "Recording failed.") }
        }
    }

    /**
     * Stop the mic and let the final transcript come back — used by the on-screen stop button (Tap)
     * and by the QSB release (Hold). Does NOT cancel the STT collection; the final transcript still
     * needs to arrive on it.
     */
    fun endRecordingAndSend() {
        if (_state.value.phase != TurnPhase.Listening) return
        capture?.stop()
        cancelWatchdog()
        _state.update { it.copy(phase = TurnPhase.Thinking) } // transcribing → thinking
    }

    // endregion

    // region shared turn

    private fun submitUserTurn(text: String) {
        // Commit the user's line + an empty assistant line to stream into.
        _state.update {
            val msgs = it.messages + ChatLine(true, text) + ChatLine(false, "")
            it.copy(messages = msgs, phase = TurnPhase.Thinking, userTranscript = "")
        }
        answerIndex = _state.value.messages.lastIndex

        val turnId = newTurnId()
        currentTurnId = turnId
        val player = NexusTtsPlayer(scope, onSegmentStarted = { ttsEnvelope.bump() })
        ttsPlayer?.release()
        ttsPlayer = player
        armWatchdog(TurnPhase.Thinking, 120_000, "The assistant took too long.")

        turnJob = scope.launch {
            val cid = ensureConversation()
            if (cid == null) {
                fail("Couldn't reach Nexus.")
                return@launch
            }
            runCatching {
                transport.streamTurn(
                    base = serverUrl,
                    token = token,
                    conversationId = cid,
                    onReady = {
                        scope.launch {
                            runCatching { transport.submitTurn(serverUrl, token, cid, text, turnId) }
                        }
                    },
                ).collect { ev ->
                    // Drop events from a superseded turn (barge-in leaves stale frames flowing).
                    if (ev.turnId != null && currentTurnId != null && ev.turnId != currentTurnId) {
                        return@collect
                    }
                    onTurnEvent(ev, player)
                }
            }
            // Stream ended (completed / failed / socket close): let audio finish, then settle.
            player.markEndOfStream()
            player.awaitDrain()
            cancelWatchdog()
            if (_state.value.phase != TurnPhase.Failed) {
                _state.update { it.copy(phase = TurnPhase.Done) }
            }
        }
    }

    private fun onTurnEvent(ev: NexusTurnEvent, player: NexusTtsPlayer) {
        when (ev) {
            is NexusTurnEvent.TextDelta -> {
                tokenEnvelope.bump(ev.text.length)
                appendAnswer(ev.text)
                if (_state.value.phase == TurnPhase.Thinking) {
                    _state.update { it.copy(phase = TurnPhase.Speaking) }
                }
            }
            is NexusTurnEvent.Audio -> {
                player.enqueue(AudioSegment(ev.opusBytes, ev.segmentIndex, ev.durationMs, ev.isFinal))
                if (_state.value.phase != TurnPhase.Speaking) {
                    _state.update { it.copy(phase = TurnPhase.Speaking) }
                }
            }
            is NexusTurnEvent.Sources -> _state.update { it.copy(sources = ev.sources) }
            is NexusTurnEvent.Failed -> {
                bumpFailure()
                // Surface the message in the assistant bubble if it's still empty.
                setAnswerIfEmpty("⚠ ${ev.message}")
                _state.update { it.copy(phase = TurnPhase.Failed, errorMessage = ev.message) }
            }
            is NexusTurnEvent.Completed -> Unit // settled after drain
            is NexusTurnEvent.Narrator -> Unit // pre-roll narration — not shown in this UI
        }
    }

    // endregion

    // region lifecycle

    fun dismiss() {
        if (_state.value.dismissing) return
        _state.update { it.copy(dismissing = true) }
    }

    /** Release everything. Call after the exit animation, from the overlay. */
    fun close() {
        cancelWatchdog()
        capture?.stop()
        ttsPlayer?.release()
        scope.cancel()
    }

    // endregion

    // region helpers

    private suspend fun ensureConversation(): Int? {
        conversationId?.let { return it }
        return runCatching { transport.createConversation(serverUrl, token) }
            .getOrNull()
            ?.also { conversationId = it }
    }

    private fun appendAnswer(delta: String) {
        val i = answerIndex
        _state.update { s ->
            if (i !in s.messages.indices) return@update s
            val cur = s.messages[i]
            val msgs = s.messages.toMutableList()
            msgs[i] = cur.copy(text = cur.text + delta)
            s.copy(messages = msgs)
        }
    }

    private fun setAnswerIfEmpty(text: String) {
        val i = answerIndex
        _state.update { s ->
            if (i !in s.messages.indices) return@update s
            val cur = s.messages[i]
            if (cur.text.isNotEmpty()) return@update s
            val msgs = s.messages.toMutableList()
            msgs[i] = cur.copy(text = text)
            s.copy(messages = msgs)
        }
    }

    private fun bumpFailure() = _state.update { it.copy(failureSignal = it.failureSignal + 1) }

    private fun fail(message: String) {
        cancelWatchdog()
        capture?.stop()
        bumpFailure()
        _state.update { it.copy(phase = TurnPhase.Failed, errorMessage = message) }
    }

    private fun reset(phase: TurnPhase) {
        cancelWatchdog()
        capture?.stop()
        currentTurnId = null
        _state.update { it.copy(phase = phase, userTranscript = "") }
    }

    /**
     * Fail the turn if it's still in-flight ([busy]) after [timeoutMs]. Re-armed on each phase
     * transition (listening 45s → thinking/speaking 120s), so a normally-progressing turn always
     * cancels the previous timer before it can fire. [expectedPhase] documents the arming phase.
     */
    private fun armWatchdog(@Suppress("UNUSED_PARAMETER") expectedPhase: TurnPhase, timeoutMs: Long, message: String) {
        cancelWatchdog()
        watchdogJob = scope.launch {
            delay(timeoutMs)
            if (busy) fail(message)
        }
    }

    private fun cancelWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = null
    }

    private fun newTurnId(): String = "turn-${System.nanoTime() / 1000}"

    // endregion
}
