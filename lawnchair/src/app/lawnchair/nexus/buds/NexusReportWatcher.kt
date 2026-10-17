package app.lawnchair.nexus.buds

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.util.Log
import app.lawnchair.nexus.NexusAppActivity
import app.lawnchair.nexus.net.NexusTransport
import app.lawnchair.nexus.voice.NexusTtsPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Listens to the person's conversation for as long as the earbud service lives, so a report of a
 * finished background run (which the server writes by itself, with nobody asking) reaches them
 * where they are: said in the earbuds, said on the phone, or as a notification ([reportAction]).
 * The app tells which conversation is the current one ([watch]).
 */
class NexusReportWatcher(
    private val context: Context,
    private val scope: CoroutineScope,
    private val transport: NexusTransport,
    private val server: () -> Pair<String, String>?,
) {
    private var watching: Int? = null
    private var job: Job? = null
    private var player: NexusTtsPlayer? = null

    private val announcer = NexusReportAnnouncer(
        headsetConnected = { headsetConnected(context) },
        appInFront = { NexusBudsBridge.resumed },
        speak = { segment ->
            val p = player ?: NexusTtsPlayer(scope).also { player = it }
            p.enqueue(segment)
        },
        endSpeech = {
            player?.markEndOfStream()
            player = null
        },
        notify = ::notify,
    )

    /** Follows [conversationId]; the one being followed already is left alone. */
    fun watch(conversationId: Int?) {
        if (conversationId == null || conversationId == watching) return
        watching = conversationId
        job?.cancel()
        job = scope.launch {
            var wait = 2_000L
            while (isActive) {
                val (base, token) = server() ?: run { delay(30_000); null } ?: continue
                val got = runCatching {
                    transport.watchConversation(base, token, conversationId).collect { event ->
                        wait = 2_000L
                        announcer.onEvent(event)
                    }
                }
                got.exceptionOrNull()?.let { Log.w(TAG, "report_watch_lost conv=$conversationId", it) }
                // The stream ended (the connection went): take it up again, a little later each time.
                delay(wait)
                wait = (wait * 2).coerceAtMost(60_000L)
            }
        }
        Log.i(TAG, "report_watch conv=$conversationId")
    }

    fun stop() {
        job?.cancel()
        job = null
        watching = null
        player?.release()
        player = null
    }

    private fun notify(text: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Reports from your team", NotificationManager.IMPORTANCE_DEFAULT),
        )
        val open = PendingIntent.getActivity(
            context,
            1,
            Intent(context, NexusAppActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        manager.notify(
            REPORT_NOTIFICATION_ID,
            Notification.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Nexus")
                .setContentText(text.take(160))
                .setStyle(Notification.BigTextStyle().bigText(text.take(600)))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }

    companion object {
        private const val TAG = "NexusBuds"
        private const val CHANNEL = "nexus_reports"
        private const val REPORT_NOTIFICATION_ID = 7411

        /** Whether earbuds or another headset are connected to take sound. */
        fun headsetConnected(context: Context): Boolean {
            val audio = context.getSystemService(AudioManager::class.java)
            return audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in HEADSET_TYPES }
        }

        private val HEADSET_TYPES = setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_HEARING_AID,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET,
        )
    }
}
