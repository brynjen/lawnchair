package app.lawnchair.nexuslauncher

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * ⚠️ SYNC NOTE — this is a hand-maintained Compose duplicate of the Flutter neural orb in
 * `nexus_mobile/lib/core/orb/` (orb.dart, orb_painter.dart, neural_geometry.dart). The two are meant
 * to look **identical**: whenever the Flutter orb changes, mirror the change here (and vice-versa).
 *
 * A seeded ball of radius-biased nodes with short-range edges, rotating about the vertical (Y) axis
 * with strong depth-based fade so nodes/links dim as they recede (the 3D feel). When [loading] is
 * true it eases into a "spinning wheel": each node slides radially outward to its *nearest* rim
 * position (a thin outer band), the whole thing spins faster, and any link that would cross the
 * centre fades out so only short band arcs remain. Eases back to the sphere when done. Geometry is
 * generated once; motion is per-frame.
 */
@Composable
fun NeuralOrb(
    modifier: Modifier = Modifier,
    accent: Color = Color(0xFF7AB8FF),
    loading: Boolean = false,
) {
    val geom = remember { generateOrb() }
    val load by animateFloatAsState(
        targetValue = if (loading) 1f else 0f,
        animationSpec = tween(durationMillis = 800),
        label = "orbLoad",
    )
    var angleY by remember { mutableFloatStateOf(0f) }
    var spin by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) {
                    val dt = (now - last) / 1_000_000_000f
                    angleY = (angleY + SLOW_Y * dt) % TWO_PI // base globe rotation
                    spin = (spin + SPIN_SPEED * dt) % TWO_PI // clockwise band spinner
                }
                last = now
            }
        }
    }

    val n = geom.px.size
    val bx = remember { FloatArray(n) }
    val by = remember { FloatArray(n) }
    val ba = remember { FloatArray(n) }
    val bs = remember { FloatArray(n) }

    Canvas(modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val radius = min(cx, cy) * 0.92f
        val unit = size.minDimension / 112f
        val focal = 2.4f
        val cosY = cos(angleY)
        val sinY = sin(angleY)
        val l = load

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(accent.copy(alpha = 0.18f + 0.20f * l), Color.Transparent),
                center = Offset(cx, cy),
                radius = radius * 1.3f,
            ),
            radius = radius * 1.3f,
            center = Offset(cx, cy),
        )

        for (i in 0 until n) {
            // Globe projection (Y-axis rotation + perspective).
            val x = geom.px[i]
            val y = geom.py[i]
            val z = geom.pz[i]
            val xr = x * cosY + z * sinY
            val zr = -x * sinY + z * cosY
            val scale = focal / (focal - zr)
            val gx = cx + xr * radius * scale
            val gy = cy + y * radius * scale
            val gDepth = ((zr + 1f) / 2f).coerceIn(0f, 1f)

            // Wheel target: nodes sit in a thin outer band at their own angle and spin clockwise
            // together, forming a circular loading spinner.
            val wa = geom.wheelAngle[i] + spin
            val wr = geom.wheelR[i] * radius
            val wx = cx + cos(wa) * wr
            val wy = cy + sin(wa) * wr

            val depth = lerp(gDepth, 1f, l)
            bx[i] = lerp(gx, wx, l)
            by[i] = lerp(gy, wy, l)
            // Strong depth fade (3D): back nodes nearly vanish, front nodes bright.
            ba[i] = ((0.10f + 0.90f * depth.pow(1.3f)) * (1f + 0.5f * l)).coerceIn(0f, 1f)
            bs[i] = (0.5f + 1.9f * depth + 0.6f * l) * unit
        }

        // Fade all links out during the load animation (spinner has none); back in when done.
        for (e in geom.edgeA.indices) {
            val a = geom.edgeA[e]
            val b = geom.edgeB[e]
            val alpha = ((ba[a] + ba[b]) / 2f * 0.55f * (1f - l)).coerceIn(0f, 0.85f)
            drawLine(
                color = accent.copy(alpha = alpha),
                start = Offset(bx[a], by[a]),
                end = Offset(bx[b], by[b]),
                strokeWidth = 1.2f * unit,
            )
        }

        for (i in 0 until n) {
            drawCircle(
                color = accent.copy(alpha = ba[i]),
                radius = bs[i],
                center = Offset(bx[i], by[i]),
            )
        }
    }
}

private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

private const val TWO_PI = (2 * PI).toFloat()
private const val SLOW_Y = 0.26f // rad/s — base globe rotation
private const val SPIN_SPEED = 3.6f // rad/s — clockwise band spinner while loading

private class OrbGeom(
    val px: FloatArray,
    val py: FloatArray,
    val pz: FloatArray,
    val edgeA: IntArray,
    val edgeB: IntArray,
    val wheelAngle: FloatArray,
    val wheelR: FloatArray,
)

private fun generateOrb(
    nodeCount: Int = 360,
    seed: Int = 1337,
    neighbors: Int = 4,
    maxEdgeLength: Float = 0.34f,
): OrbGeom {
    val rng = Random(seed)
    val px = FloatArray(nodeCount)
    val py = FloatArray(nodeCount)
    val pz = FloatArray(nodeCount)
    val wheelAngle = FloatArray(nodeCount)
    val wheelR = FloatArray(nodeCount)
    for (i in 0 until nodeCount) {
        val z = 2f * rng.nextFloat() - 1f
        val phi = (2 * PI * rng.nextDouble()).toFloat()
        val s = sqrt(1f - z * z)
        val r = rng.nextDouble().pow(0.55).toFloat().coerceIn(0.08f, 1f)
        px[i] = s * cos(phi) * r
        py[i] = s * sin(phi) * r
        pz[i] = z * r
        // Stable band slot (keeps each node near its own side) + thin rim-ward radius.
        wheelAngle[i] = atan2(py[i], px[i])
        wheelR[i] = 0.80f + 0.16f * r
    }

    val edgeSet = HashSet<Long>()
    val maxD2 = maxEdgeLength * maxEdgeLength
    val bestIdx = IntArray(neighbors)
    val bestD2 = DoubleArray(neighbors)
    for (i in 0 until nodeCount) {
        for (nn in 0 until neighbors) {
            bestIdx[nn] = -1
            bestD2[nn] = Double.MAX_VALUE
        }
        for (j in 0 until nodeCount) {
            if (j == i) continue
            val dx = (px[i] - px[j]).toDouble()
            val dy = (py[i] - py[j]).toDouble()
            val dz = (pz[i] - pz[j]).toDouble()
            val d2 = dx * dx + dy * dy + dz * dz
            if (d2 > maxD2) continue
            var worst = 0
            for (nn in 1 until neighbors) if (bestD2[nn] > bestD2[worst]) worst = nn
            if (d2 < bestD2[worst]) {
                bestD2[worst] = d2
                bestIdx[worst] = j
            }
        }
        for (nn in 0 until neighbors) {
            val j = bestIdx[nn]
            if (j < 0) continue
            val a = min(i, j)
            val b = if (a == i) j else i
            edgeSet.add((a.toLong() shl 32) or (b.toLong() and 0xffffffffL))
        }
    }
    val edgeA = IntArray(edgeSet.size)
    val edgeB = IntArray(edgeSet.size)
    var k = 0
    for (key in edgeSet) {
        edgeA[k] = (key shr 32).toInt()
        edgeB[k] = (key and 0xffffffffL).toInt()
        k++
    }
    return OrbGeom(px, py, pz, edgeA, edgeB, wheelAngle, wheelR)
}
