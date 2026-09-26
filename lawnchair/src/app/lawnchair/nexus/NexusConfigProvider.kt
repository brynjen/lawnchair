package app.lawnchair.nexus

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri

/**
 * Exposes the embedded Nexus app's server config + last-known presence to the
 * launcher side ([NexusConfig]) as a single-row cursor.
 *
 * Authority: `no.nordli.nexus.config`. Guarded by the signature-level
 * permission `no.nordli.nexus.permission.READ_CONFIG`. See
 * `docs/archive/nexus-search-bar-plan.md` piece A.
 *
 * The values are the Flutter app's SharedPreferences, which shared_preferences
 * stores in the file `FlutterSharedPreferences` with every key prefixed
 * `flutter.`.
 */
class NexusConfigProvider : ContentProvider() {

    private companion object {
        const val PREFS_FILE = "FlutterSharedPreferences"

        // Native (flutter.-prefixed) keys written by the Dart side.
        const val KEY_SERVER_URL = "flutter.nexus.server.url"
        const val KEY_CONFIGURED = "flutter.nexus.configured"
        const val KEY_PRESENCE_ONLINE = "flutter.nexus.presence.online"
        const val KEY_PRESENCE_EPOCH_MS = "flutter.nexus.presence.epochMs"
        const val KEY_WHISPER_READY = "flutter.nexus.presence.whisperReady"
        const val KEY_TTS_READY = "flutter.nexus.presence.ttsReady"
        const val KEY_LLM_READY = "flutter.nexus.presence.llmReady"

        // Cursor column names (the contract the launcher consumes).
        val COLUMNS = arrayOf(
            "serverUrl",
            "configured",
            "presenceOnline",
            "presenceEpochMs",
            "whisperReady",
            "ttsReady",
            "llmReady",
        )
    }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val prefs = context!!.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

        val cursor = MatrixCursor(COLUMNS)
        cursor.addRow(
            arrayOf<Any?>(
                prefs.getString(KEY_SERVER_URL, "") ?: "",
                boolToInt(readBool(prefs, KEY_CONFIGURED)),
                boolToInt(readBool(prefs, KEY_PRESENCE_ONLINE)),
                readLong(prefs, KEY_PRESENCE_EPOCH_MS),
                boolToInt(readBool(prefs, KEY_WHISPER_READY)),
                boolToInt(readBool(prefs, KEY_TTS_READY)),
                boolToInt(readBool(prefs, KEY_LLM_READY)),
            ),
        )
        return cursor
    }

    /**
     * Flutter usually stores a Dart `bool` as a native `Boolean`, but tolerate
     * a String-encoded value too. Absent → false.
     */
    private fun readBool(prefs: SharedPreferences, key: String): Boolean {
        val value = prefs.all[key] ?: return false
        return when (value) {
            is Boolean -> value
            is String -> value.toBooleanStrictOrNull() ?: false
            else -> false
        }
    }

    /**
     * Flutter stores a Dart `int` as a native `Long`. Tolerate `Int`/`String`
     * too. Absent → 0.
     */
    private fun readLong(prefs: SharedPreferences, key: String): Long {
        val value = prefs.all[key] ?: return 0L
        return when (value) {
            is Long -> value
            is Int -> value.toLong()
            is String -> value.toLongOrNull() ?: 0L
            else -> 0L
        }
    }

    private fun boolToInt(value: Boolean): Int = if (value) 1 else 0

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("read-only provider")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException("read-only provider")

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException("read-only provider")
}
