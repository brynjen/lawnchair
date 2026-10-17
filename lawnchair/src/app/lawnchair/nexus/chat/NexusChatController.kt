package app.lawnchair.nexus.chat

import android.os.SystemClock
import app.lawnchair.nexus.box.BoxOrbState
import app.lawnchair.nexus.box.boxOrbStates
import app.lawnchair.nexus.buds.NexusMicPrefs
import app.lawnchair.nexus.buds.SilenceEndDetector
import app.lawnchair.nexus.net.AudioSegment
import app.lawnchair.nexus.net.ChatSubject
import app.lawnchair.nexus.net.NexusClient
import app.lawnchair.nexus.net.NexusSource
import app.lawnchair.nexus.net.NexusTransport
import app.lawnchair.nexus.net.NexusTurnEvent
import app.lawnchair.nexus.net.NexusUnauthorizedException
import app.lawnchair.nexus.voice.NexusAudioCapture
import app.lawnchair.nexus.voice.NexusAudioRoute
import app.lawnchair.nexus.voice.NexusTtsPlayer
import app.lawnchair.nexus.voice.TokenEnvelope
import app.lawnchair.nexus.voice.TtsEnvelope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max

/** Which entry the overlay was opened in. */
enum class Mode { Text, Voice }

/**
 * The per-turn lifecycle. [AwaitingAnswer]: the model asked the user something (`ask_user`); the
 * turn is open on the server and the next input answers it.
 */
enum class TurnPhase { Idle, Listening, Thinking, Speaking, AwaitingAnswer, Done, Failed }

/** How voice mode was entered — drives the stop-button label (tap-to-stop vs release-to-send). */
enum class MicGesture { None, Tap, Hold }

