package app.lawnchair.nexus.voice

/**
 * How loud a clip of Ogg/Opus speech is over time, read from the sizes of its Opus packets without
 * decoding them. The same as `SpeechEnvelope` in `nexus_mobile/lib/core/audio/speech_envelope.dart`.
 *
 * nexus-talk's Opus is variable-bitrate: a 20 ms packet is a few dozen bytes while the voice rests
 * and two or three times that on a stressed syllable. Over real replies the size followed the
 * decoded loudness in dB with a correlation of 0.8 to 0.9 once smoothed over 60 ms (two voices,
 * measured 2026-11-18), which is enough for the orb to move with the syllables and rest in the
 * pauses.
 */
class SpeechEnvelope private constructor(
    /** When each packet starts, in milliseconds of playback, ascending. */
    private val startsMs: DoubleArray,
    private val endMs: Double,
    /** How loud each packet is, 0 (the clip's quiet) to 1 (its loud). */
    private val levels: FloatArray,
) {
    /** How many packets the clip has. */
    val size: Int get() = levels.size

    /** The level at [positionMs] into the clip; 0 before it starts and after it ends. */
    fun at(positionMs: Double): Float {
        if (levels.isEmpty() || positionMs < startsMs[0] || positionMs >= endMs) return 0f
        var low = 0
        var high = startsMs.size - 1
        while (low < high) {
            val mid = (low + high + 1) ushr 1
            if (startsMs[mid] <= positionMs) low = mid else high = mid - 1
        }
        return levels[low]
    }

    companion object {
        /**
         * The envelope of [ogg], or null when it is not Ogg/Opus, holds no audio, or is
         * constant-bitrate (every packet the same size says nothing of the voice).
         */
        fun fromOgg(ogg: ByteArray): SpeechEnvelope? {
            val sizes = ArrayList<Double>()
            val durations = ArrayList<Double>()
            var preSkipMs = 0.0
            var packet = 0
            var length = 0
            var start = 0
            var page = 0
            fun byte(i: Int) = ogg[i].toInt() and 0xff
            while (page + 27 <= ogg.size) {
                if (byte(page) != 0x4f || byte(page + 1) != 0x67 || byte(page + 2) != 0x67 || byte(page + 3) != 0x53) {
                    return null
                }
                val segments = byte(page + 26)
                var at = page + 27 + segments
                if (at > ogg.size) return null
                for (s in 0 until segments) {
                    val lace = byte(page + 27 + s)
                    if (length == 0) start = at
                    length += lace
                    at += lace
                    if (at > ogg.size) return null
                    if (lace == 255) continue
                    if (packet == 0) {
                        // OpusHead: the samples (at 48 kHz) the decoder drops first.
                        if (length < 19 || !isOpusHead(ogg, start)) return null
                        preSkipMs = (byte(start + 10) or (byte(start + 11) shl 8)) / 48.0
                    } else if (packet > 1 && length > 0) {
                        // Packet 1 is OpusTags; from 2 on, audio.
                        val ms = packetMs(byte(start), if (length > 1) byte(start + 1) else 0)
                        if (ms > 0) {
                            durations.add(ms)
                            // Bytes per 20 ms, so packets of other lengths compare.
                            sizes.add(length * 20 / ms)
                        }
                    }
                    packet++
                    length = 0
                }
                page = at
            }
            if (sizes.isEmpty()) return null

            val sorted = sizes.sorted()
            val quiet = sorted[(sorted.size * 0.10).toInt()]
            val loud = sorted[Math.round((sorted.size - 1) * 0.95).toInt()]
            if (loud - quiet < 1) return null

            val raw = DoubleArray(sizes.size) { ((sizes[it] - quiet) / (loud - quiet)).coerceIn(0.0, 1.0) }
            // A packet and its neighbours: one 20 ms packet is noisier than the voice.
            val levels = FloatArray(raw.size) { i ->
                ((raw[if (i > 0) i - 1 else i] + raw[i] + raw[if (i < raw.size - 1) i + 1 else i]) / 3).toFloat()
            }
            val starts = DoubleArray(durations.size)
            var time = -preSkipMs
            for (i in durations.indices) {
                starts[i] = time
                time += durations[i]
            }
            return SpeechEnvelope(starts, time, levels)
        }

        private fun isOpusHead(b: ByteArray, at: Int): Boolean =
            b[at] == 'O'.code.toByte() && b[at + 1] == 'p'.code.toByte() && b[at + 2] == 'u'.code.toByte() &&
                b[at + 3] == 's'.code.toByte() && b[at + 4] == 'H'.code.toByte()

        /** How long a packet plays, from its TOC byte (RFC 6716, 3.1) and, for many frames, the next. */
        private fun packetMs(toc: Int, next: Int): Double {
            val config = toc shr 3
            val frame = when {
                config < 12 -> doubleArrayOf(10.0, 20.0, 40.0, 60.0)[config % 4]
                config < 16 -> if (config % 2 == 0) 10.0 else 20.0
                else -> doubleArrayOf(2.5, 5.0, 10.0, 20.0)[config % 4]
            }
            val frames = when (toc and 3) {
                0 -> 1
                1, 2 -> 2
                else -> next and 0x3f
            }
            return frame * frames
        }
    }
}
