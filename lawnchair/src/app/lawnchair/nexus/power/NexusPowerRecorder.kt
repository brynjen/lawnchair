package app.lawnchair.nexus.power

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.OutcomeReceiver
import android.os.PowerMonitor
import android.os.PowerMonitorReadings
import android.os.health.SystemHealthManager
import android.util.Log
import app.lawnchair.nexus.NexusConfig
import app.lawnchair.nexus.net.NexusClient
import app.lawnchair.nexus.push.NexusPushTrigger
import app.lawnchair.nexus.push.PushStats
import java.time.Instant
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Records what the phone used over a stretch the person marks out: Settings → Notifications →
 * "Record power use" in the app. Switching it on takes a [PowerSnapshot]; switching it off takes
 * another and sends the difference to the server (`power.record`), where the recordings add up to
 * a picture of what push costs with Wi-Fi and without, at one beat and another
 * (nexus `docs/features/power-recording.md`).
 *
 * Nothing runs in between: the phone's energy meters count by themselves, charging or not, and the
 * two ends are read when the switch moves. Whether Wi-Fi carried the traffic is read at both ends
 * and taken to have held in between. A recording that could not be sent is kept and sent with the
 * next one, or when the launcher next starts.
 */
object NexusPowerRecorder {
    private const val TAG = "NexusPower"
    private const val FILE = "FlutterSharedPreferences"
    const val KEY = "flutter.settings.power.recording"
    private const val PREFS = "nexus_power"
    private const val KEY_START = "start"
    private const val KEY_PENDING = "pending"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Held so the listener is not garbage collected.
    private var listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener? = null

    fun watch(context: Context) {
        val app = context.applicationContext
        if (listener != null) return
        val l = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY) scope.launch { sync(app) }
        }
        listener = l
        app.getSharedPreferences(FILE, Context.MODE_PRIVATE).registerOnSharedPreferenceChangeListener(l)
        scope.launch { sync(app) }
    }

    /** Brings the recording in line with the switch, and sends whatever waits to be sent. */
    private suspend fun sync(app: Context) {
        val wanted = app.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY, false)
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val start = PowerSnapshot.parse(prefs.getString(KEY_START, null))
        if (wanted && start == null) {
            val now = snapshot(app)
            prefs.edit().putString(KEY_START, now.toJson()).apply()
            Log.i(TAG, "power_recording_started rails=${now.rails.size} wifi=${now.wifi} charging=${now.charging}")
        } else if (!wanted && start != null) {
            val end = snapshot(app)
            val pushPrefs = app.getSharedPreferences("nexus_push", Context.MODE_PRIVATE)
            val pushEnabled = app.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(NexusPushTrigger.KEY, true)
            val recording = powerRecordingJson(
                start = start,
                end = end,
                device = Build.MODEL,
                pushEnabled = pushEnabled,
                keepaliveSeconds = pushPrefs.getInt("keepaliveSeconds", 0).takeIf { it > 0 && pushEnabled },
                iso = { Instant.ofEpochMilli(it).toString() },
            )
            // Kept before it is sent: a phone with no way to the server loses nothing.
            prefs.edit().remove(KEY_START).putString(KEY_PENDING, recording.toString()).apply()
            Log.i(TAG, "power_recording_ended seconds=${(end.atMs - start.atMs) / 1000}")
        }
        send(app)
    }

    private suspend fun send(app: Context) {
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val pending = prefs.getString(KEY_PENDING, null) ?: return
        val status = NexusConfig.read(app)
        if (!status.enabled) return
        runCatching {
            NexusClient().powerRecord(status.serverUrl, status.authKey, org.json.JSONObject(pending))
        }.onSuccess {
            prefs.edit().remove(KEY_PENDING).apply()
            Log.i(TAG, "power_recording_sent id=$it")
        }.onFailure { Log.w(TAG, "power_recording_not_sent ${it.javaClass.simpleName}") }
    }

    private suspend fun snapshot(app: Context): PowerSnapshot {
        val battery = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val manager = app.getSystemService(BatteryManager::class.java)
        val level = battery?.let {
            val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
            it.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) * 100 / scale
        } ?: 0
        val charge = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        val connectivity = app.getSystemService(ConnectivityManager::class.java)
        val wifi = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val push = PushStats.process
        return PowerSnapshot(
            atMs = System.currentTimeMillis(),
            wifi = wifi,
            charging = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0,
            level = level,
            // The property is Integer.MIN_VALUE (or 0) on a phone whose gauge does not report it.
            chargeUah = charge.takeIf { it > 0 }?.toLong(),
            rails = rails(app),
            pushOpens = push.opens,
            pushFailures = push.failures,
            pushMessages = push.messages,
            pushConnectedSeconds = push.connectedSeconds(),
        )
    }

    /**
     * The phone's energy meters by name, in µWs since boot: the rails it measures (the modem, Wi-Fi,
     * the CPU clusters) and the consumers it models. Empty before Android 15, on a phone with no
     * meters, and when the system does not answer in time.
     */
    private suspend fun rails(app: Context): Map<String, Long> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return emptyMap()
        val health = app.getSystemService(SystemHealthManager::class.java) ?: return emptyMap()
        return withTimeoutOrNull(10_000) {
            val monitors = suspendCancellableCoroutine<List<PowerMonitor>> { cont ->
                health.getSupportedPowerMonitors(null) { if (cont.isActive) cont.resume(it) }
            }
            if (monitors.isEmpty()) return@withTimeoutOrNull emptyMap()
            suspendCancellableCoroutine<Map<String, Long>> { cont ->
                health.getPowerMonitorReadings(
                    monitors,
                    null,
                    object : OutcomeReceiver<PowerMonitorReadings, RuntimeException> {
                        override fun onResult(readings: PowerMonitorReadings) {
                            val out = HashMap<String, Long>()
                            for (monitor in monitors) {
                                val energy = readings.getConsumedEnergy(monitor)
                                if (energy != PowerMonitorReadings.ENERGY_UNAVAILABLE.toLong()) out[monitor.name] = energy
                            }
                            if (cont.isActive) cont.resume(out)
                        }

                        override fun onError(error: RuntimeException) {
                            Log.w(TAG, "power_rails_unreadable ${error.javaClass.simpleName}")
                            if (cont.isActive) cont.resume(emptyMap())
                        }
                    },
                )
            }
        } ?: emptyMap()
    }
}
