package app.lawnchair.nexus

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Light, dark or system: the launcher's surfaces follow the person's choice in the Nexus app
 * (`app.theme.mode`), and the palette is the app's own.
 */
class NexusThemeTest {

    @Test
    fun `the app's strings are read as the app reads them`() {
        assertEquals(NexusThemeMode.Light, NexusThemeMode.fromWire("light"))
        assertEquals(NexusThemeMode.Dark, NexusThemeMode.fromWire("dark"))
        assertEquals(NexusThemeMode.System, NexusThemeMode.fromWire("system"))
    }

    @Test
    fun `anything else, or nothing, is the system's`() {
        assertEquals(NexusThemeMode.System, NexusThemeMode.fromWire(null))
        assertEquals(NexusThemeMode.System, NexusThemeMode.fromWire(""))
        assertEquals(NexusThemeMode.System, NexusThemeMode.fromWire("Light")) // the app writes lower case only
        assertEquals(NexusThemeMode.System, NexusThemeMode.fromWire("auto"))
    }

    @Test
    fun `light and dark ignore the system`() {
        assertFalse(NexusThemeMode.Light.isDark(systemDark = true))
        assertFalse(NexusThemeMode.Light.isDark(systemDark = false))
        assertTrue(NexusThemeMode.Dark.isDark(systemDark = true))
        assertTrue(NexusThemeMode.Dark.isDark(systemDark = false))
    }

    @Test
    fun `system follows the system`() {
        assertTrue(NexusThemeMode.System.isDark(systemDark = true))
        assertFalse(NexusThemeMode.System.isDark(systemDark = false))
    }

    @Test
    fun `a status the app never wrote a theme for is the system's`() {
        val status = NexusStatus.NOT_INSTALLED
        assertEquals(NexusThemeMode.System, status.themeMode)
        assertTrue(status.themeMode.isDark(systemDark = true))
    }

    @Test
    fun `the palette is the app's tokens in each mode`() {
        // nexus_mobile lib/core/tokens.dart: T (dark) and NexusTokens.light().
        assertTrue(NexusColors.Dark.isDark)
        assertEquals(Color(0xFF0A0A12), NexusColors.Dark.bg)
        assertEquals(Color(0xFF14141E), NexusColors.Dark.surface)
        assertEquals(Color(0xFFF0F0F5), NexusColors.Dark.text)
        assertEquals(Color(0x61F0F0F5), NexusColors.Dark.text3)
        assertFalse(NexusColors.Light.isDark)
        assertEquals(Color(0xFFF7F7FB), NexusColors.Light.bg)
        assertEquals(Color(0xFFFFFFFF), NexusColors.Light.surface)
        assertEquals(Color(0xFF0A0A12), NexusColors.Light.text)
        assertEquals(Color(0x610A0A12), NexusColors.Light.text3)
        // Shared by both.
        assertEquals(Color(0xFF7AB8FF), NexusColors.Light.accent)
        assertEquals(NexusColors.Dark.accent, NexusColors.Light.accent)
        assertEquals(Color(0xFF4A90E2), NexusColors.Light.accentDeep)
        assertEquals(Color(0xFF0A1828), NexusColors.Light.onAccent)
        assertEquals(Color(0xFF5EEAD4), NexusColors.Dark.good)
        assertEquals(Color(0xFFF87171), NexusColors.Dark.bad)
    }

    @Test
    fun `the palette for a mode`() {
        assertEquals(NexusColors.Dark, NexusColors.of(dark = true))
        assertEquals(NexusColors.Light, NexusColors.of(dark = false))
    }
}
