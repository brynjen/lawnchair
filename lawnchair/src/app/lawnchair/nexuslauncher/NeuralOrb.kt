package app.lawnchair.nexuslauncher

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import app.lawnchair.nexus.box.BoxOrbState
import app.lawnchair.nexus.box.SubstrateMode
import kotlinx.coroutines.delay
import no.nordli.neuralorb.NeuralOrbView
import no.nordli.neuralorb.OrbController
import no.nordli.neuralorb.OrbMode
import no.nordli.neuralorb.OrbProcess
import no.nordli.neuralorb.OrbTheme
import no.nordli.neuralorb.OrbTuning

/**
 * The Nexus orb: the neural-orb AAR's [NeuralOrbView] (Swarm2, GLES 3) — the same renderer the
 * Flutter app embeds, so there is no hand-kept Compose copy of the orb any more. This file maps the
 * launcher's voice states onto it, the same mapping as `nexus_mobile/lib/core/orb/orb.dart`.
 */
enum class OrbState {
    Idle, Listening, Thinking, Speaking, Searching, Loading, Muted,

    /** The box is down. Dims and freezes the orb, as [Muted] does. */
    Offline,

    /** The box is up but has shut the door on a caller's token. The same look as [Offline], kept apart so callers say which. */
    Unauthorized,
}

/** The renderer's audio band count (the AAR keeps its spec internal). */
private const val AUDIO_BANDS = 16

private fun OrbState.mode(): OrbMode = when (this) {
    OrbState.Idle -> OrbMode.IDLE
    OrbState.Listening -> OrbMode.LISTENING
    OrbState.Thinking -> OrbMode.THINKING
    OrbState.Speaking -> OrbMode.SPEAKING
    OrbState.Searching -> OrbMode.TOOL_CALL
    OrbState.Loading -> OrbMode.LOADING
    OrbState.Muted, OrbState.Offline, OrbState.Unauthorized -> OrbMode.OFFLINE
}

/**
 * A substrate slot's mode as the renderer's: the server's first nine modes mirror [OrbMode] one for
 * one. `unauthorized` has no mode of its own and must not fall to idle (what [OrbMode.fromWireName]
 * would do), so it dims like offline; a mode this build does not know draws as processing, busy
 * without a claim about what.
 */
private fun SubstrateMode.orbMode(): OrbMode = when (this) {
    SubstrateMode.Idle -> OrbMode.IDLE
    SubstrateMode.Listening -> OrbMode.LISTENING
    SubstrateMode.Thinking -> OrbMode.THINKING
    SubstrateMode.Processing, SubstrateMode.Unknown -> OrbMode.PROCESSING
    SubstrateMode.Speaking -> OrbMode.SPEAKING
    SubstrateMode.ToolCall -> OrbMode.TOOL_CALL
    SubstrateMode.Loading -> OrbMode.LOADING
    SubstrateMode.Offline, SubstrateMode.Unauthorized -> OrbMode.OFFLINE
    SubstrateMode.Error -> OrbMode.ERROR
}

/**
 * Draws either [box], every slot in its own lane (a lane keeps its colour for the life of its
 * activity), or, with no box, [state] alone in lane 0. Always one call that replaces all the lanes,
 * so nothing of the other drawing is left behind.
 */
private fun OrbController.draw(state: OrbState, box: BoxOrbState?) {
    setProcesses(
        if (box == null) {
            listOf(OrbProcess(id = 0, mode = state.mode()))
        } else {
            box.drawable.map {
                OrbProcess(id = it.slot, label = it.label ?: "Task ${it.slot + 1}", mode = it.mode.orbMode())
            }
        },
    )
}

/** Dark ink on a light surface, light on a dark one, with [accent] leading the palette. */
private fun themeFor(dark: Boolean, accent: Color): OrbTheme {
    val base = if (dark) OrbTheme.MIDNIGHT else OrbTheme.DAYLIGHT
    val palette = base.palette.copyOf().also { it[0] = accent.toArgb() }
    return base.copy(id = "${base.id}-nexus", palette = palette)
}

