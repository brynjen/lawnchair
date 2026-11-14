package app.lawnchair.nexus

import android.content.Context
import android.content.Intent
import app.lawnchair.nexus.buds.NexusBudsBridge
import app.lawnchair.nexus.buds.NexusBudsTrigger
import io.flutter.embedding.android.FlutterFragmentActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import java.net.URLEncoder

/**
 * The Nexus app — nexus_mobile, embedded as a Flutter AAR (`melos app:aar` in nexus). This is the
 * APK's drawer entry; [app.lawnchair.LawnchairLauncher] is only its HOME activity.
 *
 * A FlutterFragmentActivity, not a FlutterActivity: health_sync's Health Connect permission request
 * needs a ComponentActivity. It is also the APK's only MAIN/LAUNCHER activity, so a check-in
 * notification's launch intent (route `/coach?checkIn=<id>`) lands here.
 *
 * The launcher's search bar opens it at Home ([homeIntent]): a cold start begins there anyway, and an
 * app that is already running, on the map or in settings, is told to go there (the singleTop
 * [onNewIntent]). A chat found in the launcher's search field opens at that chat ([chatIntent]): a
 * cold start begins at its route (Flutter's `route` extra), and a running app is told to open it.
 */
class NexusAppActivity : FlutterFragmentActivity() {
    private var launcherLine: MethodChannel? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        // The line the earbud service passes taps on by, and the app says when it can take them.
        val line = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "no.nordli.nexus/buds")
        line.setMethodCallHandler { call, result ->
            when (call.method) {
                "home" -> NexusBudsBridge.homeReady = call.arguments as? Boolean ?: false
                "busy" -> NexusBudsBridge.busy = call.arguments as? Boolean ?: false
                "conversation" -> (call.arguments as? Int)?.let { id -> NexusBudsBridge.onConversation?.invoke(id) }
                else -> {
                    result.notImplemented()
                    return@setMethodCallHandler
                }
            }
            result.success(null)
        }
        NexusBudsBridge.channel = line
        launcherLine = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "no.nordli.nexus/launcher")
    }

    override fun cleanUpFlutterEngine(flutterEngine: FlutterEngine) {
        NexusBudsBridge.reset()
        launcherLine = null
        super.cleanUpFlutterEngine(flutterEngine)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Before the app has put its listener up there is nothing to take it to: it starts at Home.
        when (intent.getStringExtra(EXTRA_START)) {
            START_HOME -> launcherLine?.invokeMethod("home", null)
            START_ROUTE -> intent.getStringExtra(EXTRA_ROUTE)?.let { launcherLine?.invokeMethod("open", it) }
            START_ALONE -> intent.getStringExtra(EXTRA_ROUTE)?.let { launcherLine?.invokeMethod("openAlone", it) }
        }
    }

    override fun onResume() {
        super.onResume()
        NexusBudsBridge.resumed = true
        // In front is where the double-tap service may be started (e.g. after a reboot), if it is wanted.
        NexusBudsTrigger.sync(this)
        // A person who has just signed in is listened for from now, not from the next restart.
        app.lawnchair.nexus.push.NexusPushTrigger.sync(this)
        NexusBannerSync.refresh(this)
    }

    override fun onPause() {
        NexusBudsBridge.resumed = false
        NexusBudsBridge.onLeft?.invoke()
        super.onPause()
    }

    companion object {
        /** An extra on the launch intent saying where in the app to be. */
        const val EXTRA_START = "nexus.start"
        const val START_HOME = "home"

        /** Opens the app at its Home screen, from wherever in it the person was. */
        fun homeIntent(context: Context): Intent = Intent(context, NexusAppActivity::class.java)
            .putExtra(EXTRA_START, START_HOME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

        const val START_ROUTE = "route"

        /** Flutter's own extra: the route a cold start begins at (FlutterActivityLaunchConfigs). */
        private const val EXTRA_ROUTE = "route"

        const val START_ALONE = "alone"

        /**
         * Opens the app at [route] of nexus_mobile's router on its own, for a notification's tap: the
         * route carries `alone=1`, and back from it leaves the app again.
         */
        fun aloneIntent(context: Context, route: String): Intent =
            Intent(context, NexusAppActivity::class.java)
                .putExtra(EXTRA_START, START_ALONE)
                .putExtra(EXTRA_ROUTE, route)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

        /**
         * Opens the app at chat [conversationId] (over its Home, so back lands there), headed [title];
         * [agentId] is the agent of a direct session.
         */
        fun chatIntent(context: Context, conversationId: Int, title: String, agentId: String?): Intent =
            Intent(context, NexusAppActivity::class.java)
                .putExtra(EXTRA_START, START_ROUTE)
                .putExtra(EXTRA_ROUTE, chatRoute(conversationId, title, agentId))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}

/** The app's route to a chat: `/chat?c=<id>&title=<title>[&agent=<id>]` (nexus_mobile's app.dart). */
fun chatRoute(conversationId: Int, title: String, agentId: String?): String =
    "/chat?c=$conversationId&title=${URLEncoder.encode(title, Charsets.UTF_8)}" +
        (agentId?.let { "&agent=${URLEncoder.encode(it, Charsets.UTF_8)}" } ?: "")
