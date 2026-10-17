package app.lawnchair.nexus.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import app.lawnchair.nexus.buds.NexusMicPrefs
import app.lawnchair.nexus.buds.applyGain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlin.math.sqrt

/**
 * Microphone capture producing raw PCM16 / 16 kHz / mono chunks — the format `audio.sendAudioChunk`
 * expects (matches the mobile `record` config). Also exposes a normalized RMS [amplitude] (0..1) so
 * the orb reacts to the user's voice while Listening (AudioRecord has no amplitude callback).
 *
 * Crucially, [stop] makes the emitted flow **complete** (not just stop the recorder): the STT
 * consumer keys its end-of-stream (whisper EOF → final transcript) off the flow completing. Caller
 * must hold `RECORD_AUDIO` before [start] (see `MicPermission`).
 */
class NexusAudioCapture(
    /** The headset in call mode for the turn, which is then the microphone; null is the phone's. */
    private val route: NexusAudioRoute? = null,
    /** The Microphone settings: the pickup level scales what is sent, the switches go to the platform's effects. */
    private val prefs: NexusMicPrefs = NexusMicPrefs(),
) {

    private val _amplitude = MutableStateFlow(0f)

    /** Live mic loudness, 0..1, updated per chunk. Resets to 0 when capture stops. */
    val amplitude: StateFlow<Float> = _amplitude

    @Volatile
    private var running = false

    @Volatile
    private var record: AudioRecord? = null

    // Noise suppression, echo cancellation and automatic gain, as the settings ask; released with the recorder.
    private var effects: List<android.media.audiofx.AudioEffect> = emptyList()

    /**
     * Start capturing; emits ~[chunkMs] PCM16 chunks until [stop] (flow completes) or the collector
     * is cancelled. Reads on the IO dispatcher; buffers unbounded so the opening burst is never
     * dropped (whisper needs chunk 0).
     */
    @SuppressLint("MissingPermission")
    fun start(chunkMs: Int = 100): Flow<ByteArray> = callbackFlow {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
            .coerceAtLeast(2 * SAMPLE_RATE / 10)
        val chunkBytes = (SAMPLE_RATE * 2 * chunkMs / 1000).coerceAtLeast(320) // 16-bit mono
        val headset = route?.headset
        val rec = AudioRecord(
            // A headset in call mode records as a call; otherwise as before.
            if (headset != null) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            CHANNEL,
            ENCODING,
            maxOf(minBuf, chunkBytes * 4),
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            close(IllegalStateException("AudioRecord failed to initialize"))
            return@callbackFlow
        }
        record = rec
        running = true
        route?.input()?.let { rec.setPreferredDevice(it) }
        // A call already carries the system's own processing; a second filter on a headset's microphone
        // is what it does worst with.
        effects = if (headset != null) {
            emptyList()
        } else {
            listOfNotNull(
                if (prefs.noiseSuppression && NoiseSuppressor.isAvailable()) NoiseSuppressor.create(rec.audioSessionId) else null,
                if (prefs.echoCancel && AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(rec.audioSessionId) else null,
                if (prefs.boost && AutomaticGainControl.isAvailable()) AutomaticGainControl.create(rec.audioSessionId) else null,
            ).onEach { it.enabled = true }
        }
        rec.startRecording()

        // Blocking reads run on IO; stop() flips `running` so the loop exits and the flow completes.
        val reader = launch(Dispatchers.IO) {
            val buf = ByteArray(chunkBytes)
            try {
                while (running) {
                    val read = rec.read(buf, 0, buf.size)
                    if (read <= 0) continue
                    val chunk = applyGain(buf, read, prefs.gain).let { if (it === buf) buf.copyOf(read) else it }
                    _amplitude.value = rms(chunk, chunk.size)
                    trySend(chunk)
                }
            } finally {
                close() // complete the flow so the STT consumer sends EOF
            }
        }

        awaitClose {
            running = false
            reader.cancel()
            stopInternal()
        }
    }.buffer(Channel.UNLIMITED)

    /** Stop recording; the emitted flow completes (triggering STT finalization). */
    fun stop() {
        running = false
    }

    private fun stopInternal() {
        val rec = record ?: return
        record = null
        effects.forEach { runCatching { it.release() } }
        effects = emptyList()
        runCatching { rec.stop() }
        runCatching { rec.release() }
        _amplitude.value = 0f
    }

    /** Root-mean-square of the PCM16 little-endian samples in [bytes], normalized to ~0..1. */
    private fun rms(bytes: ByteArray, len: Int): Float {
        var sum = 0.0
        var n = 0
        var i = 0
        while (i + 1 < len) {
            val sample = (bytes[i].toInt() and 0xFF) or (bytes[i + 1].toInt() shl 8)
            sum += (sample * sample).toDouble()
            n++
            i += 2
        }
        if (n == 0) return 0f
        val rms = sqrt(sum / n) / 32768.0
        // Speech RMS is small; scale so normal talking lands mid-range, then clamp.
        return (rms * 6.0).coerceIn(0.0, 1.0).toFloat()
    }

    private companion object {
        const val SAMPLE_RATE = 16000
        const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }
}
