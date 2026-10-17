package app.lawnchair.nexus.buds

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

/**
 * A moment of digital silence played as media. Android sends the earbuds' media keys to the session
 * of the app that last played audio, so a session that only says it is playing never gets them: this
 * is what makes [NexusBudsService]'s session the one the keys go to. It is not heard and takes no
 * audio focus, so music that is paused stays paused and music that plays is not ducked.
 */
internal object SilentBlip {
    private const val RATE = 8000

    /** Starts the silence; release the track when it is over. Null when the device would not play it. */
    fun play(durationMs: Int = 400): AudioTrack? = runCatching {
        val samples = RATE * durationMs / 1000
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(samples * 2)
            .build()
        track.write(ShortArray(samples), 0, samples)
        track.play()
        track
    }.getOrNull()
}
