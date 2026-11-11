package app.lawnchair.nexus

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The one ongoing notification Nexus keeps while it works in the background. Every foreground
 * service of the launcher (the earbuds' double tap, the push connection) stands behind this same
 * notification, so the shade has one line for Nexus that says what it is doing, and not a banner
 * for each service. The system keeps a notification that two foreground services share for as long
 * as either of them runs.
 *
 * A service sets its part ([voice], [push]) and calls [show]; it clears its part when it stops.
 */
object NexusBanner {
    const val ID = 7410
    private const val CHANNEL = "nexus_status"

    /** What the earbuds' service says, or null while it is not running. */
    @Volatile var voice: VoiceLine? = null

    /** How the push connection stands, or null while it is not running. */
    @Volatile var push: PushLine? = null

    /** The earbuds' line: [text] as the service words it, [inTurn] while a turn is going on. */
    data class VoiceLine(val text: String, val inTurn: Boolean)

    /** The push connection: listening since [connectedAt], or trying to connect (null); [received] so far. */
    data class PushLine(val connectedAt: Long?, val received: Int)

    fun build(context: Context): Notification {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Nexus in the background", NotificationManager.IMPORTANCE_LOW))
        // The channels the services had each to themselves before there was one banner.
        manager.deleteNotificationChannel("nexus_buds")
        manager.deleteNotificationChannel("nexus_push_service")
        val text = bannerText(voice, push, ::clock)
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, NexusAppActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Nexus")
            .setContentText(text.summary)
            .setStyle(Notification.BigTextStyle().bigText(text.lines.joinToString("\n")))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    /** Draws the banner again after a part changed. Nothing when no service stands behind it. */
    fun show(context: Context) {
        if (voice == null && push == null) return
        context.getSystemService(NotificationManager::class.java).notify(ID, build(context))
    }

    private fun clock(at: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(at))
}

/** What the banner says: [summary] on its one line, [lines] when it is pulled open. */
data class BannerText(val summary: String, val lines: List<String>)

/**
 * The banner's words. A turn that is going on is the news and takes the line to itself; otherwise
 * the line names what Nexus is listening for, and the open banner has a line for each.
 */
fun bannerText(voice: NexusBanner.VoiceLine?, push: NexusBanner.PushLine?, clock: (Long) -> String): BannerText {
    val pushLine = push?.let {
        val since = it.connectedAt
        when {
            since == null -> "Notifications: connecting"
            it.received == 0 -> "Notifications: listening since ${clock(since)}"
            else -> "Notifications: listening since ${clock(since)}, ${it.received} received"
        }
    }
    val lines = listOfNotNull(voice?.text, pushLine)
    val summary = when {
        voice?.inTurn == true -> voice.text
        else -> listOfNotNull(
            push?.let { if (it.connectedAt == null) "Notifications connecting" else "Notifications on" },
            voice?.let { "double-tap to talk" },
        ).joinToString(" · ").replaceFirstChar { it.uppercase() }
    }
    return BannerText(summary.ifEmpty { "Nexus" }, lines.ifEmpty { listOf("Nexus") })
}
