package app.lawnchair.nexus

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.Bundle
import android.os.PowerManager
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import app.lawnchair.nexus.net.NexusClient
import app.lawnchair.nexus.net.NexusUnauthorizedException
import app.lawnchair.nexus.push.postPushNotification
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Keeps [NexusBanner.box] in line with the server (nexus `docs/ROADMAP.md`, "The banner"). Android
 * does not say when the shade is pulled down, so the banner is fetched at the moments that can be
 * known: the screen comes on, the home screen or the app comes to the front, the server's nudge on
 * the push socket says the box changed, and a button of the banner was pressed. Nothing is polled,
 * and with the screen off a nudge fetches nothing: the next screen-on does.
 */
object NexusBannerSync {
    private const val TAG = "NexusBanner"

    /** A fetch this soon after the last one is the same moment seen twice (screen on, then home in front). */
    private const val SAME_MOMENT_MS = 5_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val watching = AtomicBoolean(false)
    private val fetching = AtomicBoolean(false)

    @Volatile private var fetchedAt = 0L

    /** How often the banner was fetched and how many nudges came, for the push service's dump. */
    @Volatile var fetches = 0
        private set

    @Volatile var nudges = 0
        private set

    /** Starts following the screen. Called by each service that stands behind the banner. */
    fun attach(context: Context) {
        val app = context.applicationContext
        if (watching.compareAndSet(false, true)) {
            ContextCompat.registerReceiver(
                app,
                object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) = refresh(context)
                },
                IntentFilter(Intent.ACTION_SCREEN_ON),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }
        refresh(app)
    }

    /** Asks the server for the banner, unless nothing shows it or it was just asked; [now] asks anyway. */
    fun refresh(context: Context, now: Boolean = false) {
        val app = context.applicationContext
        if (!NexusBanner.shown) return
        if (!now && System.currentTimeMillis() - fetchedAt < SAME_MOMENT_MS) return
        if (!fetching.compareAndSet(false, true)) return
        scope.launch {
            try {
                answer(app) { base, token -> NexusClient().bannerState(base, token) }
            } finally {
                fetching.set(false)
            }
        }
    }

    /** Presses the server's button [id], and shows the banner as it then is. */
    fun act(context: Context, id: String) {
        val app = context.applicationContext
        scope.launch {
            val state = answer(app) { base, token -> NexusClient().bannerAct(base, token, id) }
            val notice = state?.notice
            if (!notice.isNullOrEmpty()) {
                withContext(Dispatchers.Main) { Toast.makeText(app, notice, Toast.LENGTH_LONG).show() }
            }
        }
    }

    /**
     * The server's nudge: the box changed. With the screen on the banner is fetched now; with it off
     * the next screen-on fetches it. A job that left the card is asked about either way, since its
     * end is a notification for the person it concerns.
     */
    fun onNudge(context: Context, jobs: List<Int>) {
        val app = context.applicationContext
        nudges++
        if (app.getSystemService(PowerManager::class.java).isInteractive) refresh(app, now = true)
        if (jobs.isEmpty()) return
        scope.launch {
            val status = NexusConfig.read(app)
            if (!status.enabled) return@launch
            for (job in jobs) {
                val notice = runCatching { NexusClient().bannerJobNotice(status.serverUrl, status.authKey, job) }
                    .onFailure { Log.w(TAG, "banner_job_notice_failed ${it.javaClass.simpleName}") }
                    .getOrNull() ?: continue
                postPushNotification(app, "job:$job", notice.title, notice.text)
            }
        }
    }

    /** Runs [ask] against the server and makes its answer the banner; no answer is `Offline since`. */
    private suspend fun answer(
        app: Context,
        ask: suspend (base: String, token: String) -> BannerState,
    ): BannerState? {
        val status = NexusConfig.read(app)
        if (!status.enabled) {
            NexusBanner.box = null
            NexusBanner.show(app)
            return null
        }
        fetches++
        val result = runCatching { ask(status.serverUrl, status.authKey) }
        fetchedAt = System.currentTimeMillis()
        val state = result.getOrNull()
        NexusBanner.box = when {
            state != null -> NexusBanner.BoxLine(
                online = true,
                since = fetchedAt,
                headline = state.headline,
                lines = state.lines,
                actions = state.actions,
            )
            // Signed out: nothing to say of the box.
            result.exceptionOrNull() is NexusUnauthorizedException -> null
            // It answered, but not with a banner (a server from before there was one): it is there.
            result.exceptionOrNull()?.message?.startsWith("HTTP ") == true -> NexusBanner.BoxLine(online = true, since = fetchedAt)
            // Not answered: offline since the first time it was not.
            else -> NexusBanner.box?.takeIf { !it.online } ?: NexusBanner.BoxLine(online = false, since = fetchedAt)
        }
        if (state == null) Log.i(TAG, "banner_no_answer ${result.exceptionOrNull()?.javaClass?.simpleName}")
        NexusBanner.show(app)
        return state
    }
}

