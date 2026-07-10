package app.lawnchair.nexuslauncher

import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * ⚠️ SYNC NOTE — this is a hand-maintained Compose duplicate of the Flutter neural orb in
 * `nexus_mobile/lib/core/orb/` (orb.dart, orb_painter.dart, neural_geometry.dart). The two are meant
 * to look **identical**: whenever the Flutter orb changes, mirror the change here (and vice-versa).
 * The section structure below parallels the Dart files so diffs line up; all state targets, easing
 * constants, and brightness math are copied number-for-number. Node placement is only statistically
 * identical (Kotlin and Dart PRNGs differ for the same seed) — the identity lives in the motion.
 *
 * A seeded ball of 620 radius-biased nodes joined by short-range edges — the "neural brain" —
 * rotating about yaw with a fixed 0.4 forward tilt. Voice state drives the network: an amplitude
 * signal enters at the core and travels outward shell-by-shell as a brightness/size wave, thinking
 * adds an azimuthal shimmer, searching a sonar ping, loading morphs the outer nodes into a tilted
 * orbital comet ring, and synapse pulses race along the edges. State changes cross-fade via
 * [NetworkMotion]'s exponential easing — no hard breaks.
 */
enum class OrbState { Idle, Listening, Thinking, Speaking, Searching, Loading, Muted }

@Composable
fun NeuralOrb(
    modifier: Modifier = Modifier,
    accent: Color = Color(0xFF7AB8FF),
    state: OrbState = OrbState.Idle,
    glowIntensity: Float = 0.6f,
    externalAmplitude: (() -> Float?)? = null,
    failureSignal: Int = 0,
) {
    val sim = remember { OrbSim(neuralSphere) }
    val curState = rememberUpdatedState(state)
    val curExternal = rememberUpdatedState(externalAmplitude)
    val curFailure = rememberUpdatedState(failureSignal)
    val frameTick = remember { mutableLongStateOf(0L) }

    LaunchedEffect(Unit) {
        var start = 0L
        while (true) {
            withFrameNanos { now ->
                if (start == 0L) start = now
                val time = (now - start) / 1e9
                sim.tick(time, curState.value, curExternal.value?.invoke(), curFailure.value)
                frameTick.longValue = now
            }
        }
    }

    Spacer(
        modifier.drawBehind {
            frameTick.longValue // draw-phase dependency: repaint every frame, no recomposition
            drawNeuralOrb(sim, accent, glowIntensity)
        },
    )
}

/** Convenience overload for binary call sites (news screen): loading → the orbital wheel. */
@Composable
fun NeuralOrb(
    modifier: Modifier = Modifier,
    accent: Color = Color(0xFF7AB8FF),
    loading: Boolean,
) = NeuralOrb(modifier, accent, if (loading) OrbState.Loading else OrbState.Idle)

// ─────────────────────────────────────────────────────────────────────────────
// Geometry (port of neural_geometry.dart — NeuralSphere)
// ─────────────────────────────────────────────────────────────────────────────

/** Shared immutable geometry; only the per-instance scratch buffers are mutable. */
private val neuralSphere: NeuralSphere by lazy { NeuralSphere.generate() }

