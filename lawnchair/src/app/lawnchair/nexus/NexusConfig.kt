package app.lawnchair.nexus

import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import com.android.launcher3.BuildConfig

/**
 * Reconciled snapshot of the Nexus app's config + cached presence, read from the app's
 * signature-protected ContentProvider (`content://no.nordli.nexus.config`). The launcher never
 * stores its own server URL — this is the single source of truth.
 *
 * [enabled] gates the chat bar: it requires the app installed, onboarding-configured, and a
 * non-empty server URL. When the provider is unreachable (app not installed, or a debug
 * signature mismatch denies the read) every flag falls back to "not configured".
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
) {
    /** True when the bar should be interactive (installed + configured + has a URL). */
    val enabled: Boolean get() = installed && configured && serverUrl.isNotEmpty()

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

    /** The interim shared access token compiled into the app. */
    val accessToken: String get() = BuildConfig.NEXUS_ACCESS_TOKEN

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
                NexusStatus(
                    installed = true,
                    configured = cursor.bool("configured"),
                    serverUrl = providerUrl.ifEmpty { overrideUrl },
                    presenceOnline = cursor.bool("presenceOnline"),
                    presenceEpochMs = cursor.long("presenceEpochMs"),
                    whisperReady = cursor.bool("whisperReady"),
                    ttsReady = cursor.bool("ttsReady"),
                    llmReady = cursor.bool("llmReady"),
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
