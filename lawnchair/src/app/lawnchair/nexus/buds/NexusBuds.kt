package app.lawnchair.nexus.buds

import android.content.Context
import app.lawnchair.nexus.chat.TurnPhase
import kotlin.math.pow

/**
 * The Microphone settings the Nexus app saves (Settings → Microphone), as the launcher side reads them
 * from the app's own saved settings: the same file and keys the app writes (`shared_preferences`
 * stores every key under `flutter.`, and an int as a Long). A key that is missing, or holds something
 * else, is the app's default, so the two sides agree before anyone has opened the page.
 */
data class NexusMicPrefs(
    /** `auto`, `bluetooth` or `phone`. */
    val source: String = "auto",
    /** 0..100, the pickup level; 50 leaves the microphone as it is ([micGain]). */
    val sensitivity: Int = 50,
    /** How long a pause ends what you say in a hands-free turn: `sensitive`, `balanced` or `conservative`. */
    val vad: String = "balanced",
    val noiseSuppression: Boolean = false,
    val echoCancel: Boolean = false,
    val boost: Boolean = false,
    /** Whether a double tap on the earbuds starts a turn. On unless the person has switched it off. */
    val earbudTrigger: Boolean = true,
) {
    /** The gain the pickup level stands for. */
    val gain: Double get() = micGain(sensitivity)

    /** How long a pause may be before a hands-free turn ends, or null: it never ends on its own. */
    val silenceMs: Long?
        get() = when (vad) {
            "sensitive" -> 800L
            "conservative" -> null
            else -> 1_400L
        }

    companion object {
        private const val PREFIX = "flutter.settings.mic."
        private const val FILE = "FlutterSharedPreferences"

        fun from(get: (String) -> Any?): NexusMicPrefs {
            fun text(name: String, default: String) = (get(PREFIX + name) as? String) ?: default
            fun flag(name: String) = (get(PREFIX + name) as? Boolean) ?: false
            val level = when (val v = get(PREFIX + "sensitivityV2")) {
                is Long -> v.toInt()
                is Int -> v
                else -> 50
            }
            return NexusMicPrefs(
                source = text("source", "auto"),
                sensitivity = level,
                vad = text("vad", "balanced"),
                noiseSuppression = flag("noiseSuppressionV2"),
                echoCancel = flag("echoCancelV2"),
                boost = flag("boostV2"),
                earbudTrigger = (get(PREFIX + "earbudTrigger") as? Boolean) ?: true,
            )
        }

        fun read(context: Context): NexusMicPrefs {
            val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            return from { key -> prefs.all[key] }
        }
    }
}

/** The gain a pickup level (0..100) stands for: 50 is 1, every 25 steps doubles or halves it. Same as the app's `micGain`. */
fun micGain(level: Int): Double = 2.0.pow((level.coerceIn(0, 100) - 50) / 25.0)

/**
 * The first [length] bytes of [pcm16] (16-bit little-endian samples) scaled by [gain] and clipped. A gain of
 * 1 hands back [pcm16] itself; otherwise a new array of [length] bytes (a stray last byte is dropped).
 */
fun applyGain(pcm16: ByteArray, length: Int, gain: Double): ByteArray {
    if (gain == 1.0 && length == pcm16.size) return pcm16
    val samples = length / 2
    val out = ByteArray(samples * 2)
    for (i in 0 until samples) {
        val raw = (pcm16[i * 2].toInt() and 0xFF) or (pcm16[i * 2 + 1].toInt() shl 8) // sign-extends
        val scaled = Math.round(raw * gain).toInt().coerceIn(-32768, 32767)
        out[i * 2] = (scaled and 0xFF).toByte()
        out[i * 2 + 1] = ((scaled shr 8) and 0xFF).toByte()
    }
    return out
}

/**
 * Decides when a hands-free turn (started from the earbuds, with no finger to lift) is over, from the
 * mic's loudness. [onLevel] is told the level (0..1) and the milliseconds since listening began, and
 * answers true when to stop: a pause of [silenceMs] after speech, [noSpeechMs] of nothing at all, or
 * [maxMs] whatever happens. A null [silenceMs] never ends on a pause (the "Manual" setting).
 */
class SilenceEndDetector(
    private val silenceMs: Long?,
    private val noSpeechMs: Long = 8_000,
    private val maxMs: Long = 60_000,
    private val threshold: Float = 0.1f,
) {
    private var spoke = false
    private var lastLoudMs = 0L

    fun onLevel(level: Float, elapsedMs: Long): Boolean {
        if (level >= threshold) {
            spoke = true
            lastLoudMs = elapsedMs
        }
        if (elapsedMs >= maxMs) return true
        if (!spoke) return elapsedMs >= noSpeechMs
        val pause = silenceMs ?: return false
        return elapsedMs - lastLoudMs >= pause
    }
}

/** A tap on the earbuds, as the media keys the system sends for it. */
enum class Tap { Double, Single }

enum class BudsAction { StartListening, FinishListening, InterruptAndListen, Cancel, Ignore }

/**
 * What a tap does, given where the turn is. A double tap (the next-track key) is the way in: it starts a
 * turn, sends what has been said, or cuts Nexus off and listens. A single tap (play/pause) only ever
 * stops a turn that is going; with none, it is left to whatever else is playing.
 */
fun budsAction(tap: Tap, phase: TurnPhase): BudsAction = when (tap) {
    Tap.Double -> when (phase) {
        TurnPhase.Listening -> BudsAction.FinishListening
        TurnPhase.Thinking, TurnPhase.Speaking -> BudsAction.InterruptAndListen
        TurnPhase.Idle, TurnPhase.Done, TurnPhase.Failed, TurnPhase.AwaitingAnswer -> BudsAction.StartListening
    }
    Tap.Single -> when (phase) {
        TurnPhase.Listening, TurnPhase.Thinking, TurnPhase.Speaking -> BudsAction.Cancel
        else -> BudsAction.Ignore
    }
}

/**
 * Whether a tap goes to the app's Home screen (which shows the turn like a held one) rather than being
 * run here with audio only: the app is in front, its Home screen is up, and, for a single tap, a turn is
 * going (otherwise a single tap is the music app's to take).
 */
fun appTakesTap(tap: Tap, appResumed: Boolean, homeReady: Boolean, busy: Boolean): Boolean =
    appResumed && homeReady && (tap == Tap.Double || busy)

/**
 * The code speech is told in, from the Language setting as the app saves it ("Norsk", "English",
 * "Automatic"); null lets the server decide.
 */
fun speechLanguageOf(saved: String?): String? = when (saved) {
    "Norsk" -> "no"
    "English" -> "en"
    else -> null
}
