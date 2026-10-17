package app.lawnchair.nexus.buds

import android.os.Handler
import android.os.Looper
import io.flutter.plugin.common.MethodChannel

/**
 * The line between [NexusBudsService], which owns the media session the earbuds' taps go to, and the
 * Nexus app's Home screen. While the app is in front with its Home screen up, a tap is passed on to it
 * (it starts, sends or stops a turn that the screen shows like a held one); otherwise the service runs
 * the turn itself, with audio only. [NexusAppActivity] attaches [channel] and keeps [resumed]; the app
 * tells us [homeReady] and [busy] (see the app's `BudsTaps`).
 */
object NexusBudsBridge {
    @Volatile var channel: MethodChannel? = null
    @Volatile var resumed = false
    @Volatile var homeReady = false
    @Volatile var busy = false

    /** Passes [tap] to the app if it is to take it. True when it did. */
    fun forward(tap: Tap): Boolean {
        val line = channel ?: return false
        if (!appTakesTap(tap, resumed, homeReady, busy)) return false
        Handler(Looper.getMainLooper()).post {
            line.invokeMethod("tap", if (tap == Tap.Double) "double" else "single")
        }
        return true
    }

    /** The app's screen is gone: nothing can be passed to it. */
    fun reset() {
        channel = null
        homeReady = false
        busy = false
    }
}
