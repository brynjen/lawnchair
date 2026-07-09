package app.lawnchair.nexus.chat

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
            if (!status.installed) {
                // App not installed (sideloaded, not on Play) — just tell the user.
                Toast.makeText(
                    launcher,
                    launcher.getString(com.android.launcher3.R.string.nexus_install_prompt),
                    Toast.LENGTH_LONG,
                ).show()
                return
            }
            // Installed: open the chat window. If the app isn't configured against a server yet,
            // serverUrl is blank and the overlay shows a "finish setup" prompt instead of the composer.
            NexusChatOverlay(launcher).show(status.serverUrl, NexusConfig.accessToken)
        }
    }
}