/** The server's answer to `banner.state` and `banner.act`; [notice] is why an action was refused. */
data class BannerState(
    val version: String,
    val headline: String,
    val lines: List<String>,
    val actions: List<NexusBanner.BannerAction>,
    val notice: String?,
)

/** A job's end as the server words it for this person (`banner.jobNotice`). */
data class BannerNotice(val title: String, val text: String)

fun parseBannerState(body: String): BannerState {
    val json = JSONObject(body.trim())
    val lines = json.optJSONArray("lines")
    val actions = json.optJSONArray("actions")
    return BannerState(
        version = json.optString("version"),
        headline = json.optString("headline"),
        lines = List(lines?.length() ?: 0) { lines!!.getString(it) },
        actions = List(actions?.length() ?: 0) {
            val a = actions!!.getJSONObject(it)
            NexusBanner.BannerAction(
                id = a.getString("id"),
                label = a.getString("label"),
                confirm = if (a.isNull("confirm")) null else a.getString("confirm"),
            )
        },
        notice = if (json.isNull("notice")) null else json.getString("notice"),
    )
}

/** `{"title","text"}`, or `null` when the job's end is not this person's to hear. */
fun parseBannerNotice(body: String): BannerNotice? {
    val json = runCatching { JSONObject(body.trim()) }.getOrNull() ?: return null
    val title = json.optString("title")
    if (title.isEmpty()) return null
    return BannerNotice(title, json.optString("text"))
}

/** The banner's buttons that need no question: one the server named, or asking the server again. */
class NexusBannerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_CHECK -> NexusBannerSync.refresh(context, now = true)
            ACTION_ACT -> intent.getStringExtra(EXTRA_ID)?.let { NexusBannerSync.act(context, it) }
        }
    }

    companion object {
        const val ACTION_ACT = "app.lawnchair.nexus.banner.ACT"
        const val ACTION_CHECK = "app.lawnchair.nexus.banner.CHECK"
        const val EXTRA_ID = "id"
    }
}

/**
 * The question before a button of the banner that ends something (a job that would have to be run
 * again): a dialog over whatever app is in front, since the shade cannot draw one. Yes presses the
 * button; anything else leaves the box as it is.
 */
class NexusBannerConfirmActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(NexusBannerReceiver.EXTRA_ID)
        val question = intent.getStringExtra(EXTRA_QUESTION)
        if (id == null || question == null) {
            finish()
            return
        }
        val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val theme = if (night) android.R.style.Theme_DeviceDefault_Dialog_Alert else android.R.style.Theme_DeviceDefault_Light_Dialog_Alert
        AlertDialog.Builder(this, theme)
            .setTitle("Nexus")
            .setMessage(question)
            .setPositiveButton(intent.getStringExtra(EXTRA_LABEL)?.trimEnd('…') ?: "Yes") { _, _ -> NexusBannerSync.act(this, id) }
            .setNegativeButton("No", null)
            .setOnDismissListener { finish() }
            .show()
    }

    companion object {
        const val EXTRA_LABEL = "label"
        const val EXTRA_QUESTION = "question"
    }
}