/**
 * The glow setting (0..1; 0.6 is the renderer's own look) as bloom and node glow. [prominent] is the
 * look for a surface where the orb is the picture rather than an indicator: the swarm fills as much
 * of its view as the renderer allows (1.28) and its dots are larger (1.45, from 1.12), so on a big
 * page it reads as a sphere of ink and not a speckle.
 */
private fun tuningFor(theme: OrbTheme, glow: Float, prominent: Boolean): OrbTuning {
    val g = glow.coerceIn(0f, 1f)
    val base = mapOf(
        "bloomStrength" to (0.15f + g * 1.283f),
        "nodeGlow" to (0.25f + g * 1.333f),
    )
    val look = if (prominent) base + mapOf("orbScale" to 1.28f, "nodeSize" to 1.45f) else base
    return OrbTuning(theme.tuning.toMap() + look)
}

@Composable
fun NeuralOrb(
    modifier: Modifier = Modifier,
    accent: Color = Color(0xFF7AB8FF),
    state: OrbState = OrbState.Idle,
    glowIntensity: Float = 0.6f,
    /** The orb as the picture of a surface (see [tuningFor]): a bigger swarm with bigger dots. */
    prominent: Boolean = false,
    externalAmplitude: (() -> Float?)? = null,
    failureSignal: Int = 0,
    /** What the whole box is doing, drawn slot by slot in place of [state]; null draws [state] alone. */
    box: BoxOrbState? = null,
    /**
     * Light ink (the dark theme) or dark ink. Follows the system, but a surface that is dark whatever
     * the system says, as the News page is, must say so: dark ink on it is a faint speck.
     */
    dark: Boolean = isSystemInDarkTheme(),
) {
    val theme = remember(dark, accent) { themeFor(dark, accent) }
    val tuning = remember(theme, glowIntensity, prominent) { tuningFor(theme, glowIntensity, prominent) }
    val curState = rememberUpdatedState(state)
    val curBox = rememberUpdatedState(box)
    val curExternal = rememberUpdatedState(externalAmplitude)
    val view = remember { arrayOfNulls<NeuralOrbView>(1) }

    AndroidView(
        modifier = modifier,
        factory = { context -> NeuralOrbView(context).also { view[0] = it } },
        update = { orb ->
            orb.controller.setTheme(theme)
            orb.controller.setTuning(tuning)
            orb.controller.draw(state, box)
        },
        onRelease = { it.dispose() },
    )

    // A failure flashes the error mode, then returns to whatever state is current.
    LaunchedEffect(failureSignal) {
        if (failureSignal == 0) return@LaunchedEffect
        view[0]?.controller?.setMode(OrbMode.ERROR)
        delay(900)
        view[0]?.controller?.draw(curState.value, curBox.value)
    }

    // The live level as audio bands, about 30 times a second.
    LaunchedEffect(Unit) {
        var last = 0L
        var live = false
        while (true) {
            withFrameNanos { now ->
                if (now - last < 33_000_000L) return@withFrameNanos
                last = now
                val controller = view[0]?.controller ?: return@withFrameNanos
                val level = curExternal.value?.invoke()
                if (level == null) {
                    if (live) controller.clearAudioBands(0)
                    live = false
                } else {
                    live = true
                    val a = level.coerceIn(0f, 1f)
                    controller.setAudioBands(
                        0,
                        FloatArray(AUDIO_BANDS) { i -> a * (1f - i / (AUDIO_BANDS * 1.6f)) },
                    )
                }
            }
        }
    }
}

/** Convenience overload for binary call sites (news screen): loading → the loading swarm. */
@Composable
fun NeuralOrb(
    modifier: Modifier = Modifier,
    accent: Color = Color(0xFF7AB8FF),
    loading: Boolean,
    dark: Boolean = isSystemInDarkTheme(),
    glowIntensity: Float = 0.6f,
    prominent: Boolean = false,
) = NeuralOrb(
    modifier,
    accent,
    if (loading) OrbState.Loading else OrbState.Idle,
    glowIntensity = glowIntensity,
    prominent = prominent,
    dark = dark,
)
