package app.lawnchair.nexus

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import app.lawnchair.nexus.buds.BUDS_ACTION_STOP_TURN
import app.lawnchair.nexus.buds.NexusBudsService
import com.android.launcher3.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The one ongoing notification Nexus keeps while it works in the background. Every foreground
 * service of the launcher (the earbuds' double tap, the push connection) stands behind this same
 * notification, so the shade has one line for Nexus, and not a banner for each service. The system
 * keeps a notification that two foreground services share for as long as either of them runs.
 *
 * What it says is the box: whether it can be reached, the mode it is in and the job that holds it,
 * with the buttons that fit that state ([box], kept current by [NexusBannerSync]; nexus
 * `docs/ROADMAP.md`, "The banner"). A turn going on in the earbuds takes the line while it lasts.
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

    /** What the server last said of the box, or that it did not answer; null before the first answer. */
    @Volatile var box: BoxLine? = null

    /** The earbuds' line: [text] as the service words it, [inTurn] while a turn is going on. */
    data class VoiceLine(val text: String, val inTurn: Boolean)

    /** The push connection: listening since [connectedAt], or trying to connect (null); [received] so far. */
    data class PushLine(val connectedAt: Long?, val received: Int)

    /**
     * The box as the server described it: [headline] for the one line, [lines] for the banner pulled
     * open, [actions] for its buttons. Or, when the server did not answer, offline [since].
     */
    data class BoxLine(
        val online: Boolean,
        val since: Long = 0L,
        val headline: String = "",
        val lines: List<String> = emptyList(),
        val actions: List<BannerAction> = emptyList(),
    )

    /** A button the server named: [id] goes back to it, [confirm] is asked first when it ends something. */
    data class BannerAction(val id: String, val label: String, val confirm: String?)

    /** Whether a service stands behind the banner, so it is in the shade. */
    val shown: Boolean get() = voice != null || push != null

    fun build(context: Context): Notification {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Nexus in the background", NotificationManager.IMPORTANCE_LOW))
        // The channels the services had each to themselves before there was one banner.
        manager.deleteNotificationChannel("nexus_buds")
        manager.deleteNotificationChannel("nexus_push_service")
        val box = box
        val text = bannerText(voice, push, box, ::clock)
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, NexusAppActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = Notification.Builder(context, CHANNEL)
            .setSmallIcon(if (box?.online == false) R.drawable.ic_nexus_status_offline else R.drawable.ic_nexus_status)
            .setContentTitle("Nexus")
            .setContentText(text.summary)
            .setStyle(Notification.BigTextStyle().bigText(text.lines.joinToString("\n")))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
        bannerButtons(voice, box).forEachIndexed { index, button -> builder.addAction(action(context, index, button)) }
        return builder.build()
    }

    /** Draws the banner again after a part changed. Nothing when no service stands behind it. */
    fun show(context: Context) {
        if (!shown) return
        context.getSystemService(NotificationManager::class.java).notify(ID, build(context))
    }

    /**
     * Says that Nexus is not listening: the system refused the push service (the launcher came up
     * with the screen off and is not let off battery optimisation), and it starts at the next unlock.
     * The service's own banner takes this one's place when it starts.
     */
    fun showNotListening(context: Context) {
        if (shown) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Nexus in the background", NotificationManager.IMPORTANCE_LOW))
        manager.notify(
            ID,
            Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_nexus_status_offline)
                .setContentTitle("Nexus")
                .setContentText(NOT_LISTENING)
                .setOnlyAlertOnce(true)
                .build(),
        )
    }

    private fun action(context: Context, index: Int, button: BannerButton): Notification.Action {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val intent = when (button) {
            BannerButton.StopTurn -> PendingIntent.getService(
                context,
                10,
                Intent(context, NexusBudsService::class.java).setAction(BUDS_ACTION_STOP_TURN),
                flags,
            )
            BannerButton.CheckNow -> PendingIntent.getBroadcast(
                context,
                11,
                Intent(context, NexusBannerReceiver::class.java).setAction(NexusBannerReceiver.ACTION_CHECK),
                flags,
            )
            is BannerButton.Server -> if (button.action.confirm == null) {
                PendingIntent.getBroadcast(
                    context,
                    20 + index,
                    Intent(context, NexusBannerReceiver::class.java)
                        .setAction(NexusBannerReceiver.ACTION_ACT)
                        .putExtra(NexusBannerReceiver.EXTRA_ID, button.action.id),
                    flags,
                )
            } else {
                // Something is ended by it: asked first, in a dialog over whatever app is in front.
                PendingIntent.getActivity(
                    context,
                    30 + index,
                    Intent(context, NexusBannerConfirmActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        .putExtra(NexusBannerReceiver.EXTRA_ID, button.action.id)
                        .putExtra(NexusBannerConfirmActivity.EXTRA_LABEL, button.action.label)
                        .putExtra(NexusBannerConfirmActivity.EXTRA_QUESTION, button.action.confirm),
                    flags,
                )
            }
        }
        val built = Notification.Action.Builder(null as Icon?, button.label, intent)
        // What ends a job is not done from a locked screen.
        if (button is BannerButton.Server && button.action.confirm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            built.setAuthenticationRequired(true)
        }
        return built.build()
    }

    private fun clock(at: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(at))

    const val NOT_LISTENING = "Not listening until you unlock"
}

/** What the banner says: [summary] on its one line, [lines] when it is pulled open. */
data class BannerText(val summary: String, val lines: List<String>)

/**
 * The banner's words. The one line is the box: `Online`, with what the server said of it, or
 * `Offline since` the time it stopped answering. A turn that is going on in the earbuds is the news
 * and takes the line to itself. Pulled open, the state stands first and the server's lines follow;
 * the push connection is named only while it is not up.
 */
fun bannerText(
    voice: NexusBanner.VoiceLine?,
    push: NexusBanner.PushLine?,
    box: NexusBanner.BoxLine?,
    clock: (Long) -> String,
): BannerText {
    val state = when {
        box == null -> null
        !box.online -> "Offline since ${clock(box.since)}"
        box.headline.isEmpty() -> "Online"
        else -> "Online · ${box.headline}"
    }
    val turn = voice?.takeIf { it.inTurn }?.text
    val connecting = if (push != null && push.connectedAt == null) "Notifications: connecting" else null
    val summary = turn ?: state ?: connecting ?: "Nexus"
    val lines = listOfNotNull(turn, state) + (if (box?.online == true) box.lines else emptyList()) + listOfNotNull(connecting)
    return BannerText(summary, lines.ifEmpty { listOf("Nexus") })
}

/** A button of the banner: the phone's own two, or one the server named. */
sealed interface BannerButton {
    val label: String

    /** Ends the turn going on in the earbuds. */
    data object StopTurn : BannerButton {
        override val label = "Stop"
    }

    /** Asks the server again, when it did not answer. */
    data object CheckNow : BannerButton {
        override val label = "Check now"
    }

    data class Server(val action: NexusBanner.BannerAction) : BannerButton {
        override val label get() = action.label
    }
}

/**
 * The banner's buttons, at most three (what a notification shows): Stop while a turn is going on,
 * Check now while the box does not answer, and otherwise what the server offers for its state.
 */
fun bannerButtons(voice: NexusBanner.VoiceLine?, box: NexusBanner.BoxLine?): List<BannerButton> {
    val stop = if (voice?.inTurn == true) listOf(BannerButton.StopTurn) else emptyList()
    val forBox = when {
        box == null -> emptyList()
        !box.online -> listOf(BannerButton.CheckNow)
        else -> box.actions.map { BannerButton.Server(it) }
    }
    return (stop + forBox).take(3)
}
