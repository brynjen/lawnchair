package app.lawnchair.nexus.voice

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Amplitude envelopes that convert discrete stream events into a smooth 0..1 level for the orb's
 * `externalAmplitude`, so the orb pulses with the answer's real cadence instead of a synthetic sine.
 *
 * These mirror the Flutter reference: [TokenEnvelope] ≈ the launcher's original text-chat envelope,
 * [TtsEnvelope] ≈ `home_page.dart _OrbPageState._ttsEnvelope`.
 *
 * Both read wall-clock time via an injectable [now] (nanos) so they're deterministic under unit test.
 */

/**
 * Attack/decay envelope fed by streamed-token arrival: each text delta kicks the level up (bigger
 * deltas kick harder), decaying at ~2.5/s between bursts. Used while text streams (no audio).
 */
class TokenEnvelope(private val now: () -> Long = System::nanoTime) {
    private var peak = 0f
    private var bumpedAtNanos = 0L

    fun bump(chars: Int) {
        peak = max(level(), min(1f, 0.5f + chars / 40f))
        bumpedAtNanos = now()
    }

    fun level(): Float {
        if (bumpedAtNanos == 0L) return 0f
        val age = (now() - bumpedAtNanos) / 1e9
        return (peak * exp(-2.5 * age)).toFloat()
    }
}

/**
 * TTS playback envelope: each spoken segment start bumps the level to full; it decays as
 * `0.25 + 0.75*exp(-3.0*age)` between segments, so a continuously-speaking assistant stays near the
 * top while a pause relaxes it. Mirrors Flutter's `_ttsEnvelope`.
 */
class TtsEnvelope(private val now: () -> Long = System::nanoTime) {
    private var bumpedAtNanos = 0L

    fun bump() {
        bumpedAtNanos = now()
    }

    fun level(): Float {
        if (bumpedAtNanos == 0L) return 0f
        val age = (now() - bumpedAtNanos) / 1e9
        return (0.25 + 0.75 * exp(-3.0 * age)).toFloat()
    }
}
