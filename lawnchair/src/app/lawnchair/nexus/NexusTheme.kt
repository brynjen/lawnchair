package app.lawnchair.nexus

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The person's choice in the Nexus app (Settings, Appearance): light, dark, or follow the system.
 * The app keeps it as the string `light` or `dark` under `app.theme.mode` (`ThemeModeCubit`);
 * anything else, or nothing, is the system's.
 */
enum class NexusThemeMode {
    System,
    Light,
    Dark,
    ;

    /** Dark or light for a surface of the launcher: the choice, or the system's when there is none. */
    fun isDark(systemDark: Boolean): Boolean = when (this) {
        Light -> false
        Dark -> true
        System -> systemDark
    }

    companion object {
        fun fromWire(value: String?): NexusThemeMode = when (value) {
            "light" -> Light
            "dark" -> Dark
            else -> System
        }
    }
}

/**
 * The app's colours (`NexusTokens` in nexus_mobile `core/tokens.dart`, the light and dark palettes),
 * mirrored value for value so a launcher surface reads as the app does in either mode. Change both
 * together.
 */
data class NexusColors(
    val isDark: Boolean,
    val bg: Color,
    val surface: Color,
    val surface2: Color,
    val surface3: Color,
    val border: Color,
    val borderStrong: Color,
    val text: Color,
    val text2: Color,
    val text3: Color,
    val accent: Color,
    val accentDeep: Color,
    val accentSoft: Color,
    val onAccent: Color,
    val good: Color,
    val warn: Color,
    val bad: Color,
) {
    companion object {
        // The same in both palettes.
        private val Accent = Color(0xFF7AB8FF)
        private val AccentDeep = Color(0xFF4A90E2)
        private val AccentSoft = Color(0x2E7AB8FF)
        private val OnAccent = Color(0xFF0A1828)
        private val Good = Color(0xFF5EEAD4)
        private val Warn = Color(0xFFF5C475)
        private val Bad = Color(0xFFF87171)

        val Dark = NexusColors(
            isDark = true,
            bg = Color(0xFF0A0A12),
            surface = Color(0xFF14141E),
            surface2 = Color(0xFF1C1C2A),
            surface3 = Color(0xFF252533),
            border = Color(0x12FFFFFF),
            borderStrong = Color(0x24FFFFFF),
            text = Color(0xFFF0F0F5),
            text2 = Color(0x9EF0F0F5),
            text3 = Color(0x61F0F0F5),
            accent = Accent,
            accentDeep = AccentDeep,
            accentSoft = AccentSoft,
            onAccent = OnAccent,
            good = Good,
            warn = Warn,
            bad = Bad,
        )

        val Light = NexusColors(
            isDark = false,
            bg = Color(0xFFF7F7FB),
            surface = Color(0xFFFFFFFF),
            surface2 = Color(0xFFF0F0F5),
            surface3 = Color(0xFFE5E5EC),
            border = Color(0x140A0A12),
            borderStrong = Color(0x290A0A12),
            text = Color(0xFF0A0A12),
            text2 = Color(0x9E0A0A12),
            text3 = Color(0x610A0A12),
            accent = Accent,
            accentDeep = AccentDeep,
            accentSoft = AccentSoft,
            onAccent = OnAccent,
            good = Good,
            warn = Warn,
            bad = Bad,
        )

        fun of(dark: Boolean): NexusColors = if (dark) Dark else Light
    }
}

/** The palette a launcher surface draws with; provided by the surface, dark until it says otherwise. */
val LocalNexusColors = compositionLocalOf { NexusColors.Dark }
