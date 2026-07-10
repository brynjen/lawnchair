package app.lawnchair.nexus.chat

import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import app.lawnchair.LawnchairLauncher
import app.lawnchair.nexus.NexusConfig
import app.lawnchair.nexus.net.FakeNexusTransport
import app.lawnchair.nexus.net.NexusClient
import app.lawnchair.nexus.net.NexusTransport
import com.android.launcher3.InsettableFrameLayout

/**
 * The Nexus chat overlay — a full-bleed, transparent Compose surface layered over the launcher (the
 * same attach pattern as [app.lawnchair.nexuslauncher.NexusNewsOverlay], opened by a tap rather than
 * a swipe). Unlike a fullscreen page, the home screen shows through the transparent window and is
 * dimmed by the composable's own scrim.
 *
 * Owns a [NexusChatController] (the voice/text state machine, outside any composition so it survives
 * recomposition/IME relayout). One instance per open; [showText]/[showVoice] add the view,
 * dismissal runs the exit animation and then detaches + releases the controller.
 */
class NexusChatOverlay(private val launcher: LawnchairLauncher) {

    private var panel: View? = null
    private var controller: NexusChatController? = null
    private var backCallback: Any? = null
    private var prevSoftInputMode: Int? = null

    private fun show(mode: Mode, serverUrl: String, token: String, gesture: MicGesture) {
        if (panel != null) return
        val parent = (launcher.dragLayer?.parent as? ViewGroup) ?: launcher.dragLayer ?: return

        // ┌── SINGLE SWITCH ──────────────────────────────────────────────────────────────────┐
        // │ USE_MOCK routes the whole turn (STT result, LLM answer, TTS audio) through          │
        // │ FakeNexusTransport so you can see the UI end-to-end with the server down. The REAL   │
        // │ mic still runs (that's what drives the voice-amplitude orb) — only the Serverpod     │
        // │ calls are faked. Flip USE_MOCK to false to use the live server (NexusClient).        │
        // └─────────────────────────────────────────────────────────────────────────────────────┘
        val transport: NexusTransport =
            if (USE_MOCK || simulate || serverUrl == MOCK_URL) FakeNexusTransport() else NexusClient()
        val ctrl = NexusChatController(transport, serverUrl, token, mode, gesture)
        controller = ctrl

        // Read the status-bar height synchronously from the (already-attached) parent so the
        // top-left orb can use a FIXED top offset. Relying on Compose's WindowInsets makes the orb
        // lay out at y=0 and jump down a frame later when the inset finally resolves.
        val statusBarTopPx = androidx.core.view.ViewCompat.getRootWindowInsets(parent)
            ?.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars())?.top ?: 0

