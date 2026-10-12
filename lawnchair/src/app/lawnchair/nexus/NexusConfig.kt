package app.lawnchair.nexus

import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri

/**
 * Why the Nexus bar, voice, News, the Eir card and the widgets are (not) usable. The launcher
 * does nothing for the person until the app has a server, a signed-in person and finished
 * onboarding for that person; each lock names the step that is missing.
 */
enum class NexusLock {
    /** Everything the launcher needs is there. */
    None,

    /** The Nexus app is not on the phone. */
    NotInstalled,

    /** Installed, but it has never reached a server. */
    NeedsServer,

    /** A server, but nobody is signed in (or the app holds no session key). */
    NeedsSignIn,

    /** Signed in, but this person has not finished onboarding. */
    NeedsOnboarding,
}

/**
 * Reconciled snapshot of the Nexus app's config + cached presence, read from the app's
 * signature-protected ContentProvider (`content://no.nordli.nexus.config`). The launcher never
 * stores its own server URL or login — the app is the single source of truth.
 *
 * [enabled] gates the chat bar and everything else that talks to the server: it requires the app
 * installed, a server, a signed-in person with a session key ([authKey]) and that person's
 * onboarding finished ([lock] says which is missing). When the provider is unreachable (app not
 * installed, or a debug signature mismatch denies the read) every flag falls back to "not
 * configured".
 */
data class NexusStatus(
    val installed: Boolean,
    val configured: Boolean,
    val serverUrl: String,
    val presenceOnline: Boolean,
    val presenceEpochMs: Long,
    val whisperReady: Boolean,
    val ttsReady: Boolean,
    val llmReady: Boolean,
    /** The server has speech in and out off (its GPU Max mode); typing still works. */
    val voiceOff: Boolean = false,
    /** The server's sentence for [voiceOff]; blank when it gave none. */
    val voiceOffReason: String = "",
    /** A person is signed in on the app (and the app holds their session key). */
    val signedIn: Boolean = false,
    /** That person has finished onboarding. */
    val onboarded: Boolean = false,
    /** The signed-in person's session key, which every call to the server carries; blank when out. */
    val authKey: String = "",
) {
    /** The first thing missing, or [NexusLock.None]. A flag without a key does not count as signed in. */
    val lock: NexusLock
        get() = when {
            !installed -> NexusLock.NotInstalled
            !configured || serverUrl.isEmpty() -> NexusLock.NeedsServer
            !signedIn || authKey.isEmpty() -> NexusLock.NeedsSignIn
            !onboarded -> NexusLock.NeedsOnboarding
            else -> NexusLock.None
        }

    /** True when the bar should be interactive and the launcher may call the server. */
    val enabled: Boolean get() = lock == NexusLock.None

    companion object {
        val NOT_INSTALLED = NexusStatus(
            installed = false,
            configured = false,
            serverUrl = "",
            presenceOnline = false,
            presenceEpochMs = 0L,
            whisperReady = false,
            ttsReady = false,
            llmReady = false,
        )
    }
}

object NexusConfig {

    const val APP_PACKAGE = "no.nordli.nexus"
    private val CONFIG_URI: Uri = Uri.parse("content://$APP_PACKAGE.config")

    /** Presence cache is treated as stale (needs a fresh ping) after this long. */
    const val PRESENCE_STALE_MS = 60_000L

    /** Whether the Nexus app is installed at all. */
    fun isAppInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(APP_PACKAGE, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    /**
     * Read the full status. Handles a missing app, a null cursor, and a SecurityException (debug
     * signature mismatch) gracefully — all collapse to [NexusStatus.NOT_INSTALLED]-shaped "not
     * configured". [overrideUrl] (a hidden dev pref) wins only when the provider yields no URL.
     */
    fun read(context: Context, overrideUrl: String = ""): NexusStatus {
        val installed = isAppInstalled(context)
        if (!installed) {
            return if (overrideUrl.isNotEmpty()) {
                NexusStatus.NOT_INSTALLED.copy(serverUrl = overrideUrl)
            } else {
                NexusStatus.NOT_INSTALLED
            }
        }

        var cursor: Cursor? = null
        return try {
            cursor = context.contentResolver.query(CONFIG_URI, null, null, null, null)
            if (cursor == null || !cursor.moveToFirst()) {
                NexusStatus.NOT_INSTALLED.copy(installed = true, serverUrl = overrideUrl)
            } else {
                val providerUrl = cursor.string("serverUrl")
                val authKey = cursor.string("authKey")
                NexusStatus(
                    installed = true,
                    configured = cursor.bool("configured"),
                    serverUrl = providerUrl.ifEmpty { overrideUrl },
                    presenceOnline = cursor.bool("presenceOnline"),
                    presenceEpochMs = cursor.long("presenceEpochMs"),
                    whisperReady = cursor.bool("whisperReady"),
                    ttsReady = cursor.bool("ttsReady"),
                    llmReady = cursor.bool("llmReady"),
                    voiceOff = cursor.bool("voiceOff"),
                    voiceOffReason = cursor.string("voiceOffReason"),
                    authKey = authKey,
                    // The key decides, not the flag: a phone restored from a backup can carry the
                    // flag without the key (the key's file is kept out of backups).
                    signedIn = cursor.bool("signedIn") && authKey.isNotEmpty(),
                    onboarded = cursor.bool("onboarded"),
                )
            }
        } catch (_: SecurityException) {
            // Debug builds signed with a different key than the Nexus app can't read the guarded
            // provider — treat as "installed but not configured" rather than crashing.
            NexusStatus.NOT_INSTALLED.copy(installed = true, serverUrl = overrideUrl)
        } catch (_: Exception) {
            NexusStatus.NOT_INSTALLED.copy(installed = true, serverUrl = overrideUrl)
        } finally {
            cursor?.close()
        }
    }

    /** True when the cached presence is old enough that a single-shot resume ping is warranted. */
    fun isPresenceStale(status: NexusStatus, nowMs: Long = System.currentTimeMillis()): Boolean =
        status.presenceEpochMs <= 0L || nowMs - status.presenceEpochMs > PRESENCE_STALE_MS

    // The provider may emit any column as String/int/bool — coerce defensively.
    private fun Cursor.string(name: String): String {
        val i = getColumnIndex(name)
        return if (i < 0 || isNull(i)) "" else getString(i) ?: ""
    }

    private fun Cursor.long(name: String): Long {
        val i = getColumnIndex(name)
        if (i < 0 || isNull(i)) return 0L
        return runCatching { getLong(i) }.getOrElse { getString(i)?.toLongOrNull() ?: 0L }
    }

    private fun Cursor.bool(name: String): Boolean {
        val i = getColumnIndex(name)
        if (i < 0 || isNull(i)) return false
        return runCatching { getInt(i) != 0 }.getOrElse {
            when (getString(i)?.lowercase()) {
                "1", "true", "yes" -> true
                else -> false
            }
        }
    }
}