private class NeuralSphere private constructor(
    val px: FloatArray,
    val py: FloatArray,
    val pz: FloatArray,
    /** Cached |p| per node, 0..1. */
    val radius: FloatArray,
    /** Node → shell index 0..11 (concentric radial bands) — drives the amplitude wave. */
    val shellOf: IntArray,
    /** Node → static size bin 0..3, biased larger toward the center. */
    val sizeBinOf: IntArray,
    /** Edge endpoints (node indices), edgeA[i] < edgeB[i]. */
    val edgeA: IntArray,
    val edgeB: IntArray,
    /**
     * CSR-style outward adjacency for the synapse pulses: node i's outward edges are
     * `outEdges[outOffsets[i] until outOffsets[i+1]]`, each an index into [edgeA]/[edgeB] whose
     * *other* endpoint has strictly larger radius (walks always move core → rim and terminate).
     */
    val outOffsets: IntArray,
    val outEdges: IntArray,
) {
    val nodeCount get() = px.size
    val edgeCount get() = edgeA.size

    companion object {
        const val SIZE_BINS = 4

        fun generate(
            nodeCount: Int = 620,
            seed: Int = 1337,
            shellCount: Int = 12,
            neighbors: Int = 4,
            maxEdgeLength: Double = 0.35,
        ): NeuralSphere {
            val rng = Random(seed)
            val px = FloatArray(nodeCount)
            val py = FloatArray(nodeCount)
            val pz = FloatArray(nodeCount)
            val radius = FloatArray(nodeCount)
            val shellOf = IntArray(nodeCount)
            val sizeBinOf = IntArray(nodeCount)

            for (i in 0 until nodeCount) {
                // Uniform direction on the sphere...
                val z = 2 * rng.nextDouble() - 1
                val phi = 2 * PI * rng.nextDouble()
                val s = sqrt(1 - z * z)
                // ...with radius biased toward the center; clamped off the exact origin.
                val r = rng.nextDouble().pow(0.55).coerceIn(0.08, 1.0)
                px[i] = (s * cos(phi) * r).toFloat()
                py[i] = (s * sin(phi) * r).toFloat()
                pz[i] = (z * r).toFloat()
                radius[i] = r.toFloat()
                shellOf[i] = (r * shellCount).toInt().coerceIn(0, shellCount - 1)
                // Inner nodes draw from larger bins; seeded jitter adds variety.
                val bias = (1 - r) * (SIZE_BINS - 1)
                sizeBinOf[i] =
                    (bias + (rng.nextDouble() - 0.5) * 1.6).roundToInt().coerceIn(0, SIZE_BINS - 1)
            }

            // k-nearest edges, deduped (a < b), long ones dropped. O(n²), once at init.
            val edgeSet = HashSet<Int>()
            val maxD2 = maxEdgeLength * maxEdgeLength
            val bestIdx = IntArray(neighbors)
            val bestD2 = DoubleArray(neighbors)
            for (i in 0 until nodeCount) {
                for (n in 0 until neighbors) {
                    bestIdx[n] = -1
                    bestD2[n] = Double.MAX_VALUE
                }
                for (j in 0 until nodeCount) {
                    if (j == i) continue
                    val dx = (px[i] - px[j]).toDouble()
                    val dy = (py[i] - py[j]).toDouble()
                    val dz = (pz[i] - pz[j]).toDouble()
                    val d2 = dx * dx + dy * dy + dz * dz
                    if (d2 >= bestD2[neighbors - 1]) continue
                    // Insertion into the tiny sorted best-k list.
                    var n = neighbors - 1
                    while (n > 0 && bestD2[n - 1] > d2) {
                        bestD2[n] = bestD2[n - 1]
                        bestIdx[n] = bestIdx[n - 1]
                        n--
                    }
                    bestD2[n] = d2
                    bestIdx[n] = j
                }
                for (n in 0 until neighbors) {
                    val j = bestIdx[n]
                    if (j < 0 || bestD2[n] > maxD2) continue
                    val a = min(i, j)
                    val b = max(i, j)
                    edgeSet.add(a * nodeCount + b)
                }
            }
            val sorted = edgeSet.toIntArray().also { it.sort() }
            val edgeA = IntArray(sorted.size) { sorted[it] / nodeCount }
            val edgeB = IntArray(sorted.size) { sorted[it] % nodeCount }

            // Outward adjacency (CSR): each edge is outward from its smaller-radius endpoint.
            val outOffsets = IntArray(nodeCount + 1)
            for (e in edgeA.indices) {
                val a = edgeA[e]
                val b = edgeB[e]
                if (radius[a] < radius[b]) outOffsets[a + 1]++
                else if (radius[b] < radius[a]) outOffsets[b + 1]++
            }
            for (i in 0 until nodeCount) outOffsets[i + 1] += outOffsets[i]
            val outEdges = IntArray(outOffsets[nodeCount])
            val cursor = IntArray(nodeCount)
            for (e in edgeA.indices) {
                val a = edgeA[e]
                val b = edgeB[e]
                val from = when {
                    radius[a] < radius[b] -> a
                    radius[b] < radius[a] -> b
                    else -> continue
                }
                outEdges[outOffsets[from] + cursor[from]] = e
                cursor[from]++
            }

            return NeuralSphere(
                px, py, pz, radius, shellOf, sizeBinOf, edgeA, edgeB, outOffsets, outEdges,
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Simulation (ports of orb.dart _SimulatedAudio + orb_painter.dart NetworkMotion,
// AmplitudeTrail, SynapsePulses)
// ─────────────────────────────────────────────────────────────────────────────

/** Replicates the Flutter orb's flip-noise / dual-sine amplitude curves. */
private class SimulatedAudio {
    var amplitude = 0.0
    private var target = 0.0
    private var lastFlip = 0.0
    private val rng = Random.Default

    fun tick(time: Double, state: OrbState, external: Float?) {
        if (external != null) {
            // A real signal (streamed tokens) replaces the synthetic curves;
            // the same smoothing below keeps it soft.
            target = external.toDouble().coerceIn(0.0, 1.0)
            amplitude += (target - amplitude) * 0.18
            return
        }
        when (state) {
            OrbState.Listening -> if (time - lastFlip > 0.09) {
                target = 0.25 + rng.nextDouble() * 0.75
                lastFlip = time
            }
            OrbState.Speaking -> target = 0.5 + sin(time * 4.2) * 0.3 + sin(time * 7.1) * 0.15
            OrbState.Thinking -> target = 0.35 + sin(time * 1.6) * 0.1
            OrbState.Searching -> target = 0.42 + sin(time * 2.6) * 0.12
            OrbState.Loading -> target = 0.4 + sin(time * 2.0) * 0.1
            OrbState.Muted -> target = 0.0
            OrbState.Idle -> target = 0.30 + sin(time * 1.0) * 0.16
        }
        amplitude += (target - amplitude) * 0.18
    }
}

/**
 * Per-frame eased motion state. Every value glides toward its per-state target with
 * frame-rate-independent exponential smoothing, so state changes *cross-fade* the network's
 * behavior instead of popping it. Speed-like values feed phase accumulators.
 */
private class NetworkMotion {
    // ── eased per-state parameters ──────────────────────────────────────
    var yawSpeed = 0.12
    var waveDuration = 1.4
    var waveGain = 0.5
    var baseBrightness = 0.55
    var glowBoost = 0.0
    var shimmer = 0.0 // thinking: brightness band sweeping by azimuth
    var sonar = 0.0 // searching: bright shell expanding core → rim
    var wheel = 0.0 // loading: outer nodes morph into an orbital comet ring
    var hotCore = 0.0 // speaking: brightest nodes trend further toward white
    var tintThink = 0.0 // thinking: accent shifts toward violet
    var desat = 0.0 // muted: accent desaturates

    // ── one-shot event scalars (set on state transitions, decay per tick) ─
    var burstRing = 0.0 // turn start: thin ring expanding past the rim
    var whiteFlash = 0.0 // turn completion: brief whole-orb white bloom
    var errorFlash = 0.0 // failure: red flicker

    // ── phase accumulators ──────────────────────────────────────────────
    var yawPhase = 0.0
    var shimmerPhase = 0.0
    var sonarPhase = 0.0
    var wheelPhase = 0.0 // loading: the comet head's sweep angle

    private var prevState: OrbState? = null
    private var lastTime = 0.0

    fun tick(time: Double, s: OrbState) {
        val dt = (time - lastTime).coerceIn(0.0, 0.1)
        lastTime = time
        // ≈ a ~400ms ease-out, frame-rate independent.
        val k = 1 - exp(-dt * 8.0)
        fun go(v: Double, target: Double) = v + (target - v) * k

        yawSpeed = go(
            yawSpeed,
            when (s) {
                OrbState.Idle -> 0.12
                OrbState.Listening, OrbState.Loading -> 0.18
                OrbState.Speaking -> 0.22
                OrbState.Thinking -> 0.45
                OrbState.Searching -> 0.25
                OrbState.Muted -> 0.0
            },
        )
        waveDuration = go(
            waveDuration,
            when (s) {
                OrbState.Idle, OrbState.Muted -> 1.4
                OrbState.Listening -> 0.55
                OrbState.Speaking -> 0.45
                OrbState.Thinking -> 1.0
                OrbState.Searching, OrbState.Loading -> 0.9
            },
        )
        waveGain = go(
            waveGain,
            when (s) {
                OrbState.Idle -> 0.5
                OrbState.Listening -> 1.0
                OrbState.Speaking -> 1.2
                OrbState.Thinking -> 0.3
                OrbState.Searching -> 0.5
                OrbState.Loading -> 0.4
                OrbState.Muted -> 0.0
            },
        )
        baseBrightness = go(
            baseBrightness,
            when (s) {
                OrbState.Idle -> 0.55
                OrbState.Listening -> 0.65
                OrbState.Speaking -> 0.7
                OrbState.Thinking, OrbState.Searching, OrbState.Loading -> 0.6
                OrbState.Muted -> 0.25
            },
        )
        glowBoost = go(
            glowBoost,
            when (s) {
                OrbState.Idle -> 0.0
                OrbState.Listening, OrbState.Searching, OrbState.Loading -> 0.1
                OrbState.Speaking -> 0.25
                OrbState.Thinking -> 0.05
                OrbState.Muted -> -0.15
            },
        )
        shimmer = go(shimmer, if (s == OrbState.Thinking) 1.0 else 0.0)
        sonar = go(sonar, if (s == OrbState.Searching) 1.0 else 0.0)
        wheel = go(wheel, if (s == OrbState.Loading) 1.0 else 0.0)
        hotCore = go(hotCore, if (s == OrbState.Speaking) 1.0 else 0.0)
        tintThink = go(tintThink, if (s == OrbState.Thinking) 1.0 else 0.0)
        desat = go(desat, if (s == OrbState.Muted) 1.0 else 0.0)

        // One-shot event scalars: bursts fire on the transitions that mark
        // conversation moments, then decay every tick.
        if (prevState != s) {
            if (prevState == OrbState.Idle &&
                (s == OrbState.Listening || s == OrbState.Thinking || s == OrbState.Loading)
            ) {
                burstRing = 1.0 // turn start
            }
            if (prevState == OrbState.Speaking && s == OrbState.Idle) {
                whiteFlash = 0.45 // completion bloom
            }
            prevState = s
        }
        burstRing = max(0.0, burstRing - dt / 0.6)
        whiteFlash *= exp(-3.0 * dt)
        errorFlash *= exp(-2.5 * dt)

        yawPhase += dt * yawSpeed
        shimmerPhase += dt * 2.6
        sonarPhase += dt * 0.9
        wheelPhase += dt * 2.2
    }
}

/**
 * Ring buffer of recent amplitude samples. Shells sample it delayed by their radial distance,
 * turning the plain amplitude signal into a wave travelling core → rim.
 */
private class AmplitudeTrail {
    private val n = 128 // ~2s of history at 60fps
    private val amp = FloatArray(n)
    private val time = FloatArray(n)
    private var head = 0
    private var count = 0

    fun push(t: Double, amplitude: Double) {
        head = (head + 1) % n
        amp[head] = amplitude.toFloat()
        time[head] = t.toFloat()
        if (count < n) count++
    }

    fun sample(now: Double, delay: Double): Float {
        if (count == 0) return 0f
        val target = (now - delay).toFloat()
        var idx = head
        for (step in 0 until count - 1) {
            val prev = (idx - 1 + n) % n
            if (time[prev] <= target) {
                val span = time[idx] - time[prev]
                if (span <= 0f) return amp[idx]
                val t = ((target - time[prev]) / span).coerceIn(0f, 1f)
                return amp[prev] + (amp[idx] - amp[prev]) * t
            }
            idx = prev
        }
        return amp[idx]
    }
}

/**
 * Pooled light packets racing along edges core → rim — the "synapse firing" layer. Spawn rate
 * follows amplitude and state so speech visibly fires the network. Fixed pool, no allocation.
 */
private class SynapsePulses(private val sphere: NeuralSphere) {
    companion object {
        const val MAX_PULSES = 24
        private const val HOPS_PER_SECOND = 3.5
        const val MAX_HOPS = 7
    }

    private val rng = Random(7)
    private val coreNodes: IntArray = (0 until sphere.nodeCount)
        .filter { sphere.radius[it] < 0.35f && sphere.outOffsets[it + 1] > sphere.outOffsets[it] }
        .toIntArray()

    /** Per-pulse: current edge, entry endpoint, progress 0..1, hops left (0 = slot free). */
    val edge = IntArray(MAX_PULSES)
    val from = IntArray(MAX_PULSES)
    val progress = FloatArray(MAX_PULSES)
    private val hopsLeft = IntArray(MAX_PULSES)

    /** Visited-node history per pulse (`path[p*(MAX_HOPS+1) + h]`) + hops taken so
     * far — lets the trail curve back across previous edges, not just the current. */
    private val path = IntArray(MAX_PULSES * (MAX_HOPS + 1))
    private val hopsDoneArr = IntArray(MAX_PULSES)

    private var rate = 0.0
    private var accum = 0.0
    private var lastTime = 0.0

    fun isActive(p: Int) = hopsLeft[p] > 0

    fun otherEnd(p: Int) = other(edge[p], from[p])

    /** Monotonic distance travelled since spawn, in hop units. */
    fun traveled(p: Int) = hopsDoneArr[p] + progress[p]

    fun hopsDone(p: Int) = hopsDoneArr[p]

    fun pathNode(p: Int, h: Int) = path[p * (MAX_HOPS + 1) + h]

    fun tick(time: Double, s: OrbState, amplitude: Double) {
        val dt = (time - lastTime).coerceIn(0.0, 0.1)
        lastTime = time
        val gain = when (s) {
            OrbState.Speaking -> 12.0
            OrbState.Listening -> 8.0
            OrbState.Searching -> 5.0
            OrbState.Loading -> 4.0
            OrbState.Thinking -> 2.5
            OrbState.Idle -> 1.5
            OrbState.Muted -> 0.0
        }
        // Same easing family as NetworkMotion so state changes don't pop.
        val k = 1 - exp(-dt * 8.0)
        rate += (gain * (0.35 + amplitude) - rate) * k

        for (p in 0 until MAX_PULSES) {
            if (hopsLeft[p] <= 0) continue
            progress[p] += (dt * HOPS_PER_SECOND).toFloat()
            while (progress[p] >= 1f) {
                progress[p] -= 1f
                val to = other(edge[p], from[p])
                val begin = sphere.outOffsets[to]
                val end = sphere.outOffsets[to + 1]
                if (--hopsLeft[p] <= 0 || begin == end) {
                    hopsLeft[p] = 0 // reached the rim (or ran out of hops)
                    break
                }
                from[p] = to
                hopsDoneArr[p]++
                path[p * (MAX_HOPS + 1) + hopsDoneArr[p]] = to
                edge[p] = sphere.outEdges[begin + rng.nextInt(end - begin)]
            }
        }

        accum += rate * dt
        while (accum >= 1) {
            accum -= 1
            spawn()
        }
    }

    private fun spawn() {
        if (coreNodes.isEmpty()) return
        for (p in 0 until MAX_PULSES) {
            if (hopsLeft[p] > 0) continue
            val n = coreNodes[rng.nextInt(coreNodes.size)]
            val begin = sphere.outOffsets[n]
            edge[p] = sphere.outEdges[begin + rng.nextInt(sphere.outOffsets[n + 1] - begin)]
            from[p] = n
            progress[p] = 0f
            hopsLeft[p] = MAX_HOPS
            hopsDoneArr[p] = 0
            path[p * (MAX_HOPS + 1)] = n
            return
        }
    }

    private fun other(e: Int, node: Int) =
        if (sphere.edgeA[e] == node) sphere.edgeB[e] else sphere.edgeA[e]
}

// ─────────────────────────────────────────────────────────────────────────────
// Per-instance simulation + preallocated scratch (port of OrbPaintBuffers)
// ─────────────────────────────────────────────────────────────────────────────

private const val SHELL_COUNT = 12
private const val ALPHA_BINS = 4
private const val TWO_PI = 2 * PI

/** Fading synapse trail resolution: each pulse's tail is drawn as this many
 * short segments, dimmer and thinner the further back they sit. */
private const val TRAIL_BANDS = 12

/** Node radius (reference px at size 260) per static size bin. */
private val BIN_WIDTH = floatArrayOf(1.8f, 2.6f, 3.4f, 4.4f)

private class OrbSim(val sphere: NeuralSphere) {
    val audio = SimulatedAudio()
    val motion = NetworkMotion()
    val trail = AmplitudeTrail()
    val pulses = SynapsePulses(sphere)

    private var lastFailureSignal = 0

    // Scratch reused every frame — the draw never allocates.
    val sx = FloatArray(sphere.nodeCount)
    val sy = FloatArray(sphere.nodeCount)
    val nodeAlpha = FloatArray(sphere.nodeCount)
    val azimuth = FloatArray(sphere.nodeCount) { atan2(sphere.pz[it], sphere.px[it]) }
    val ringCos = FloatArray(sphere.nodeCount) { cos(azimuth[it]) }
    val ringSin = FloatArray(sphere.nodeCount) { sin(azimuth[it]) }
    val activations = FloatArray(SHELL_COUNT)
    val nodeBins = Array(NeuralSphere.SIZE_BINS * ALPHA_BINS) { FloatArray(sphere.nodeCount * 2) }
    val nodeBinCounts = IntArray(NeuralSphere.SIZE_BINS * ALPHA_BINS)
    val edgeBins = Array(ALPHA_BINS) { FloatArray(sphere.edgeCount * 4) }
    val edgeBinCounts = IntArray(ALPHA_BINS)
    val pulsePts = FloatArray(SynapsePulses.MAX_PULSES * 2)
    // One trail-segment buffer per band (x0,y0,x1,y1), band 0 nearest the head.
    val pulseTrail = Array(TRAIL_BANDS) { FloatArray(SynapsePulses.MAX_PULSES * 4) }
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
    }

    fun tick(t: Double, state: OrbState, external: Float?, failureSignal: Int) {
        if (failureSignal != lastFailureSignal) {
            lastFailureSignal = failureSignal
            motion.errorFlash = 1.0
        }
        audio.tick(t, state, external)
        motion.tick(t, state)
        trail.push(t, audio.amplitude)
        pulses.tick(t, state, audio.amplitude)
        // Each shell samples the amplitude delayed by its radius, so a pulse
        // appears at the core and travels to the rim over waveDuration.
        for (s in 0 until SHELL_COUNT) {
            activations[s] = trail.sample(t, s.toDouble() / SHELL_COUNT * motion.waveDuration)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Draw routine (port of OrbPainter.paint)
// ─────────────────────────────────────────────────────────────────────────────

/** Fixed forward lean so the rotation axis never reads as a flat spinner. */
private const val BASE_TILT = 0.4

private fun DrawScope.drawNeuralOrb(sim: OrbSim, accent: Color, glowIntensity: Float) {
    val cx = size.width / 2f
    val cy = size.height / 2f
    val s = min(size.width, size.height)
    val m = sim.motion
    val sphere = sim.sphere
    val n = sphere.nodeCount
    val projR = s * 0.36f
    val amplitude = sim.audio.amplitude.toFloat()
    // Flutter's px constants are tuned at reference size 260; scale everything
    // with the actual orb so 28dp headers and 112dp+ pages both look right.
    val unit = s / 260f

    // ── living color: one effective accent for the whole network ───────
    var eff = accent
    if (m.tintThink > 0.01) eff = lerpSrgb(eff, Color(0xFFB794F6), (0.35 * m.tintThink).toFloat())
    if (m.desat > 0.01) eff = lerpSrgb(eff, Color(0xFF9AA3AE), (0.5 * m.desat).toFloat())
    if (m.whiteFlash > 0.01) eff = lerpSrgb(eff, Color.White, m.whiteFlash.toFloat().coerceIn(0f, 1f))
    if (m.errorFlash > 0.01) eff = lerpSrgb(eff, Color(0xFFFF5A5A), (0.8 * m.errorFlash).toFloat())
    // Hot-core color for the brightest bin and the synapse pulses; speaking
    // pushes it further toward white. Per-channel sRGB lerp to match Flutter.
    val hot = lerpSrgb(eff, Color.White, (0.55 + 0.20 * m.hotCore).toFloat())

    // ── halo bloom behind the network ──────────────────────────────────
    if (glowIntensity > 0.01f) {
        val haloAlpha = (glowIntensity * (0.22f + amplitude * 0.30f + m.glowBoost.toFloat()) +
            m.whiteFlash.toFloat() * 0.3f).coerceIn(0f, 1f)
        drawRect(
            brush = Brush.radialGradient(
                0.0f to eff.copy(alpha = haloAlpha),
                0.45f to eff.copy(alpha = haloAlpha * 0.35f),
                1.0f to eff.copy(alpha = 0f),
                center = Offset(cx, cy),
                radius = s * (0.48f + amplitude * 0.03f), // breathes with the shockwave
            ),
        )
    }

    // ── turn-start supernova: thin ring expanding past the rim ─────────
    if (m.burstRing > 0.01) {
        val t = (1 - m.burstRing).toFloat() // 0 → 1 over the burst's life
        drawCircle(
            color = eff.copy(alpha = (m.burstRing * 0.5).toFloat()),
            radius = projR * (1.0f + 0.5f * t),
            center = Offset(cx, cy),
            style = Stroke(width = 2.0f * unit),
        )
    }

    // ── rotate, project, light every node ──────────────────────────────
    val yaw = m.yawPhase
    val cyaw = cos(yaw).toFloat()
    val syaw = sin(yaw).toFloat()
    val cp = cos(BASE_TILT).toFloat()
    val sp = sin(BASE_TILT).toFloat()
    sim.nodeBinCounts.fill(0)
    sim.edgeBinCounts.fill(0)

    val sonarShell = ((m.sonarPhase % 1.0) * SHELL_COUNT).toFloat()
    val sonarFade = (1.15 - m.sonarPhase % 1.0).toFloat()
    val waveGain = m.waveGain.toFloat()
    val baseBrightness = m.baseBrightness.toFloat()
    val shimmer = m.shimmer.toFloat()
    val sonar = m.sonar.toFloat()
    val wheel = m.wheel.toFloat()
    val whiteFlash = m.whiteFlash.toFloat()

    for (i in 0 until n) {
        val r = sphere.radius[i]
        val shell = sphere.shellOf[i]
        val act = sim.activations[shell]
        // Geometric shockwave: the wave physically swells each shell as it
        // passes through, so loud moments ripple the sphere's shape.
        val swell = 1 + waveGain * act * 0.06f
        var x = sphere.px[i] * swell
        var y = sphere.py[i] * swell
        var z = sphere.pz[i] * swell
        val isRing = wheel > 0.01f && r > 0.55f
        if (isRing) {
            // Loading wheel: outer nodes glide to a thin orbital band in the
            // sphere's own xz-plane — the yaw+tilt projection below renders it
            // as a tilted ellipse (gyroscope ring, never a flat circle).
            val tx = sim.ringCos[i] * 0.88f
            val tz = sim.ringSin[i] * 0.88f
            val ty = y * 0.12f
            x += (tx - x) * wheel
            y += (ty - y) * wheel
            z += (tz - z) * wheel
        }
        val x1 = x * cyaw + z * syaw
        val z1 = -x * syaw + z * cyaw
        val y2 = y * cp - z1 * sp
        val z2 = y * sp + z1 * cp
        val persp = 1 / (1 - z2 * 0.25f)
        sim.sx[i] = cx + x1 * projR * persp
        sim.sy[i] = cy + y2 * projR * persp
        val depth = 0.55f + 0.45f * (z2 + 1) / 2f

        // Inner nodes brightest; the traveling amplitude wave lights each
        // shell as it passes through it.
        var bright = baseBrightness * (0.45f + 0.55f * (1 - r)) * (1 + waveGain * act)
        if (shimmer > 0.02f) {
            // Thinking: a brightness band sweeping around the sphere by azimuth.
            val band = 0.5f + 0.5f * sin(sim.azimuth[i] + m.shimmerPhase.toFloat())
            bright += shimmer * 0.45f * band * band
        }
        if (sonar > 0.02f) {
            // Searching: a bright shell expanding core → rim, fading as it goes.
            val d = kotlin.math.abs(shell - sonarShell)
            if (d < 1.5f) bright += sonar * (1 - d / 1.5f) * 0.7f * sonarFade
        }
        if (wheel > 0.01f) {
            if (isRing) {
                // Comet asymmetry: a hot head sweeps the ring (wheelPhase), the
                // tail fades over ~270° behind it — a spinner with life, not a
                // uniform pale halo.
                var behind = ((m.wheelPhase - sim.azimuth[i]) % TWO_PI).toFloat()
                if (behind < 0) behind += TWO_PI.toFloat()
                val tail = (1 - behind / (1.5f * PI.toFloat())).coerceIn(0f, 1f)
                val comet = 0.15f + 0.85f * tail * tail
                bright = bright * (1 - wheel) + comet * 1.1f * wheel
            } else {
                bright *= 1 - 0.5f * wheel // core dims but keeps breathing
            }
        }
        bright += whiteFlash // completion bloom lifts everything briefly
        val alpha = (bright * depth).coerceIn(0f, 1f)
        sim.nodeAlpha[i] = alpha
        if (alpha < 0.05f) continue

        // Effective size bin: the wave fattens a node as it passes; rear
        // nodes drop a bin for a depth cue.
        var bin = sphere.sizeBinOf[i]
        if (waveGain * act > 0.55f) bin++
        if (z2 < -0.35f) bin--
        bin = bin.coerceIn(0, NeuralSphere.SIZE_BINS - 1)

        val aBin = (alpha * ALPHA_BINS).toInt().coerceIn(0, ALPHA_BINS - 1)
        val slot = bin * ALPHA_BINS + aBin
        val buf = sim.nodeBins[slot]
        val c = sim.nodeBinCounts[slot]
        buf[c * 2] = sim.sx[i]
        buf[c * 2 + 1] = sim.sy[i]
        sim.nodeBinCounts[slot] = c + 1
    }

    val canvas = drawContext.canvas.nativeCanvas
    val paint = sim.paint

    // ── edges: alpha from endpoint brightness, batched by bin ──────────
    // Skipped entirely when they'd be sub-half-pixel fuzz (28dp header orb).
    val edgeWidth = 0.7f * unit
    if (edgeWidth >= 0.4f) {
        for (e in 0 until sphere.edgeCount) {
            val a = sphere.edgeA[e]
            val bIdx = sphere.edgeB[e]
            var alpha = (sim.nodeAlpha[a] + sim.nodeAlpha[bIdx]) * 0.5f * 0.55f
            // Loading wheel: edges touching ring nodes fade out (they'd stretch
            // across the sphere as the ring forms); core-internal ones stay.
            if (wheel > 0.01f &&
                (sphere.radius[a] > 0.55f || sphere.radius[bIdx] > 0.55f)
            ) {
                alpha *= 1 - wheel
            }
            if (alpha < 0.03f) continue
            val aBin = (alpha * ALPHA_BINS).toInt().coerceIn(0, ALPHA_BINS - 1)
            val buf = sim.edgeBins[aBin]
            val c = sim.edgeBinCounts[aBin]
            buf[c * 4] = sim.sx[a]
            buf[c * 4 + 1] = sim.sy[a]
            buf[c * 4 + 2] = sim.sx[bIdx]
            buf[c * 4 + 3] = sim.sy[bIdx]
            sim.edgeBinCounts[aBin] = c + 1
        }
        paint.strokeCap = android.graphics.Paint.Cap.BUTT
        paint.strokeWidth = edgeWidth
        for (aBin in 0 until ALPHA_BINS) {
            val count = sim.edgeBinCounts[aBin]
            if (count == 0) continue
            paint.color = eff.copy(alpha = (aBin + 0.5f) / ALPHA_BINS).toArgb()
            canvas.drawLines(sim.edgeBins[aBin], 0, count * 4, paint)
        }
    }

    // ── nodes: one drawPoints per (size × alpha) bin ────────────────────
    paint.strokeCap = android.graphics.Paint.Cap.ROUND
    for (bin in 0 until NeuralSphere.SIZE_BINS) {
        val width = max(BIN_WIDTH[bin] * unit, 1f)
        for (aBin in 0 until ALPHA_BINS) {
            val slot = bin * ALPHA_BINS + aBin
            val count = sim.nodeBinCounts[slot]
            if (count == 0) continue
            val binAlpha = (aBin + 0.5f) / ALPHA_BINS
            // Soft glow under the brightest nodes — wide low-alpha pass in
            // place of a real blur (which would tank mid-range framerates).
            if (aBin == ALPHA_BINS - 1) {
                paint.strokeWidth = width * 2.6f
                paint.color = eff.copy(alpha = 0.16f).toArgb()
                canvas.drawPoints(sim.nodeBins[slot], 0, count * 2, paint)
            }
            // Brightest nodes trend toward white for a hot-core sparkle.
            paint.strokeWidth = width
            paint.color = (if (aBin == ALPHA_BINS - 1) hot else eff)
                .copy(alpha = binAlpha).toArgb()
            canvas.drawPoints(sim.nodeBins[slot], 0, count * 2, paint)
        }
    }

    // ── synapse pulses: hot packets racing along edges core → rim ──────
    // Each pulse is a bright head plus a fading trail — the "electrical
    // signal" itself — brightest at the node and dissipating behind it. The
    // trail is TRAIL_BANDS short segments (band 0 nearest the head) sampled
    // back along the edge and batched into one draw per band.
    val trailHops = 3.0f // how far back (in edge-hops) the trail reaches
    var headN = 0
    val pulses = sim.pulses
    // Screen position at travelled-distance x for pulse p, written into out[0/1].
    fun pointAt(p: Int, hopsDone: Int, other: Int, x: Float, out: FloatArray) {
        val h = x.toInt()
        val frac = x - h
        val near = pulses.pathNode(p, h)
        val far = if (h >= hopsDone) other else pulses.pathNode(p, h + 1)
        out[0] = sim.sx[near] + (sim.sx[far] - sim.sx[near]) * frac
        out[1] = sim.sy[near] + (sim.sy[far] - sim.sy[near]) * frac
    }
    val pA = FloatArray(2)
    val pB = FloatArray(2)
    for (p in 0 until SynapsePulses.MAX_PULSES) {
        if (!pulses.isActive(p)) continue
        val trav = pulses.traveled(p)
        val hopsDone = pulses.hopsDone(p)
        val other = pulses.otherEnd(p)
        for (k in 0 until TRAIL_BANDS) {
            pointAt(p, hopsDone, other, max(0f, trav - trailHops * k / TRAIL_BANDS), pA)
            pointAt(p, hopsDone, other, max(0f, trav - trailHops * (k + 1) / TRAIL_BANDS), pB)
            val buf = sim.pulseTrail[k]
            buf[headN * 4] = pA[0]
            buf[headN * 4 + 1] = pA[1]
            buf[headN * 4 + 2] = pB[0]
            buf[headN * 4 + 3] = pB[1]
        }
        pointAt(p, hopsDone, other, trav, pA)
        sim.pulsePts[headN * 2] = pA[0]
        sim.pulsePts[headN * 2 + 1] = pA[1]
        headN++
    }
    if (headN > 0) {
        // Draw the tail first (dimmest bands underneath), then the head on top.
        // Width scales with orb size but keeps a floor so it's legible small.
        paint.strokeCap = android.graphics.Paint.Cap.ROUND
        for (k in TRAIL_BANDS - 1 downTo 0) {
            val f = 1 - k.toFloat() / TRAIL_BANDS // 1 near head → fades to tail
            paint.strokeWidth = max((0.7f + 1.7f * f) * unit, 0.5f + 0.8f * f)
            paint.color = hot.copy(alpha = 0.5f * f).toArgb()
            canvas.drawLines(sim.pulseTrail[k], 0, headN * 4, paint)
        }
        paint.strokeWidth = max(BIN_WIDTH[3] * unit, 2.2f)
        paint.color = hot.copy(alpha = 0.85f).toArgb()
        canvas.drawPoints(sim.pulsePts, 0, headN * 2, paint)
    }
}

/** Per-channel sRGB lerp — matches Flutter's Color.lerp (Compose's lerp goes through Oklab). */
private fun lerpSrgb(a: Color, b: Color, t: Float): Color = Color(
    red = a.red + (b.red - a.red) * t,
    green = a.green + (b.green - a.green) * t,
    blue = a.blue + (b.blue - a.blue) * t,
    alpha = a.alpha + (b.alpha - a.alpha) * t,
)
