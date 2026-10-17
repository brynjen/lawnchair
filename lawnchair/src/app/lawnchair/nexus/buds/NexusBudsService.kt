package app.lawnchair.nexus.buds

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import app.lawnchair.nexus.NexusAppActivity
import app.lawnchair.nexus.NexusConfig
import app.lawnchair.nexus.chat.MicGesture
import app.lawnchair.nexus.chat.Mode
import app.lawnchair.nexus.chat.NexusChatController
import app.lawnchair.nexus.chat.TurnPhase
import app.lawnchair.nexus.net.NexusClient
import app.lawnchair.nexus.voice.NexusAudioRoute
import app.lawnchair.nexus.voice.NexusCuePlayer
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Lets a double tap on Bluetooth earbuds (the next-track key) start a turn with Nexus, with the app in
 * the background and the screen off.
 *
 * It is a foreground service (microphone, so the mic can be opened from the background) that keeps a
 * [MediaSession] active: the system sends the earbuds' media keys to the most recent active session,
 * so while nothing else is playing, they come here. Another app that is playing music keeps its keys.
 * A key does what [budsAction] says: a double tap starts a turn (the earbuds' mic, then the reply in
 * the earbuds), sends what has been said, or cuts Nexus off and listens again; a single tap stops a
 * turn that is going and is otherwise left alone.
 *
 * Started and stopped by [NexusBudsTrigger] from the Microphone setting "Double-tap to talk".
 */
