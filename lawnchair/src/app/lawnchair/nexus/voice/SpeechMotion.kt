package app.lawnchair.nexus.voice

import android.content.Context
import kotlin.math.pow

/**
 * What drives the speaking orb's equalizer: the person's choice under the app's Settings →
 * Appearance → When speaking (`SpeechMotion` in `nexus_mobile/lib/domain/entities/orb_settings.dart`),
 * read from the app's own preferences.
 */
enum class SpeechMotion {
    /** A swell each time a sentence starts, settling to a quarter: how it was. */
    PULSES,

    /** The voice's own loudness, syllable by syllable, as it plays. */
    VOICE,

    /** The voice, stronger still: its peaks fill the equalizer. */
    LOUD,

    /** The voice with its contrast raised: the quiet falls away and the stresses jump to the top. */
    PUNCHY,
    ;

    companion object {
        private const val FILE = "FlutterSharedPreferences"
        private const val KEY = "flutter.settings.orb.speechMotion"

        /** The motion the app stored as [name] (`voice`, `loud`, ...); Voice when there is none. */
        fun fromName(name: String?): SpeechMotion = entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: VOICE

        fun read(context: Context): SpeechMotion =
            fromName(context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, null))
    }
}

/**
 * The level `speechBands` is given for [voice], the loudness of the reply playing now (0..1,
 * [SpeechEnvelope]), under [motion]. Over 1 drives the bands harder. The same as `speechLevelFor` in
 * `nexus_mobile/lib/core/orb/orb.dart`: 1.6x moved the most on a real reply; more than about 2x pins
 * the bands at the top.
 */
fun speechLevelFor(motion: SpeechMotion, voice: Float): Float = when (motion) {
    SpeechMotion.PULSES -> voice
    SpeechMotion.VOICE -> voice * 1.6f
    SpeechMotion.LOUD -> voice * 2.2f
    SpeechMotion.PUNCHY -> voice.toDouble().pow(1.6).toFloat() * 2.6f
}
