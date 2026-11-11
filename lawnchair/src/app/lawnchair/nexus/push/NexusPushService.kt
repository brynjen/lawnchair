package app.lawnchair.nexus.push

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.TrafficStats
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import app.lawnchair.nexus.NexusAppActivity
import app.lawnchair.nexus.NexusConfig
import app.lawnchair.nexus.net.NexusClient
import app.lawnchair.nexus.net.networkUp
import androidx.core.app.ServiceCompat
import java.io.FileDescriptor
import java.io.PrintWriter
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * Nexus's own push connection: one WebSocket from the launcher to the ntfy topic the server names,
 * held for as long as a person is signed in, and a notification for each message that arrives on it.
 * No Google service and no second app: the launcher is the process that is always there.
 *
 * It is a foreground service because that is what lets a socket live through Doze: the system cuts
 * the network of every app that is not in front, running a foreground service or let off battery
 * optimisation. The connection is written to once an interval from each end (ours here, the
 * server's by its `keepalive-interval`), on the beat the server names with the address
 * ([PushSubscription.keepaliveSeconds]: 90 through the Cloudflare tunnel, which closes a WebSocket
 * that is quiet for about 100). Both timers start when the socket opens, so the radio is woken
 * once per interval and not twice.
 *
 * Its bytes carry a tag of their own ([TRAFFIC_TAG]) so `dumpsys netstats detail` counts them apart
 * from everything else the launcher sends, and [dump] says how often the connection opened, dropped
 * and delivered. Together with a block of hours with it on and one with it off, that is how what
 * push alone costs the battery is measured (nexus `tool/push_test`).
 *
 * Started and stopped by [NexusPushTrigger].
 */
class NexusPushService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val stats = PushStats()
    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS) // a socket that is quiet between keepalives is the point
            .socketFactory(TaggedSockets)
            .build()
    }

    // The beat of the connection that stands, for [dump].
    @Volatile private var keepaliveSeconds = 0
    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(SERVICE_CHANNEL, "Listening for notifications", NotificationManager.IMPORTANCE_MIN),
        )
        ServiceCompat.startForeground(
            this,
            SERVICE_NOTIFICATION_ID,
            Notification.Builder(this, SERVICE_CHANNEL)
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle("Nexus is listening for notifications")
                .setOngoing(true)
                .build(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING,
        )
        scope.launch {
            // One connection while there is a network, none while there is not: a network that comes
            // back starts over at once, with no retry wait left from the one that went.
            networkUp(this@NexusPushService).collectLatest { up -> if (up) listenForGood() }
        }
        Log.i(TAG, "push_service_started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        scope.cancel()
        Log.i(TAG, "push_service_stopped")
        super.onDestroy()
    }

    /** Connects, listens until the connection is lost, waits, and connects again. */
    private suspend fun listenForGood() {
        var failures = 0
        while (true) {
            val subscription = subscription()
            if (subscription == null) {
                // No server to ask, nobody signed in, or a server with no ntfy: nothing to hold a
                // socket for. The trigger starts the service again when the app is next opened.
                Log.i(TAG, "push_no_address")
                stopSelf()
                return
            }
            val url = pushSocketUrl(subscription.url, prefs.getString(KEY_LAST_ID, null))
            if (url == null) {
                Log.w(TAG, "push_bad_address")
                stopSelf()
                return
            }
            keepaliveSeconds = subscription.keepaliveSeconds
            val opened = listen(url, subscription.keepaliveSeconds)
            failures = if (opened) 1 else failures + 1
            delay(pushRetrySeconds(failures) * 1000L)
        }
    }

    /**
     * Where to listen: the server's answer, kept so a phone that cannot reach the server (it is
     * restarting, the network is half up) still listens where it was last told to.
     */
    private suspend fun subscription(): PushSubscription? {
        val status = NexusConfig.read(this)
        if (!status.enabled) return null
        val asked = runCatching { NexusClient().pushSubscription(status.serverUrl, status.authKey) }
        asked.onSuccess { answer ->
            prefs.edit().apply {
                if (answer == null) {
                    remove(KEY_ADDRESS)
                } else {
                    putString(KEY_ADDRESS, answer.url)
                    putInt(KEY_KEEPALIVE, answer.keepaliveSeconds)
                }
            }.apply()
            return answer
        }
        val kept = prefs.getString(KEY_ADDRESS, null) ?: return null
        return PushSubscription(kept, prefs.getInt(KEY_KEEPALIVE, DEFAULT_KEEPALIVE_SECONDS))
    }

    /**
     * Holds one socket until it closes or fails, pinging every [pingSeconds]. True when the server
     * had answered `open` on it.
     */
    private suspend fun listen(url: String, pingSeconds: Int): Boolean = suspendCancellableCoroutine { cont ->
        var opened = false
        // The same connection pool and sockets, with this subscription's beat.
        val client = http.newBuilder().pingInterval(pingSeconds.toLong(), TimeUnit.SECONDS).build()
        val socket = client.newWebSocket(
            Request.Builder().url(url).build(),
            object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    when (val event = parsePushEvent(text)) {
                        PushEvent.Open -> {
                            opened = true
                            stats.opened()
                            Log.i(TAG, "push_open")
                        }
                        PushEvent.Keepalive -> stats.keepalive()
                        is PushEvent.Message -> {
                            stats.message()
                            prefs.edit().putString(KEY_LAST_ID, event.id).apply()
                            show(event)
                        }
                        PushEvent.Other -> Unit
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = over("closed $code")

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
                    over(response?.let { "http ${it.code}" } ?: t.javaClass.simpleName)

                private fun over(why: String) {
                    stats.lost(why)
                    Log.i(TAG, "push_lost $why")
                    if (cont.isActive) cont.resume(opened)
                }
            },
        )
        cont.invokeOnCancellation { socket.cancel() }
    }

    private fun show(message: PushEvent.Message) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(MESSAGE_CHANNEL, "Nexus", NotificationManager.IMPORTANCE_DEFAULT),
        )
        val open = PendingIntent.getActivity(
            this,
            2,
            Intent(this, NexusAppActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        manager.notify(
            message.id.hashCode(),
            Notification.Builder(this, MESSAGE_CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(message.title.ifEmpty { "Nexus" })
                .setContentText(message.text.take(160))
                .setStyle(Notification.BigTextStyle().bigText(message.text.take(600)))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }

    /** `adb shell dumpsys activity service NexusPushService`: counts and times, never a message's text. */
    override fun dump(fd: FileDescriptor?, writer: PrintWriter, args: Array<out String>?) {
        stats.lines().forEach(writer::println)
        val exempt = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        writer.println("push keepaliveSeconds=$keepaliveSeconds trafficTag=0x${TRAFFIC_TAG.toString(16)} batteryExempt=$exempt")
    }

    /** Every socket of the push client carries [TRAFFIC_TAG], whichever of OkHttp's threads opens it. */
    private object TaggedSockets : SocketFactory() {
        private val plain: SocketFactory = getDefault()

        private fun <T> tagged(make: () -> T): T {
            // The system tags a socket with its thread's tag when the socket is made.
            TrafficStats.setThreadStatsTag(TRAFFIC_TAG)
            return make()
        }

        override fun createSocket(): Socket = tagged { plain.createSocket() }
        override fun createSocket(host: String, port: Int): Socket = tagged { plain.createSocket(host, port) }
        override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
            tagged { plain.createSocket(host, port, localHost, localPort) }

        override fun createSocket(host: InetAddress, port: Int): Socket = tagged { plain.createSocket(host, port) }
        override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
            tagged { plain.createSocket(address, port, localAddress, localPort) }
    }

    companion object {
        private const val TAG = "NexusPush"
        private const val PREFS = "nexus_push"
        private const val KEY_ADDRESS = "address"
        private const val KEY_KEEPALIVE = "keepaliveSeconds"
        private const val KEY_LAST_ID = "lastId"
        private const val SERVICE_CHANNEL = "nexus_push_service"
        private const val MESSAGE_CHANNEL = "nexus_push"
        private const val SERVICE_NOTIFICATION_ID = 4711

        /** "NXPU": what the push socket's bytes are counted under in `dumpsys netstats detail`. */
        const val TRAFFIC_TAG = 0x4E585055
    }
}

/**
 * Starts and stops [NexusPushService] to match the app's saved `settings.push.enabled` (on unless
 * switched off) and whether a person is signed in: when the setting changes, when the launcher
 * starts, and whenever the app comes to the front.
 */
object NexusPushTrigger {
    private const val TAG = "NexusPush"
    private const val FILE = "FlutterSharedPreferences"
    const val KEY = "flutter.settings.push.enabled"

    // Held so the listener is not garbage collected.
    private var listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener? = null

    fun watch(context: Context) {
        val app = context.applicationContext
        if (listener != null) return
        val l = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY) sync(app)
        }
        listener = l
        app.getSharedPreferences(FILE, Context.MODE_PRIVATE).registerOnSharedPreferenceChangeListener(l)
        sync(app)
    }

    fun sync(context: Context) {
        val app = context.applicationContext
        val intent = Intent(app, NexusPushService::class.java)
        val wanted = app.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY, true) &&
            NexusConfig.read(app).enabled
        runCatching {
            if (wanted) app.startForegroundService(intent) else app.stopService(intent)
        }.onFailure { Log.w(TAG, "push_sync_failed wanted=$wanted", it) }
    }
}

/**
 * Switches push on or off from a computer, for a battery measurement with it and one without:
 * `adb shell am broadcast -n <package>/app.lawnchair.nexus.push.NexusPushSwitch --ez on false`.
 * Only the shell may send it (the receiver asks for the DUMP permission).
 */
class NexusPushSwitch : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val on = intent.getBooleanExtra("on", true)
        context.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
            .edit().putBoolean(NexusPushTrigger.KEY, on).apply()
        NexusPushTrigger.sync(context)
        Log.i("NexusPush", "push_switch on=$on")
    }
}
