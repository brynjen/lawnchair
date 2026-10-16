package app.lawnchair.nexus.voice

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi

/** A Bluetooth headset a call can use: LE Audio or the classic call profile. */
data class HeadsetCandidate(val id: Int, val le: Boolean, val name: String, val address: String?)

/**
 * The headset the person chose when it is connected, else LE Audio before the classic call profile.
 * Pure, so a test can run it without a phone.
 */
fun pickHeadset(candidates: List<HeadsetCandidate>, preferredAddress: String?): HeadsetCandidate? {
    if (candidates.isEmpty()) return null
    preferredAddress?.let { wanted ->
        candidates.firstOrNull { it.address.equals(wanted, ignoreCase = true) }?.let { return it }
    }
    return candidates.firstOrNull { it.le } ?: candidates.first()
}

/**
 * Where the sound of one voice turn goes: the phone, or a Bluetooth headset.
 *
 * A headset takes the microphone only in call mode, and while it is in call mode it takes only
 * call-stream sound, so a turn that listens through it has to hear its reply through it too.
 * [acquire] puts the headset in call mode for the whole turn, from the press to the end of the reply;
 * [release] gives it back, so music returns to normal. Without a headset (or before Android 12, which
 * has no communication devices to pick) both do nothing and the phone's microphone and speaker are
 * used as before. The Flutter app's `AudioRouteService` does the same for the mic pill in the app.
 */
class NexusAudioRoute(private val context: Context) {

    private val audio: AudioManager? = context.getSystemService(AudioManager::class.java)

    @Volatile
    private var linked: AudioDeviceInfo? = null

    /** The headset in call mode for the turn in flight, or null. */
    val headset: AudioDeviceInfo? get() = linked

    /** Whether a headset is in call mode: capture and playback follow it. */
    val held: Boolean get() = linked != null

    /** Puts a headset in call mode. True when one is. Never throws: a turn without one is a phone turn. */
    fun acquire(): Boolean {
        if (linked != null) return true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        return runCatching {
            val manager = audio ?: return false
            val devices = manager.availableCommunicationDevices
            val candidates = devices.mapNotNull { it.asCandidate() }
            val pick = pickHeadset(candidates, chosenAddress()) ?: return false
            val device = devices.first { it.id == pick.id }
            if (!manager.setCommunicationDevice(device)) {
                Log.w(TAG, "route_refused device=${pick.name}")
                return false
            }
            linked = device
            Log.i(TAG, "route_acquire device=${pick.name} kind=${if (pick.le) "le" else "sco"}")
            true
        }.getOrElse {
            Log.w(TAG, "route_acquire_failed", it)
            false
        }
    }

    /** Gives the headset back. Does nothing when none is held. */
    fun release() {
        val device = linked ?: return
        linked = null
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) audio?.clearCommunicationDevice()
            Log.i(TAG, "route_release device=${device.productName}")
        }.onFailure { Log.w(TAG, "route_release_failed", it) }
    }

    /** The headset input that matches [headset], for the recorder. */
    fun input(): AudioDeviceInfo? {
        val out = linked ?: return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        return audio?.getDevices(AudioManager.GET_DEVICES_INPUTS)
            ?.firstOrNull { it.type == out.type && it.address == out.address }
    }

    // The headset chosen under Settings → Bluetooth in the embedded app (its saved settings).
    private fun chosenAddress(): String? =
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE).getString(KEY_SELECTED_DEVICE, null)

    @RequiresApi(Build.VERSION_CODES.S)
    private fun AudioDeviceInfo.asCandidate(): HeadsetCandidate? {
        val le = type == AudioDeviceInfo.TYPE_BLE_HEADSET
        if (!le && type != AudioDeviceInfo.TYPE_BLUETOOTH_SCO) return null
        return HeadsetCandidate(id, le, productName.toString(), address.takeIf { it.isNotEmpty() })
    }

    private companion object {
        const val TAG = "NexusAudioRoute"
        const val PREFS_FILE = "FlutterSharedPreferences"
        const val KEY_SELECTED_DEVICE = "flutter.settings.bluetooth.selectedDeviceId"
    }
}
