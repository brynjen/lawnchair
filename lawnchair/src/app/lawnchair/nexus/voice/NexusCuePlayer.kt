package app.lawnchair.nexus.voice

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
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
    suspend fun speak(bytes: ByteArray, route: NexusAudioRoute?, timeoutMs: Long = 3_000): Boolean =
        withTimeoutOrNull(timeoutMs) {
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
}
