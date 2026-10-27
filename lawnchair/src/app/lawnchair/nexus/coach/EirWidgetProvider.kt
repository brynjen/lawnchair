package app.lawnchair.nexus.coach

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import app.lawnchair.nexus.NexusAppActivity
import com.android.launcher3.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Eir, the health coach, as a home-screen widget: the readiness light, the coach's word for today and
 * the check-ins waiting. Tapping it opens the Nexus app at `/coach`, where the chat is. It is a widget
 * to be placed, not part of the News page, because not everyone has a coach. A day that could not be
 * read (no network, no relay key in this build, nobody signed in) is a plain "Open Eir".
 *
 * Read over the sealed coach route by [EirCoach]. The system asks for an update when the widget is
 * placed and every 30 minutes after (`eir_widget_info.xml`); nothing runs in between.
 */
class EirWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // The broadcast has seconds, not minutes: past that the widget says "Open Eir".
                val today = withTimeoutOrNull(LOAD_TIMEOUT_MS) { EirCoach.load(context) }
                for (id in ids) manager.updateAppWidget(id, views(context, today))
            } finally {
                pending.finish()
            }
        }
    }

    private fun views(context: Context, today: EirCoach.Today?): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.eir_widget)
        views.setOnClickPendingIntent(R.id.eir_widget_root, openEir(context))
        views.setTextViewText(
            R.id.eir_widget_text,
            today?.suggestion
                ?: context.getString(if (today != null) R.string.eir_widget_no_data else R.string.eir_widget_open),
        )
        views.setImageViewResource(
            R.id.eir_widget_light,
            when (today?.light) {
                "normal" -> R.drawable.eir_widget_light_normal
                "watch" -> R.drawable.eir_widget_light_watch
                "strain" -> R.drawable.eir_widget_light_strain
                else -> R.drawable.eir_widget_light_none
            },
        )
        val checkIns = today?.pendingCheckIns ?: 0
        views.setViewVisibility(R.id.eir_widget_check_ins, if (checkIns > 0) View.VISIBLE else View.GONE)
        if (checkIns > 0) {
            views.setTextViewText(
                R.id.eir_widget_check_ins,
                if (checkIns == 1) {
                    context.getString(R.string.eir_widget_check_in)
                } else {
                    context.getString(R.string.eir_widget_check_ins, checkIns)
                },
            )
        }
        return views
    }

    private fun openEir(context: Context): PendingIntent {
        // The route extra is Flutter's initial route on a cold start; the deep link carries the same path to
        // an app that is already running (the activity is singleTop).
        val intent = Intent(context, NexusAppActivity::class.java)
            .setData(Uri.parse("nexus-health://coach/coach"))
            .putExtra("route", "/coach")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private companion object {
        const val LOAD_TIMEOUT_MS = 8_000L
    }
}
