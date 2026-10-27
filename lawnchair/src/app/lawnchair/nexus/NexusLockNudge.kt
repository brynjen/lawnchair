package app.lawnchair.nexus

import android.content.Context
import android.widget.Toast

/**
 * What the launcher says, and does, when the Nexus app is not ready to be used from here: the
 * sentence for each [NexusLock], and opening the app where the missing step is. Shared by the bar,
 * the mic and News so the phone has one way of saying it.
 */
object NexusLockNudge {

    /** The one-line instruction for [lock]; blank for [NexusLock.None]. */
    fun message(context: Context, lock: NexusLock): String = when (lock) {
        NexusLock.None -> ""
        NexusLock.NotInstalled -> context.getString(com.android.launcher3.R.string.nexus_install_prompt)
        NexusLock.NeedsServer -> "Open Nexus to finish setup."
        NexusLock.NeedsSignIn -> "Sign in to Nexus to use this."
        NexusLock.NeedsOnboarding -> "Finish setting up Nexus first."
    }

    /** Toasts [message] and, when the app is there, opens it. */
    fun show(context: Context, lock: NexusLock) {
        if (lock == NexusLock.None) return
        Toast.makeText(context, message(context, lock), Toast.LENGTH_LONG).show()
        if (lock != NexusLock.NotInstalled) openApp(context)
    }

    /** Opens the Nexus app (it routes a signed-out or half-onboarded person to the step they are on). */
    fun openApp(context: Context) {
        runCatching {
            context.packageManager.getLaunchIntentForPackage(NexusConfig.APP_PACKAGE)
                ?.let { context.startActivity(it) }
        }
    }
}
