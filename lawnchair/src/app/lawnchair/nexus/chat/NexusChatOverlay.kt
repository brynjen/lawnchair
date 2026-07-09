package app.lawnchair.nexus.chat

import android.content.Intent
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import app.lawnchair.LawnchairLauncher
import app.lawnchair.nexus.NexusConfig
import com.android.launcher3.InsettableFrameLayout

/**
 * The Nexus chat overlay — a full-bleed Compose surface attached over the launcher (the same attach
 * pattern as [app.lawnchair.nexuslauncher.NexusNewsOverlay], minus the overscroll wiring since this
 * is opened by a tap rather than a swipe). One instance per open; [show] adds the view, and closing
 * (button / dismiss) removes it and tears down any in-flight turn.
 */
class NexusChatOverlay(private val launcher: LawnchairLauncher) {

    private var panel: View? = null

    fun show(serverUrl: String, token: String) {
        if (panel != null) return
        val parent = (launcher.dragLayer?.parent as? ViewGroup) ?: launcher.dragLayer ?: return
        val view = ComposeView(launcher).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                NexusChatScreen(serverUrl = serverUrl, token = token, onClose = { remove() })
            }
        }
        parent.addView(
            view,
            InsettableFrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT).apply {
                ignoreInsets = true
            },
        )
        view.bringToFront()
        panel = view
    }

    private fun remove() {
        val v = panel ?: return
        (v.parent as? ViewGroup)?.removeView(v)
        panel = null
    }

    companion object {
        private const val MATCH_PARENT = ViewGroup.LayoutParams.MATCH_PARENT

        /**
         * The gated entry point for a tap on the Nexus pill. Reads the Nexus app's config; opens the
         * chat when installed + configured, otherwise nudges the user to install or configure. Safe
         * to call on the main thread — the provider read is a quick local query.
         */
        fun openFromQsb(launcher: LawnchairLauncher) {
            val status = NexusConfig.read(launcher)
            when {
                status.enabled -> NexusChatOverlay(launcher).show(status.serverUrl, NexusConfig.accessToken)
                !status.installed -> {
                    // App not installed — send the user to install it.
                    val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${NexusConfig.APP_PACKAGE}"))
                    val fallback = Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://play.google.com/store/apps/details?id=${NexusConfig.APP_PACKAGE}"),
                    )
                    val launched = runCatching { launcher.startActivity(market); true }.getOrDefault(false)
                    if (!launched) runCatching { launcher.startActivity(fallback) }
                }
                else -> {
                    // Installed but not configured against a server — open the app so the user can set one.
                    Toast.makeText(launcher, launcher.getString(com.android.launcher3.R.string.nexus_configure_prompt), Toast.LENGTH_LONG).show()
                    runCatching {
                        launcher.packageManager.getLaunchIntentForPackage(NexusConfig.APP_PACKAGE)
                            ?.let { launcher.startActivity(it) }
                    }
                }
            }
        }
    }
}
