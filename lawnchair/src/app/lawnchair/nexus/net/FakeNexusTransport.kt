package app.lawnchair.nexus.net

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.Log
import app.lawnchair.nexus.box.SubstrateFrame
import app.lawnchair.nexus.box.SubstrateMode
import app.lawnchair.nexus.box.SubstrateService
import app.lawnchair.nexus.box.SubstrateSlot
import app.lawnchair.nexus.news.NewsFeed
import app.lawnchair.nexus.news.NewsFigure
import app.lawnchair.nexus.news.NewsItem
import app.lawnchair.nexus.news.NewsKind
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
 *    [newsFeed] and [newsRefresh] answer with four canned cards (a paper with every field, a news card
 *    with a picture, a long title with nothing else, a card with a single key point) and [newsImage]
 *    draws a gradient.
 *
 * WAV (not Opus) is deliberate: it isolates the playback *plumbing* (queueing, drain, envelope, orb
 * Speaking) from the platform Opus decoder, which is only spot-checked against the live server.
 */
class FakeNexusTransport(
    private val failMidway: Boolean = false,
) : NexusTransport {

    override suspend fun createConversation(base: String, token: String, subject: ChatSubject?): Int {
        delay(100)
        return 1
    }

    override suspend fun submitTurn(
        base: String,
        token: String,
        conversationId: Int,
        userText: String,
        turnId: String,
        voice: Boolean?,
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
                NewsItem(
                    id = 1,
                    sourceName = "arXiv",
                    url = "https://arxiv.org/abs/0000.00001",
                    title = "Cheaper long-context attention without losing accuracy on retrieval tasks",
                    synopsis = "The authors replace full attention in the upper layers with a learned sparse pattern and " +
                        "match the dense baseline on retrieval and summarisation while cutting memory use sharply.",
                    body = "Long-context models spend most of their memory on attention. The paper keeps full attention " +
                        "in the first layers, where it matters for local structure, and replaces it above that with a " +
                        "sparse pattern the model learns during training.\n\nOn needle-in-a-haystack retrieval up to " +
                        "128k tokens the sparse model matches the dense baseline. On summarisation it loses under one " +
                        "point. Training cost is within 5% of the baseline, and inference memory falls by more than a " +
                        "third.\n\nThe authors release the code and the checkpoints, and list the cases where the " +
                        "pattern fails: documents whose key facts are spread evenly across the whole text.",
                    publishedAt = now.minusSeconds(2 * 3600),
                    tags = listOf("ai", "research"),
                    kind = NewsKind.Paper,
                    keyPoints = listOf(
                        "Full attention is kept in the first layers; the upper layers learn a sparse pattern.",
                        "Retrieval accuracy up to 128k tokens matches the dense baseline.",
                        "Inference memory falls by 38%, with training cost within 5%.",
                        "Fails on texts whose key facts are spread evenly throughout.",
                    ),
                    figures = listOf(
                        NewsFigure("38%", "less inference memory"),
                        NewsFigure("128k", "tokens of context tested"),
                        NewsFigure("-0.8 pt", "on summarisation against the baseline"),
                        NewsFigure("5%", "extra training cost at most"),
                    ),
                    imageUrl = "/uploads/system/news/ab12cd34ef56.jpg",
                    imageGenerated = true,
                ),
                NewsItem(
                    id = 2,
                    sourceName = "NRK",
                    url = "https://example.com/budget",
                    title = "Regjeringen la frem budsjettet",
                    synopsis = "Statsbudsjettet inneholder flere endringer i skatt og forsvar, og de største postene " +
                        "gjelder helse og samferdsel.",
                    body = "Regjeringen la i dag frem forslaget til statsbudsjett. Det viktigste er økte bevilgninger " +
                        "til forsvar og helse, mens skatten på formue og eiendom justeres.\n\nOpposisjonen sier " +
                        "forslaget ikke gjør nok for kommunene.",
                    publishedAt = now.minusSeconds(42 * 60),
                    tags = listOf("norge"),
                    keyPoints = listOf(
                        "Forsvar og helse får de største økningene.",
                        "Formuesskatten justeres, eiendomsskatten ligger fast.",
                        "Opposisjonen mener kommunene taper.",
                    ),
                    figures = listOf(NewsFigure("NOK 2.1 bn", "ekstra til forsvar")),
                    imageUrl = "/uploads/system/news/cd34ef56ab12.jpg",
                ),
                NewsItem(
                    id = 3,
                    sourceName = "Example Security",
                    url = "https://example.com/vpn",
                    title = "A serious flaw in a popular VPN client lets a website read traffic that should have been " +
                        "tunnelled, and the vendor has not yet shipped a fix for all platforms",
                    synopsis = "Researchers advise updating at once where a patch exists.",
                    body = null,
                    publishedAt = now.minusSeconds(26 * 3600),
                    tags = listOf("security"),
                ),
                NewsItem(
                    id = 4,
                    sourceName = "Example Science",
                    url = "https://example.com/fusion",
                    title = "A stability record for fusion",
                    synopsis = "A tokamak team reports minutes of net-positive plasma.",
                    body = null,
                    publishedAt = now.minusSeconds(3 * 3600),
                    tags = listOf("science"),
                    keyPoints = listOf("Net-positive plasma was held for several minutes."),
                ),
            ),
            refreshedAt = now,
        )
    }

    override suspend fun newsRefresh(base: String, token: String): NewsFeed {
        delay(1_500) // a sweep takes a while
        return newsFeed(base, token, 50)
    }

    /** A gradient JPEG standing in for the server's picture, so a mock card has one to show. */
    override suspend fun newsImage(base: String, token: String, path: String): ByteArray {
        delay(100)
        val hue = (path.hashCode() and 0x7FFFFFFF) % 360
        val bitmap = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
        val paint = Paint().apply {
            shader = LinearGradient(
                0f, 0f, 640f, 360f,
                Color.HSVToColor(floatArrayOf(hue.toFloat(), 0.45f, 0.85f)),
                Color.HSVToColor(floatArrayOf(((hue + 60) % 360).toFloat(), 0.6f, 0.45f)),
                Shader.TileMode.CLAMP,
            )
        }
        Canvas(bitmap).drawRect(0f, 0f, 640f, 360f, paint)
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
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
