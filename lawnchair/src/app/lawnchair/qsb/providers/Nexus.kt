package app.lawnchair.qsb.providers

import app.lawnchair.nexus.NexusConfig
import app.lawnchair.qsb.ThemingMethod
import com.android.launcher3.Launcher
import com.android.launcher3.R

/**
 * The Nexus chat-bar provider. Replaces the Google glyph on the bottom pill with the Nexus orb and
 * turns the QSB into the entry point to the Nexus assistant.
 *
 * It is a [QsbSearchProviderType.LOCAL] provider: [app.lawnchair.qsb.LawnQsbLayout] handles the bar
 * itself — the field is the launcher's own app search, the logo opens the Nexus app at Home and the
 * mic opens the in-process voice overlay (or shows the install/configure nudge when the app is not
 * set up). [launch] is only a fallback for entry points that go through the provider directly.
 */
data object Nexus : QsbSearchProvider(
    id = "nexus",
    name = R.string.search_provider_nexus,
    icon = R.drawable.ic_nexus,
    themingMethod = ThemingMethod.TINT,
    packageName = NexusConfig.APP_PACKAGE,
    website = "",
    type = QsbSearchProviderType.LOCAL,
) {
    override suspend fun launch(launcher: Launcher, forceWebsite: Boolean) {
        // The real entry point is LawnQsbLayout's onQsbClick (it has the gating + overlay). As a
        // fallback (e.g. gesture handlers), just open the Nexus app if it's installed.
        val intent = launcher.packageManager.getLaunchIntentForPackage(NexusConfig.APP_PACKAGE)
        if (intent != null) launcher.startActivity(intent)
    }
}
