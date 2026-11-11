package app.lawnchair.nexus

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

/**
 * Asks the person, once, to let Nexus off battery optimisation. Without it a launcher that restarts
 * with the screen off (after an update) is refused its push service and listens only from the next
 * unlock; with it the service may start from the background. It is the person's choice: a no is
 * kept, and the app's Settings has the row to change it. The exemption lets the process run; what
 * the battery pays is still only what the push connection does.
 */
object NexusBackgroundListening {
    private const val TAG = "NexusBanner"
    private const val PREFS = "nexus_push"
    private const val KEY_ASKED = "askedBatteryExemption"

    fun allowed(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    /** From the home screen, for a signed-in person, the first time only. */
    fun askOnce(activity: Activity) {
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_ASKED, false) || allowed(activity)) return
        if (!NexusConfig.read(activity).enabled) return
        prefs.edit().putBoolean(KEY_ASKED, true).apply()
        AlertDialog.Builder(activity)
            .setTitle("Let Nexus listen in the background?")
            .setMessage(
                "Nexus keeps one quiet connection for notifications. If the phone restarts Nexus while the " +
                    "screen is off, Android stops it from listening until you unlock, unless you allow it here.",
            )
            .setPositiveButton("Continue") { _, _ -> request(activity) }
            .setNegativeButton("Not now", null)
            .show()
    }

    /** The system's own question. */
    @SuppressLint("BatteryLife")
    private fun request(activity: Activity) {
        runCatching {
            activity.startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${activity.packageName}")),
            )
        }.onFailure { Log.w(TAG, "battery_exemption_ask_failed", it) }
    }
}