/** A bubble: text, or (with [imageUrl]) a picture the assistant drew. */
data class ChatLine(val fromUser: Boolean, val text: String, val imageUrl: String? = null)

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
 * launcher's needs (barge-in is a simple cancel-and-relisten).
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
    /** The article this conversation is about, when it was opened from the News page. */
    val subject: ChatSubject? = null,
    /** Puts a Bluetooth headset in call mode for a voice turn; null leaves the phone's mic and speaker. */
    private val route: NexusAudioRoute? = null,
    /** The Microphone settings, read as each recording starts (the person may change them between turns). */
    private val micPrefs: () -> NexusMicPrefs = { NexusMicPrefs() },
    /** Whether to follow what the whole box is doing (the orb's); a turn with no screen has no orb. */
    watchBox: Boolean = true,
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(
        NexusChatUiState(mode = initialMode, micGesture = initialGesture),
    )
    val state: StateFlow<NexusChatUiState> = _state.asStateFlow()

    private val _box = MutableStateFlow(BoxOrbState.UNKNOWN)

    /**
     * What the whole box is doing (`substrate.watch`), for the orb whenever this overlay's own turn is
     * not live. [BoxOrbState.UNKNOWN] until the first frame and after any drop. Kept apart from
     * [state] because frames arrive up to ten a second and must not recompose the conversation.
     */
    val box: StateFlow<BoxOrbState> = _box.asStateFlow()

    /** Where an image event's path is fetched from: joined to the server, or already absolute. */
    fun imageUrl(path: String): String =
        if (path.startsWith("http://") || path.startsWith("https://")) path
        else NexusClient.unaryUrl(serverUrl, path)

    /** The `Authorization` header `/uploads` needs, or null without a token. */
    val imageAuthorization: String? = token.takeIf { it.isNotEmpty() }?.let { "Bearer $it" }

    private val tokenEnvelope = TokenEnvelope()
    private val ttsEnvelope = TtsEnvelope()

    private var conversationId: Int? = null

    /** The conversation this controller is in, once it has one. */
    val currentConversationId: Int? get() = conversationId
    private var currentTurnId: String? = null
    private var answerIndex: Int = -1
    // The `ask_user` question the open turn waits on; the next input answers it.
    private var pendingPromptId: String? = null

    private var capture: NexusAudioCapture? = null
    private var ttsPlayer: NexusTtsPlayer? = null
    private var turnJob: Job? = null
    private var listenJob: Job? = null
    private var watchdogJob: Job? = null

    private val busy: Boolean
        get() = _state.value.phase in setOf(TurnPhase.Listening, TurnPhase.Thinking, TurnPhase.Speaking)

    init {
        if (watchBox) watchBox()
    }

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

    /**
     * Begin recording (permission already ensured by the caller). A [handsFree] turn has no finger to
     * lift: it ends when the person stops speaking (the setting under Microphone), or at the longest turn.
     */
    /**
     * Opens the mic. With [cueOver] (the "speak now" line of a turn started from the earbuds, still
     * being played) the mic is already open, but what it hears until the line is over is thrown away
     * and the end of the turn is not waited for before then: an earbud microphone delivers silence
     * for a second or two after it opens, which the line is then spoken through.
     */
    fun startListening(handsFree: Boolean = false, cueOver: Job? = null) {
        if (busy) return
        val turnId = newTurnId()
        // An answer continues the open turn: keep its id so the resumed reply is not dropped.
        if (pendingPromptId == null) currentTurnId = turnId
        val recordingId = "rec-$turnId"
        val sessionId = conversationId?.toString() ?: "pending"
        _state.update { it.copy(phase = TurnPhase.Listening, userTranscript = "", errorMessage = null) }

        // Before the mic opens: the recorder picks its source from the link, and the reply that
        // follows plays through it.
        route?.acquire()
        val prefs = micPrefs()
        val cap = NexusAudioCapture(route, prefs)
        capture = cap
        val heard = java.util.concurrent.atomic.AtomicBoolean(cueOver == null)
        cueOver?.invokeOnCompletion { heard.set(true) }
        if (handsFree) {
            if (cueOver == null) {
                endWhenSilent(cap, prefs.silenceMs)
            } else {
                scope.launch {
                    cueOver.join()
                    if (capture === cap) endWhenSilent(cap, prefs.silenceMs)
                }
            }
        }
        armWatchdog(TurnPhase.Listening, 45_000, "No speech detected.")

        listenJob = scope.launch {
            runCatching {
                transport.streamTranscription(
                    base = serverUrl,
                    token = token,
                    recordingId = recordingId,
                    sessionId = sessionId,
                    pcm16Chunks = if (cueOver == null) cap.start() else cap.start().filter { heard.get() },
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

    /** Ends the turn by itself once the person has stopped speaking (see [SilenceEndDetector]). */
    private fun endWhenSilent(cap: NexusAudioCapture, silenceMs: Long?) {
        val detector = SilenceEndDetector(silenceMs)
        val began = SystemClock.elapsedRealtime()
        scope.launch {
            while (isActive && _state.value.phase == TurnPhase.Listening && capture === cap) {
                delay(100)
                if (detector.onLevel(cap.amplitude.value, SystemClock.elapsedRealtime() - began)) {
                    endRecordingAndSend()
                    break
                }
            }
        }
    }

    /** Voice mode's way to answer a question: record like a tap-to-talk turn. */
    fun answerByVoice() {
        if (_state.value.phase != TurnPhase.AwaitingAnswer) return
        _state.update { it.copy(micGesture = MicGesture.Tap) }
        startListening()
    }

    // endregion

    // region shared turn

    private fun submitUserTurn(text: String) {
        pendingPromptId?.let { promptId ->
            answerPrompt(promptId, text)
            return
        }
        // Commit the user's line + an empty assistant line to stream into.
        _state.update {
            val msgs = it.messages + ChatLine(true, text) + ChatLine(false, "")
            it.copy(messages = msgs, phase = TurnPhase.Thinking, userTranscript = "")
        }
        answerIndex = _state.value.messages.lastIndex

        val turnId = newTurnId()
        currentTurnId = turnId
        val player = NexusTtsPlayer(scope, onSegmentStarted = { ttsEnvelope.bump() }, route = route)
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
                            // The RPC stays open for the whole turn; only a real failure (401,
                            // 500, no network) is news. Swallowing it left the UI on Thinking.
                            runCatching { transport.submitTurn(serverUrl, token, cid, text, turnId, voice = if (subject != null) false else null) }
                                .onFailure { e ->
                                    if (e !is CancellationException && currentTurnId == turnId) {
                                        fail(describe(e, "Couldn't send to Nexus"))
                                    }
                                }
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
            // Stream ended (completed / failed / socket close): let audio finish, then settle. A
            // question still open went with it.
            pendingPromptId = null
            player.markEndOfStream()
            player.awaitDrain()
            route?.release()
            cancelWatchdog()
            if (_state.value.phase != TurnPhase.Failed) {
                _state.update { it.copy(phase = TurnPhase.Done) }
            }
        }
    }

    /** Send [text] as the answer to [promptId]; the open turn's reply streams in as before. */
    private fun answerPrompt(promptId: String, text: String) {
        pendingPromptId = null
        val cid = conversationId ?: return fail("Couldn't reach Nexus.")
        _state.update {
            val msgs = it.messages + ChatLine(true, text) + ChatLine(false, "")
            it.copy(messages = msgs, phase = TurnPhase.Thinking, userTranscript = "")
        }
        answerIndex = _state.value.messages.lastIndex
        armWatchdog(TurnPhase.Thinking, 120_000, "The assistant took too long.")
        scope.launch {
            runCatching { transport.answerUserPrompt(serverUrl, token, cid, promptId, text) }
                .onFailure { e ->
                    if (e !is CancellationException) fail(describe(e, "Couldn't answer"))
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
                // The spoken question arrives after the prompt event: keep waiting for the answer.
                val phase = _state.value.phase
                if (phase != TurnPhase.Speaking && phase != TurnPhase.AwaitingAnswer) {
                    _state.update { it.copy(phase = TurnPhase.Speaking) }
                }
            }
            is NexusTurnEvent.Sources -> _state.update { it.copy(sources = ev.sources) }
            is NexusTurnEvent.Image -> _state.update {
                it.copy(messages = it.messages + ChatLine(false, "", imageUrl = ev.url))
            }
            is NexusTurnEvent.UserPrompt -> {
                // The turn waits for the answer: show the question, free the input, stop the clock.
                pendingPromptId = ev.promptId
                cancelWatchdog()
                showAssistantLine(ev.question)
                _state.update { it.copy(phase = TurnPhase.AwaitingAnswer) }
            }
            is NexusTurnEvent.Failed -> {
                pendingPromptId = null
                bumpFailure()
                showAssistantLine("⚠ ${ev.message}")
                _state.update { it.copy(phase = TurnPhase.Failed, errorMessage = ev.message) }
            }
            is NexusTurnEvent.Completed -> Unit // settled after drain
            is NexusTurnEvent.Narrator -> Unit // pre-roll narration — not shown in this UI
        }
    }

    // endregion

    // region lifecycle

    /**
     * Cuts the turn short: the mic closes, the reply stops, the server is told where the person had
     * heard to, and the controller is ready for the next turn (the conversation stays).
     */
    fun interrupt() {
        val conversation = conversationId
        val heard = ttsPlayer?.currentSegmentIndex
        listenJob?.cancel()
        turnJob?.cancel()
        capture?.stop()
        ttsPlayer?.cancel()
        route?.release()
        pendingPromptId = null
        reset(TurnPhase.Idle)
        if (conversation != null) {
            scope.launch { runCatching { transport.cancelTurn(serverUrl, token, conversation, heard) } }
        }
    }

    fun dismiss() {
        if (_state.value.dismissing) return
        _state.update { it.copy(dismissing = true) }
    }

    /** Release everything. Call after the exit animation, from the overlay. */
    fun close() {
        cancelWatchdog()
        capture?.stop()
        ttsPlayer?.release()
        route?.release()
        scope.cancel()
    }

    // endregion

    // region helpers

    /**
     * The one `substrate.watch` subscription of this overlay, open from its first moment until
     * [close] cancels [scope] (which closes the socket). A drop reads as unknown and reconnects with a
     * backoff (see [boxOrbStates]).
     */
    private fun watchBox() {
        scope.launch {
            var trouble = false
            boxOrbStates { transport.watchBox(serverUrl, token) }.collect { next ->
                _box.value = next
                // The orb encodes failure as a one-shot flicker, so a box-wide error or a shut door
                // is fired on the way in, as on the phone.
                if (next.trouble && !trouble) bumpFailure()
                trouble = next.trouble
            }
        }
    }

    private suspend fun ensureConversation(): Int? {
        conversationId?.let { return it }
        return runCatching { transport.createConversation(serverUrl, token, subject) }
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

    /**
     * Put [text] in the current answer bubble while it is empty, otherwise in a new assistant bubble
     * after it — a failure or a question must be visible even mid-answer.
     */
    private fun showAssistantLine(text: String) {
        if (text.isEmpty()) return
        val i = answerIndex
        _state.update { s ->
            val cur = s.messages.getOrNull(i)
            if (cur != null && !cur.fromUser && cur.imageUrl == null && cur.text.isEmpty()) {
                val msgs = s.messages.toMutableList()
                msgs[i] = cur.copy(text = text)
                s.copy(messages = msgs)
            } else {
                s.copy(messages = s.messages + ChatLine(false, text))
            }
        }
        answerIndex = _state.value.messages.lastIndex
    }

    private fun bumpFailure() = _state.update { it.copy(failureSignal = it.failureSignal + 1) }

    /** The sentence for a failed call: a refused session key says to sign in again. */
    private fun describe(e: Throwable, what: String): String =
        if (e is NexusUnauthorizedException) {
            "Your Nexus session has ended. Open Nexus to sign in again."
        } else {
            "$what: ${e.message}"
        }

    private fun fail(message: String) {
        cancelWatchdog()
        capture?.stop()
        route?.release()
        pendingPromptId = null
        bumpFailure()
        showAssistantLine("⚠ $message")
        _state.update { it.copy(phase = TurnPhase.Failed, errorMessage = message) }
    }

    private fun reset(phase: TurnPhase) {
        cancelWatchdog()
        capture?.stop()
        route?.release()
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