        val view = ComposeView(launcher).apply {
            // Focusable so the text field can grab focus and raise the IME (Text mode).
            isFocusableInTouchMode = true
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                NexusChatScreen(controller = ctrl, statusBarTopPx = statusBarTopPx, onClosed = { remove() })
            }
        }
        parent.addView(
            view,
            InsettableFrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT).apply { ignoreInsets = true },
        )
        view.bringToFront()
        panel = view

        // Text mode wants the IME to raise the composer: the launcher window is ADJUST_NOTHING, so
        // temporarily switch to ADJUST_RESIZE while the overlay is shown (restored on remove).
        if (mode == Mode.Text) {
            launcher.window?.let { w ->
                prevSoftInputMode = w.attributes.softInputMode
                w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            }
        }

        registerBackHandler(ctrl)
        if (gesture != MicGesture.None) ctrl.startListening()
    }

    /** For the Hold gesture: the QSB owns the press lifecycle and calls this on release. */
    fun endRecordingAndSend() {
        controller?.endRecordingAndSend()
    }

    private fun registerBackHandler(ctrl: NexusChatController) {
        // OnBackInvokedDispatcher (API 33+) catches both the back gesture (swipe) and button, at
        // PRIORITY_OVERLAY so it beats the launcher's own back chain. Below 33, orb/scrim tap dismiss.
        if (Build.VERSION.SDK_INT < 33) return
        val cb = android.window.OnBackInvokedCallback { ctrl.dismiss() }
        launcher.onBackInvokedDispatcher.registerOnBackInvokedCallback(
            android.window.OnBackInvokedDispatcher.PRIORITY_OVERLAY,
            cb,
        )
        backCallback = cb
    }

    private fun remove() {
        // Guard the API-33 type behind the SDK check so the class isn't loaded on older devices.
        if (Build.VERSION.SDK_INT >= 33) {
            (backCallback as? android.window.OnBackInvokedCallback)
                ?.let { launcher.onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }
        }
        backCallback = null
        // Hide the IME at the window level — the Compose keyboard controller is unreliable during
        // teardown, so the keyboard would otherwise linger after the overlay detaches.
        panel?.let { v ->
            val imm = launcher.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                as? android.view.inputmethod.InputMethodManager
            imm?.hideSoftInputFromWindow(v.windowToken, 0)
            v.clearFocus()
        }
        prevSoftInputMode?.let { launcher.window?.setSoftInputMode(it) }
        prevSoftInputMode = null
        controller?.close()
        controller = null
        val v = panel ?: return
        (v.parent as? ViewGroup)?.removeView(v)
        panel = null
    }

    companion object {
        private const val MATCH_PARENT = ViewGroup.LayoutParams.MATCH_PARENT
        private const val MOCK_URL = "mock://"

        /**
         * THE single switch for mock-vs-real. `true` = show the whole flow via [FakeNexusTransport]
         * (real mic + amplitude, fake transcript/answer/audio) so the UI can be checked with the
         * server down. `false` = the live server ([NexusClient]). Runtime `simulate` / a `mock://`
         * serverUrl still force the mock even when this is false.
         */
        private const val USE_MOCK = false

        /** Runtime override of [USE_MOCK] (e.g. from adb/debug), independent of the compile-time flag. */
        @JvmField
        var simulate: Boolean = false

        /** Tap on the pill's text area → Text mode. */
        fun openText(launcher: LawnchairLauncher) {
            val (url, token) = resolveOrNudge(launcher) ?: return
            NexusChatOverlay(launcher).show(Mode.Text, url, token, MicGesture.None)
        }

        /**
         * Tap/hold the mic → Voice mode. [gesture] distinguishes tap-to-stop from hold-to-release.
         * Returns the overlay so the caller (QSB) can forward the Hold release to [endRecordingAndSend].
         */
        fun openVoice(launcher: LawnchairLauncher, gesture: MicGesture): NexusChatOverlay? {
            val (url, token) = resolveOrNudge(launcher) ?: return null
            return NexusChatOverlay(launcher).also { it.show(Mode.Voice, url, token, gesture) }
        }

        /**
         * Resolve (serverUrl, token) from the Nexus config, or nudge the user and return null. In
         * simulate mode the config gating is bypassed with a mock URL.
         */
        private fun resolveOrNudge(launcher: LawnchairLauncher): Pair<String, String>? {
            // In mock mode, skip the install/configure gating entirely so the overlay always opens.
            if (USE_MOCK || simulate) return MOCK_URL to ""
            val status = NexusConfig.read(launcher)
            if (!status.installed) {
                Toast.makeText(
                    launcher,
                    launcher.getString(com.android.launcher3.R.string.nexus_install_prompt),
                    Toast.LENGTH_LONG,
                ).show()
                return null
            }
            if (status.serverUrl.isBlank()) {
                // Installed but not configured — send the user to finish setup in the Nexus app.
                Toast.makeText(launcher, "Open Nexus to finish setup.", Toast.LENGTH_LONG).show()
                runCatching {
                    launcher.packageManager.getLaunchIntentForPackage(NexusConfig.APP_PACKAGE)
                        ?.let { launcher.startActivity(it) }
                }
                return null
            }
            return status.serverUrl to NexusConfig.accessToken
        }
    }
}