class NexusBudsService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var session: MediaSession
    private var controller: NexusChatController? = null
    private var controllerUsedAt = 0L
    private var watching: Job? = null
    private var phase = TurnPhase.Idle

    // The one link to the earbuds, shared by the cue and the turn.
    private val route by lazy { NexusAudioRoute(this) }

    // The spoken "I'm listening", fetched ahead from the server.
    private val cues by lazy {
        val transport = NexusClient()
        NexusReadyCues(
            fetch = { language ->
                val status = NexusConfig.read(this)
                if (!status.enabled) null else transport.readyCue(status.serverUrl, status.authKey, language)
            },
            language = {
                speechLanguageOf(getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE).getString("flutter.settings.voice.language", null))
            },
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(CHANNEL, "Earbud double-tap", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        runCatching {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification(TurnPhase.Idle),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0,
            )
        }.onFailure {
            // Not allowed to start as a foreground service from where it was started: say so and stop.
            Log.w(TAG, "buds_foreground_refused", it)
            stopSelf()
            return
        }
        session = MediaSession(this, "NexusBuds").apply {
            setCallback(
                object : MediaSession.Callback() {
                    override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean = onKey(mediaButtonIntent)
                },
            )
            // Playing for a moment is what puts this session first for the media keys; paused after it,
            // nothing is shown as playing.
            setPlaybackState(playback(PlaybackState.STATE_PLAYING))
            isActive = true
        }
        handler.postDelayed({ session.setPlaybackState(playback(PlaybackState.STATE_PAUSED)) }, 600)
        cues.prefetch()
        Log.i(TAG, "buds_listening")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (::session.isInitialized) {
            runCatching { session.isActive = false }
            runCatching { session.release() }
        }
        watching?.cancel()
        controller?.close()
        controller = null
        scope.cancel()
        Log.i(TAG, "buds_stopped")
        super.onDestroy()
    }

    private fun playback(state: Int): PlaybackState = PlaybackState.Builder()
        .setActions(
            PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_STOP,
        )
        .setState(state, 0L, 1f)
        .build()

    /** A media key from the earbuds. True when it was Nexus's to take. */
    private fun onKey(intent: Intent): Boolean {
        val event = (
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
            }
            ) ?: return false
        val tap = when (event.keyCode) {
            KeyEvent.KEYCODE_MEDIA_NEXT -> Tap.Double
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK,
            KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_STOP,
            -> Tap.Single
            else -> return false
        }
        // With the app's Home screen in front it takes the tap and shows the turn; this runs it only in the background.
        if (appTakesTap(tap, NexusBudsBridge.resumed, NexusBudsBridge.homeReady, NexusBudsBridge.busy)) {
            Log.i(TAG, "buds_key key=${KeyEvent.keyCodeToString(event.keyCode)} act=${event.action} -> the app")
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) NexusBudsBridge.forward(tap)
            return true
        }
        val action = budsAction(tap, phase)
        Log.i(TAG, "buds_key key=${KeyEvent.keyCodeToString(event.keyCode)} act=${event.action} phase=$phase -> $action")
        if (action == BudsAction.Ignore) return false
        // The key goes down and comes up: act on the press, swallow the release.
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) act(action)
        return true
    }

    private fun act(action: BudsAction) {
        when (action) {
            BudsAction.StartListening -> startTurn()
            BudsAction.FinishListening -> controller?.endRecordingAndSend()
            BudsAction.InterruptAndListen -> {
                controller?.interrupt()
                startTurn()
            }
            BudsAction.Cancel -> controller?.interrupt()
            BudsAction.Ignore -> Unit
        }
    }

    private fun startTurn() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "buds_no_mic_permission")
            show("Open Nexus and allow the microphone")
            return
        }
        val status = NexusConfig.read(this)
        if (!status.enabled) {
            Log.w(TAG, "buds_not_ready lock=${status.lock}")
            show("Open Nexus to finish setting up")
            return
        }
        val c = controller?.takeIf { System.currentTimeMillis() - controllerUsedAt < CONVERSATION_MS }
            ?: newController(status.serverUrl, status.authKey)
        controllerUsedAt = System.currentTimeMillis()
        scope.launch {
            // The earbuds' mic and speaker first, so the cue is heard in them. The controller takes the
            // same link (it is the same object) and gives it back when the turn is over.
            route.acquire()
            // The line is spoken in the earbuds, in the person's voice; playing it brings the call link up.
            // A beep when none is ready or it would not play.
            val spoken = cues.take()?.let { NexusCuePlayer.speak(it, route) } ?: false
            if (!spoken) {
                cue(route.held)
                delay(CUE_MS)
            }
            c.startListening(handsFree = true)
        }
    }

    private fun newController(serverUrl: String, token: String): NexusChatController {
        controller?.close()
        watching?.cancel()
        val c = NexusChatController(
            transport = NexusClient(),
            serverUrl = serverUrl,
            token = token,
            initialMode = Mode.Voice,
            initialGesture = MicGesture.Tap,
            route = route,
            micPrefs = { NexusMicPrefs.read(this) },
            watchBox = false,
        )
        controller = c
        watching = scope.launch {
            c.state.collect { s ->
                if (s.phase != phase) {
                    phase = s.phase
                    show(null)
                    if (phase == TurnPhase.Done || phase == TurnPhase.Failed || phase == TurnPhase.Idle) {
                        controllerUsedAt = System.currentTimeMillis()
                    }
                }
            }
        }
        return c
    }

    /** A short tone: Nexus is listening. Played in the call stream when the earbuds are in call mode. */
    private fun cue(onHeadset: Boolean) {
        runCatching {
            val stream = if (onHeadset) AudioManager.STREAM_VOICE_CALL else AudioManager.STREAM_MUSIC
            val tone = ToneGenerator(stream, 70)
            tone.startTone(ToneGenerator.TONE_PROP_ACK, CUE_MS.toInt())
            handler.postDelayed({ tone.release() }, CUE_MS + 100)
        }
    }

    private fun show(text: String?) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification(phase, text))
    }

    private fun notification(phase: TurnPhase, override: String? = null): Notification {
        val text = override ?: when (phase) {
            TurnPhase.Listening -> "Listening… double-tap to send"
            TurnPhase.Thinking -> "Thinking… double-tap to cut in"
            TurnPhase.Speaking -> "Speaking… double-tap to cut in"
            TurnPhase.AwaitingAnswer -> "Waiting for your answer — double-tap to speak"
            else -> "Double-tap your earbuds to talk to Nexus"
        }
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, NexusAppActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Nexus")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    private companion object {
        const val TAG = "NexusBuds"
        const val CHANNEL = "nexus_buds"
        const val NOTIFICATION_ID = 7410

        /** How long the cue plays before the mic opens, so it is not heard as speech. */
        const val CUE_MS = 220L

        /** A conversation is kept for this long after the last turn; a double tap later starts a new one. */
        const val CONVERSATION_MS = 10 * 60_000L
    }
}

/**
 * Starts and stops [NexusBudsService] to match the Microphone setting "Double-tap to talk" (the app's
 * saved `settings.mic.earbudTrigger`): when the setting changes, and whenever the app comes to the
 * front. A service can only be started as a foreground service from the foreground, which is where a
 * person is when they flip the switch.
 */
object NexusBudsTrigger {
    private const val TAG = "NexusBuds"
    private const val FILE = "FlutterSharedPreferences"

    // Held so the listener is not garbage collected.
    private var listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener? = null

    fun watch(context: Context) {
        val app = context.applicationContext
        if (listener != null) return
        val prefs = app.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val l = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "flutter.settings.mic.earbudTrigger") sync(app)
        }
        listener = l
        prefs.registerOnSharedPreferenceChangeListener(l)
        sync(app)
    }

    fun sync(context: Context) {
        val app = context.applicationContext
        val intent = Intent(app, NexusBudsService::class.java)
        val wanted = NexusMicPrefs.read(app).earbudTrigger &&
            app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        runCatching {
            if (wanted) app.startForegroundService(intent) else app.stopService(intent)
        }.onFailure { Log.w(TAG, "buds_sync_failed wanted=$wanted", it) }
    }
}
