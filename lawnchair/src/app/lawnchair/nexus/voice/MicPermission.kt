package app.lawnchair.nexus.voice

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings

/**
 * RECORD_AUDIO gate for the launcher, which has no `registerForActivityResult` plumbing. Three
 * states drive the mic UX:
 *
 *  - [Granted]  → mic works.
 *  - [Denied]   → soft-denied or never asked; a request dialog will still show, so ask on tap.
 *  - [Blocked]  → "don't ask again" / auto-denied after repeated declines; the system dialog no
 *                 longer appears, so route the user to app settings and show the mic as disabled.
 *
 * The Android signal (`shouldShowRequestPermissionRationale == false`) is ambiguous — it's also
 * false before the very first ask — so we track a "have we ever asked" flag to tell first-launch
 * (Denied) from truly Blocked.
 */
object MicPermission {

    enum class State { Granted, Denied, Blocked }

    private const val PREFS = "nexus_mic_perm"
    private const val KEY_ASKED = "asked"

    fun state(activity: Activity): State {
        val granted = activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) return State.Granted
        val canAsk = activity.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
        return when {
            canAsk -> State.Denied
            !hasAsked(activity) -> State.Denied // never asked yet — the first dialog will show
            else -> State.Blocked
        }
    }

    /** Mark that we've shown (or are about to show) the system dialog at least once. */
    fun markAsked(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ASKED, true).apply()
    }

    private fun hasAsked(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ASKED, false)

    /** Open this app's permission settings so the user can flip a Blocked grant. */
    fun openSettings(context: Context) {
        runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", context.packageName, null),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
