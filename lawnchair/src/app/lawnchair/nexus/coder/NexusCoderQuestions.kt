package app.lawnchair.nexus.coder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.util.Base64
import android.util.Log
import app.lawnchair.nexus.NexusAppActivity
import app.lawnchair.nexus.NexusConfig
import com.android.launcher3.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The questions the person's coding agents wait on, as notifications. The server's nudge
 * ([app.lawnchair.nexus.push.NexusPush.CoderQuestions]) says only that they changed; the list is
 * read over the sealed coder route, each question waiting gets one notification, and one no longer
 * waiting (answered anywhere, or its wait ran out) has its notification taken away. Fetched with the
 * screen off too: a question waits ten minutes by default, and the push socket is up anyway.
 */
object NexusCoderQuestions {
    private const val TAG = "NexusCoderQuestions"
    private const val CHANNEL = "nexus_coder_questions"
    private const val PREFS = "nexus_coder_questions"
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
        val waiting = runCatching {
            parseWaitingQuestions(CoderHostClient(status.serverUrl, status.authKey, sealKey).call("waitingQuestions"))
        }.onFailure { Log.w(TAG, "coder_questions_fetch_failed ${it.javaClass.simpleName}") }
            .getOrNull() ?: return
        show(app, waiting)
    }

    private fun show(app: Context, waiting: List<WaitingQuestion>) {
        val manager = app.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Questions from your agents", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "A coding agent waits for your answer"
            },
        )
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val keys = waiting.map { it.key }.toSet()
        for (gone in prefs.getStringSet(KEY_SHOWN, emptySet()).orEmpty() - keys) {
            manager.cancel(notificationId(gone))
        }
        for (question in waiting) {
            manager.notify(notificationId(question.key), notification(app, question, now))
        }
        prefs.edit().putStringSet(KEY_SHOWN, keys).apply()
        Log.i(TAG, "coder_questions_shown count=${waiting.size}")
    }

    private fun notification(app: Context, question: WaitingQuestion, now: Long): Notification {
        val open = PendingIntent.getActivity(
            app,
            notificationId(question.key),
            NexusAppActivity.routeIntent(app, question.route),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(app, CHANNEL)
            .setSmallIcon(com.android.launcher3.R.drawable.ic_nexus_status)
            .setContentTitle(question.title)
            .setContentText(question.text.take(160))
            .setStyle(Notification.BigTextStyle().bigText(question.details(now).take(900)))
            .setWhen(question.askedAtMs)
            .setShowWhen(true)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setAutoCancel(true)
            .apply { question.decidedAtMs?.let { setTimeoutAfter((it - now).coerceAtLeast(1_000)) } }
            .build()
    }

    private fun notificationId(key: String): Int = "coder:$key".hashCode()
}
