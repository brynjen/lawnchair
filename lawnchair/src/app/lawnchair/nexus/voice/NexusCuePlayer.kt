package app.lawnchair.nexus.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaPlayer
import android.os.Build
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Plays one short spoken line (Ogg/Opus) and returns when it has ended: the "I'm listening" that opens a
 * turn started from the earbuds. In the headset's call stream when [route] holds it (which is also what
 * brings the call link up), else as ordinary speech. False when it could not be played, so the caller
 * can fall back to a beep.
 */
object NexusCuePlayer {
    /** How long the call link is given to open, on silence, before the line is played (see [openLink]). */
    private const val LINK_MS = 400L

    suspend fun speak(bytes: ByteArray, route: NexusAudioRoute?, timeoutMs: Long = 3_600): Boolean =
        withTimeoutOrNull(timeoutMs) {
            openLink(route)
            suspendCancellableCoroutine { cont ->
                val player = MediaPlayer()
                fun done(played: Boolean) {
                    runCatching { player.release() }
                    if (cont.isActive) cont.resume(played)
                }
                runCatching {
                    val headset = route?.headset
                    player.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(if (headset != null) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_ASSISTANT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build(),
                    )
                    if (headset != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) player.setPreferredDevice(headset)
                    player.setDataSource(NexusTtsPlayer.ByteArrayMediaDataSource(bytes))
                    player.setOnCompletionListener { done(true) }
                    player.setOnErrorListener { _, _, _ -> done(false); true }
                    player.prepare()
                    player.start()
                }.onFailure { done(false) }
                cont.invokeOnCancellation { runCatching { player.release() } }
            }
        } ?: false

    /**
     * The first stretch of sound after a call link comes up is lost in the earbuds, so a word of half a
     * second ("Ja?") is a blip. The link is brought up on silence and the line follows.
     */
    private suspend fun openLink(route: NexusAudioRoute?) {
        val headset = route?.headset ?: return
        val rate = 16_000
        val samples = (rate * LINK_MS / 1000).toInt()
        val track = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(samples * 2)
                .build()
        }.getOrNull() ?: return
        try {
            track.write(ShortArray(samples), 0, samples)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) track.setPreferredDevice(headset)
            track.play()
            delay(LINK_MS)
        } finally {
            runCatching { track.release() }
        }
    }
}
