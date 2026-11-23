package app.lawnchair.nexus.cyber

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.util.Base64
import android.util.Log
import app.lawnchair.nexus.NexusAppActivity
import app.lawnchair.nexus.NexusConfig
import app.lawnchair.nexus.net.SealedHostClient
import com.android.launcher3.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The cards that wait on the household in nexus-cyber, as notifications. The server's nudge
 * ([app.lawnchair.nexus.push.NexusPush.CyberCards]) says only that they changed; they are read
 * over the sealed cyber route, which answers an admin only (anyone else is refused and sees
 * nothing). Each card gets one notification, posted once, so one swiped away stays away; a card
 * no longer waiting (decided on the Mac or the phone, or its exposure closed) has its notification
 * taken away. A tap opens the Cyber page.
 */
object NexusCyberCards {
    private const val TAG = "NexusCyberCards"
    private const val CHANNEL = "nexus_cyber_cards"
    private const val PREFS = "nexus_cyber_cards"
    private const val KEY_SHOWN = "shown"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshing = Mutex()

    private val sealKey: ByteArray
        get() = Base64.decode(BuildConfig.NEXUS_RELAY_PUBLIC_KEY.trim(), Base64.DEFAULT)

    fun onNudge(context: Context) {
        val app = context.applicationContext
        scope.launch { refreshing.withLock { refresh(app) } }
    }

    private fun refresh(app: Context) {
        if (BuildConfig.NEXUS_RELAY_PUBLIC_KEY.isBlank()) return
        val status = NexusConfig.read(app)
        if (!status.enabled) return
        val host = SealedHostClient(status.serverUrl, "cyber", status.authKey, sealKey)
        runCatching { parseWaitingCards(host.call("waiting")) }
            .onFailure { Log.w(TAG, "cyber_cards_fetch_failed ${it.javaClass.simpleName}") }
            .getOrNull()
            ?.let { show(app, it) }
    }

    private fun show(app: Context, waiting: List<WaitingCard>) {
        val manager = app.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Cyber: needs you", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Something on the home network waits on your decision"
            },
        )
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val shown = prefs.getStringSet(KEY_SHOWN, emptySet()).orEmpty()
        val ids = waiting.map { it.id }.toSet()
        for (gone in shown - ids) manager.cancel(notificationId(gone))
        for (card in waiting) {
            if (card.id !in shown) manager.notify(notificationId(card.id), notification(app, card))
        }
        prefs.edit().putStringSet(KEY_SHOWN, ids).apply()
        Log.i(TAG, "cyber_cards_shown count=${waiting.size}")
    }

    private fun notification(app: Context, card: WaitingCard): Notification {
        val open = PendingIntent.getActivity(
            app,
            notificationId(card.id),
            NexusAppActivity.aloneIntent(app, CYBER_ROUTE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(app, CHANNEL)
            .setSmallIcon(com.android.launcher3.R.drawable.ic_nexus_status)
            .setContentTitle(card.title)
            .setContentText(card.text)
            .setStyle(Notification.BigTextStyle().bigText("${card.text}\n\n${card.summary}".take(900)))
            .setWhen(card.raisedAtMs)
            .setShowWhen(true)
            .setCategory(Notification.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
    }

    private fun notificationId(id: String): Int = "cyber:$id".hashCode()
}
