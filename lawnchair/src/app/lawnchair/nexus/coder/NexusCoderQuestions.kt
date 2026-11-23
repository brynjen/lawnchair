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
import app.lawnchair.nexus.net.SealedHostClient
import com.android.launcher3.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The questions the person's coding agents wait on, and what they answered, as notifications. The
 * server's nudge ([app.lawnchair.nexus.push.NexusPush.CoderQuestions]) says only that either
 * changed; both lists are read over the sealed coder route. Each question waiting gets one
 * notification, and one no longer waiting (answered anywhere, or its wait ran out) has its
 * notification taken away; a tap alone does not take it, since the question still waits. Each reply
 * kept for the phone (a run the person started here, or from NexusCoder while away from the Mac)
 * gets one notification for its session, shown once, and taken away once the session is opened or
 * sent something. Fetched with the screen off too: a question waits ten minutes by default, and the
 * push socket is up anyway.
 */
object NexusCoderQuestions {
    private const val TAG = "NexusCoderQuestions"
    private const val CHANNEL = "nexus_coder_questions"
    private const val REPLIES_CHANNEL = "nexus_coder_replies"
    private const val PREFS = "nexus_coder_questions"
    private const val KEY_SHOWN = "shown"
    private const val KEY_REPLIES = "replies"

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
        val host = SealedHostClient(status.serverUrl, "coder", status.authKey, sealKey)
        runCatching { parseWaitingQuestions(host.call("waitingQuestions")) }
            .onFailure { Log.w(TAG, "coder_questions_fetch_failed ${it.javaClass.simpleName}") }
            .getOrNull()
            ?.let { show(app, it) }
        runCatching { parseCoderReplies(host.call("replies")) }
            .onFailure { Log.w(TAG, "coder_replies_fetch_failed ${it.javaClass.simpleName}") }
            .getOrNull()
            ?.let { showReplies(app, it) }
    }

    /**
     * One notification for each session with a reply kept, posted once per reply, so one swiped away
     * stays away; a session whose reply is no longer kept has its notification taken away.
     */
    private fun showReplies(app: Context, replies: List<CoderReply>) {
        val manager = app.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(REPLIES_CHANNEL, "Replies from your agents", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "A coding agent finished what you asked"
            },
        )
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val shown = prefs.getStringSet(KEY_REPLIES, emptySet()).orEmpty()
        val keys = replies.map { it.key }.toSet()
        val sessions = replies.map { it.sessionId }.toSet()
        for (gone in shown - keys) {
            val session = gone.substringBeforeLast('/')
            if (session !in sessions) manager.cancel(replyNotificationId(session))
        }
        for (reply in replies) {
            if (reply.key !in shown) manager.notify(replyNotificationId(reply.sessionId), replyNotification(app, reply))
        }
        prefs.edit().putStringSet(KEY_REPLIES, keys).apply()
        Log.i(TAG, "coder_replies_shown count=${replies.size}")
    }

    private fun replyNotification(app: Context, reply: CoderReply): Notification {
        val open = PendingIntent.getActivity(
            app,
            replyNotificationId(reply.sessionId),
            NexusAppActivity.aloneIntent(app, reply.route),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(app, REPLIES_CHANNEL)
            .setSmallIcon(com.android.launcher3.R.drawable.ic_nexus_status)
            .setContentTitle(reply.title)
            .setContentText(reply.line.take(160))
            .setStyle(Notification.BigTextStyle().bigText(reply.details.take(1800)))
            .setWhen(reply.atMs)
            .setShowWhen(true)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
    }

    private fun replyNotificationId(sessionId: String): Int = "coder-reply:$sessionId".hashCode()

    private fun show(app: Context, waiting: List<WaitingQuestion>) {
        val manager = app.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Questions from your agents", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "A coding agent waits for your answer"
            },
        )
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val keys = waiting.map { it.key }.toSet()
        for (gone in prefs.getStringSet(KEY_SHOWN, emptySet()).orEmpty() - keys) {
            manager.cancel(notificationId(gone))
        }
        for (question in waiting) {
            manager.notify(notificationId(question.key), notification(app, question))
        }
        prefs.edit().putStringSet(KEY_SHOWN, keys).apply()
        Log.i(TAG, "coder_questions_shown count=${waiting.size}")
    }

    private fun notification(app: Context, question: WaitingQuestion): Notification {
        val open = PendingIntent.getActivity(
            app,
            notificationId(question.key),
            NexusAppActivity.aloneIntent(app, question.route),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(app, CHANNEL)
            .setSmallIcon(com.android.launcher3.R.drawable.ic_nexus_status)
            .setContentTitle(question.title)
            .setContentText(question.text.take(160))
            .setStyle(Notification.BigTextStyle().bigText(question.details().take(900)))
            .setWhen(question.askedAtMs)
            .setShowWhen(true)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .apply {
                question.decidedAtMs?.let { setTimeoutAfter((it - System.currentTimeMillis()).coerceAtLeast(1_000)) }
            }
            .build()
    }

    private fun notificationId(key: String): Int = "coder:$key".hashCode()
}
