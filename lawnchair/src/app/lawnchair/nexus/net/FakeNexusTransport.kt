package app.lawnchair.nexus.net

import android.util.Log
import app.lawnchair.nexus.box.SubstrateFrame
import app.lawnchair.nexus.box.SubstrateMode
import app.lawnchair.nexus.box.SubstrateService
import app.lawnchair.nexus.box.SubstrateSlot
import app.lawnchair.nexus.news.NewsFeed
import app.lawnchair.nexus.news.NewsItem
import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.ByteArrayOutputStream
import kotlin.math.PI
import kotlin.math.sin

/**
 * A scripted, server-free [NexusTransport] for developing and verifying the chat/voice UI while the
 * real Serverpod is unreachable. It exercises the *real* pipeline plumbing end-to-end:
 *
 *  - [streamTranscription] drains the *real* mic PCM flow (so capture + chunking are genuinely run
 *    and logged), then emits a scripted partial → final transcript, mimicking whisper.
 *  - [streamTurn] emits scripted text deltas + synthetic **WAV tone** audio segments (played through
 *    the real [app.lawnchair.nexus.voice.NexusTtsPlayer]) + sources, then completes. Set
 *    [failMidway] to exercise the error/red-flash path.
 *  - [watchBox] loops a quiet box, then two actors working, so the orb's all-slots draw can be seen;
 *    [newsFeed] and [newsRefresh] answer with three canned cards.
 *
 * WAV (not Opus) is deliberate: it isolates the playback *plumbing* (queueing, drain, envelope, orb
 * Speaking) from the platform Opus decoder, which is only spot-checked against the live server.
 */
