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
import kotlinx.coroutines.delay
import no.nordli.neuralorb.NeuralOrbView
import no.nordli.neuralorb.OrbMode
import no.nordli.neuralorb.OrbTheme
import no.nordli.neuralorb.OrbTuning

/**
 * The Nexus orb: the neural-orb AAR's [NeuralOrbView] (Swarm2, GLES 3) — the same renderer the
 * Flutter app embeds, so there is no hand-kept Compose copy of the orb any more. This file maps the
 * launcher's voice states onto it, the same mapping as `nexus_mobile/lib/core/orb/orb.dart`.
 */
enum class OrbState { Idle, Listening, Thinking, Speaking, Searching, Loading, Muted }

/** The renderer's audio band count (the AAR keeps its spec internal). */
private const val AUDIO_BANDS = 16

private fun OrbState.mode(): OrbMode = when (this) {
    OrbState.Idle -> OrbMode.IDLE
    OrbState.Listening -> OrbMode.LISTENING
    OrbState.Thinking -> OrbMode.THINKING
    OrbState.Speaking -> OrbMode.SPEAKING
    OrbState.Searching -> OrbMode.TOOL_CALL
    OrbState.Loading -> OrbMode.LOADING
    OrbState.Muted -> OrbMode.OFFLINE
}

/** Dark ink on a light surface, light on a dark one, with [accent] leading the palette. */
private fun themeFor(dark: Boolean, accent: Color): OrbTheme {
    val base = if (dark) OrbTheme.MIDNIGHT else OrbTheme.DAYLIGHT
    val palette = base.palette.copyOf().also { it[0] = accent.toArgb() }
    return base.copy(id = "${base.id}-nexus", palette = palette)
}

/** The glow setting (0..1; 0.6 is the renderer's own look) as bloom and node glow. */
private fun tuningFor(theme: OrbTheme, glow: Float): OrbTuning {
    val g = glow.coerceIn(0f, 1f)
    return OrbTuning(
        theme.tuning.toMap() + mapOf(
            "bloomStrength" to (0.15f + g * 1.283f),
            "nodeGlow" to (0.25f + g * 1.333f),
        ),
    )
}

@Composable
fun NeuralOrb(
    modifier: Modifier = Modifier,
    accent: Color = Color(0xFF7AB8FF),
    state: OrbState = OrbState.Idle,
    glowIntensity: Float = 0.6f,
    externalAmplitude: (() -> Float?)? = null,
    failureSignal: Int = 0,
) {
    val dark = isSystemInDarkTheme()
    val theme = remember(dark, accent) { themeFor(dark, accent) }
    val tuning = remember(theme, glowIntensity) { tuningFor(theme, glowIntensity) }
    val curState = rememberUpdatedState(state)
    val curExternal = rememberUpdatedState(externalAmplitude)
    val view = remember { arrayOfNulls<NeuralOrbView>(1) }

    AndroidView(
        modifier = modifier,
        factory = { context -> NeuralOrbView(context).also { view[0] = it } },
        update = { orb ->
            orb.controller.setTheme(theme)
            orb.controller.setTuning(tuning)
            orb.controller.setMode(state.mode())
        },
        onRelease = { it.dispose() },
    )

    // A failure flashes the error mode, then returns to whatever state is current.
    LaunchedEffect(failureSignal) {
        if (failureSignal == 0) return@LaunchedEffect
        view[0]?.controller?.setMode(OrbMode.ERROR)
        delay(900)
        view[0]?.controller?.setMode(curState.value.mode())
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
) = NeuralOrb(modifier, accent, if (loading) OrbState.Loading else OrbState.Idle)
