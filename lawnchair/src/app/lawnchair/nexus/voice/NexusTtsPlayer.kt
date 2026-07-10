package app.lawnchair.nexus.voice

import android.media.AudioAttributes
import android.media.MediaDataSource
import android.media.MediaPlayer
import app.lawnchair.nexus.net.AudioSegment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Plays a sequence of TTS audio segments (Ogg/Opus in production; WAV in simulation) strictly in
 * arrival order, one at a time — the launcher analogue of the mobile `AudioPlaybackService`.
 *
 * Each segment is a self-contained container decoded by the platform via a [MediaDataSource] over
 * its bytes (no manual Opus decode). Segments enqueue as they stream in; the drain loop plays each
 * and awaits its completion before the next. Opus container support in `MediaPlayer` is guaranteed
 * API 29+; on older devices a segment may fail to prepare — that's caught per-segment so the turn
 * (text + orb) still proceeds.
 *
 * [scope] is the controller's scope; the drain loop lives there and is torn down by [release].
 */
class NexusTtsPlayer(
    scope: CoroutineScope,
    private val onSegmentStarted: (Int) -> Unit = {},
) {
    private val queue = Channel<AudioSegment>(Channel.UNLIMITED)

    @Volatile
    var currentSegmentIndex: Int? = null
        private set

    @Volatile
    private var current: MediaPlayer? = null

    private val drainJob: Job = scope.launch {
        for (seg in queue) {
            currentSegmentIndex = seg.segmentIndex
            onSegmentStarted(seg.segmentIndex)
            runCatching { playSegment(seg) } // degrade gracefully on decode failure
        }
    }

    /** Queue a segment for playback. */
    fun enqueue(segment: AudioSegment) {
        queue.trySend(segment)
    }

    /** No more segments will arrive — the drain loop finishes once the queue empties. */
    fun markEndOfStream() {
        queue.close()
    }

    /** Suspend until every queued segment has played (or [timeoutMs] elapses). */
    suspend fun awaitDrain(timeoutMs: Long = 60_000) {
        withTimeoutOrNull(timeoutMs) { drainJob.join() }
    }

    /** Stop immediately (barge-in): drop the queue, kill the current player. */
    fun cancel() {
        queue.close()
        stopCurrent()
    }

    fun release() {
        queue.close()
        drainJob.cancel()
        stopCurrent()
    }

    private fun stopCurrent() {
        current?.let { p -> runCatching { p.stop() }; runCatching { p.release() } }
        current = null
        currentSegmentIndex = null
    }

    private suspend fun playSegment(seg: AudioSegment) = suspendCancellableCoroutine { cont ->
        val player = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            setDataSource(ByteArrayMediaDataSource(seg.bytes))
            setOnCompletionListener {
                runCatching { it.release() }
                if (current === this) current = null
                if (cont.isActive) cont.resume(Unit)
            }
            setOnErrorListener { mp, _, _ ->
                runCatching { mp.release() }
                if (current === this) current = null
                if (cont.isActive) cont.resume(Unit)
                true
            }
        }
        current = player
        cont.invokeOnCancellation { runCatching { player.release() } }
        runCatching {
            player.prepare() // segments are small in-memory buffers; sync prepare is fine
            player.start()
        }.onFailure {
            runCatching { player.release() }
            if (current === player) current = null
            if (cont.isActive) cont.resume(Unit)
        }
    }

    /** A [MediaDataSource] backed by an in-memory byte array (one complete audio container). */
    private class ByteArrayMediaDataSource(private val data: ByteArray) : MediaDataSource() {
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position >= data.size) return -1
            val end = minOf(position + size, data.size.toLong()).toInt()
            val count = end - position.toInt()
            if (count <= 0) return -1
            System.arraycopy(data, position.toInt(), buffer, offset, count)
            return count
        }

        override fun getSize(): Long = data.size.toLong()

        override fun close() = Unit
    }
}