class FakeNexusTransport(
    private val failMidway: Boolean = false,
) : NexusTransport {

    override suspend fun createConversation(base: String, token: String): Int {
        delay(100)
        return 1
    }

    override suspend fun submitTurn(
        base: String,
        token: String,
        conversationId: Int,
        userText: String,
        turnId: String,
    ) {
        // No-op: the scripted streamTurn drives the reply.
        Log.d(TAG, "submitTurn: \"$userText\" (turn=$turnId)")
    }

    override suspend fun answerUserPrompt(
        base: String,
        token: String,
        conversationId: Int,
        promptId: String,
        answer: String,
    ) {
        Log.d(TAG, "answerUserPrompt($promptId): \"$answer\"")
    }

    override suspend fun cancelTurn(
        base: String,
        token: String,
        conversationId: Int,
        heardThroughSegmentIndex: Int?,
    ) {
        Log.d(TAG, "cancelTurn(heardThrough=$heardThroughSegmentIndex)")
    }

    override fun streamTurn(
        base: String,
        token: String,
        conversationId: Int,
        onReady: () -> Unit,
    ): Flow<NexusTurnEvent> = flow {
        onReady() // triggers the (no-op) submitTurn
        delay(500) // "thinking" beat so the Thinking orb is visible

        // A pre-generated answer, streamed sentence-by-sentence with a spoken segment each so the
        // Speaking orb pulses to real audio. Text deltas within a sentence arrive word-by-word.
        val sentences = listOf(
            "Right now it's 18 degrees and partly cloudy.",
            "Light rain is expected this afternoon,",
            "so you might want to bring an umbrella if you're heading out.",
        )
        var segment = 0
        sentences.forEach { sentence ->
            sentence.split(" ").forEach { word ->
                emit(NexusTurnEvent.TextDelta("$word "))
                delay(60)
            }
            // One TTS segment per sentence; tone alternates so segments are distinguishable.
            val freq = if (segment % 2 == 0) 420.0 else 330.0
            val isLast = segment == sentences.lastIndex
            emit(NexusTurnEvent.Audio(wavTone(freq, 700), segmentIndex = segment, durationMs = 700, isFinal = isLast))
            segment++
            if (failMidway && segment == 1) {
                emit(NexusTurnEvent.Failed("Simulated failure"))
                return@flow
            }
            delay(250)
        }
        emit(NexusTurnEvent.Sources(listOf(NexusSource("weather.example.com", "https://weather.example.com"))))
        delay(150)
        emit(NexusTurnEvent.Completed())
    }

    override fun streamTranscription(
        base: String,
        token: String,
        recordingId: String,
        sessionId: String,
        pcm16Chunks: Flow<ByteArray>,
        language: String?,
    ): Flow<TranscriptionEvent> = flow {
        var chunks = 0
        var bytes = 0L
        pcm16Chunks.collect { chunk ->
            chunks++
            bytes += chunk.size
            if (chunks == 3) emit(TranscriptionEvent("What's the weather", isFinal = false))
        }
        // Mic flow completed (user released / tapped stop) → finalize.
        Log.d(TAG, "STT drained rec=$recordingId chunks=$chunks bytes=$bytes → final")
        emit(TranscriptionEvent("What's the weather like today?", isFinal = true))
    }

    override fun watchBox(base: String, token: String): Flow<SubstrateFrame> = flow {
        var sequence = 0L
        fun frame(vararg slots: SubstrateSlot) = SubstrateFrame(sequence++, slots.toList(), capacity = 8, dropped = 0)
        fun slot(i: Int, mode: SubstrateMode, service: SubstrateService, actor: String) =
            SubstrateSlot(i, mode, service, label = null, actor = actor)
        while (true) {
            emit(frame(SubstrateSlot(0, SubstrateMode.Idle, SubstrateService.None, null, null)))
            delay(4_000)
            emit(frame(slot(0, SubstrateMode.Thinking, SubstrateService.VllmChat, "conv:1")))
            delay(1_500)
            emit(
                frame(
                    slot(0, SubstrateMode.Thinking, SubstrateService.VllmChat, "conv:1"),
                    slot(1, SubstrateMode.ToolCall, SubstrateService.Search, "subagent:2"),
                ),
            )
            delay(3_000)
        }
    }

    override suspend fun newsFeed(base: String, token: String, limit: Int): NewsFeed {
        delay(150)
        val now = Instant.now()
        return NewsFeed(
            items = listOf(
                NewsItem(1, "Example News", "https://example.com/1", "Open-weight models close the gap",
                    "A new wave of permissive models matches last year's flagships.", "The full story.",
                    now.minusSeconds(42 * 60), listOf("ai", "tech")),
                NewsItem(2, "Example Science", "https://example.com/2", "A stability record for fusion",
                    "A tokamak team reports minutes of net-positive plasma.", null,
                    now.minusSeconds(3 * 3600), listOf("science")),
                NewsItem(3, "Example Security", "https://example.com/3", "Threat-modelling voice assistants",
                    "From replay attacks to prompt injection over audio.", null,
                    now.minusSeconds(26 * 3600), listOf("security", "ai")),
            ),
            refreshedAt = now,
        )
    }

    override suspend fun newsRefresh(base: String, token: String): NewsFeed {
        delay(1_500) // a sweep takes a while
        return newsFeed(base, token, 50)
    }

    private companion object {
        const val TAG = "NexusFakeTransport"
        const val SAMPLE_RATE = 16000

        /** A mono 16-bit PCM WAV of a [freq]-Hz sine lasting [ms] milliseconds. */
        fun wavTone(freq: Double, ms: Int): ByteArray {
            val nSamples = SAMPLE_RATE * ms / 1000
            val dataBytes = nSamples * 2
            val out = ByteArrayOutputStream(44 + dataBytes)
            fun le16(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }
            fun le32(v: Int) { le16(v and 0xFFFF); le16((v shr 16) and 0xFFFF) }
            // RIFF header
            out.write("RIFF".toByteArray()); le32(36 + dataBytes); out.write("WAVE".toByteArray())
            // fmt chunk
            out.write("fmt ".toByteArray()); le32(16); le16(1); le16(1) // PCM, mono
            le32(SAMPLE_RATE); le32(SAMPLE_RATE * 2); le16(2); le16(16)
            // data chunk
            out.write("data".toByteArray()); le32(dataBytes)
            for (i in 0 until nSamples) {
                val fade = 1.0 - (i.toDouble() / nSamples) // gentle decay so segments don't click
                val s = (sin(2.0 * PI * freq * i / SAMPLE_RATE) * 0.35 * fade * Short.MAX_VALUE).toInt()
                le16(s)
            }
            return out.toByteArray()
        }
    }
}
