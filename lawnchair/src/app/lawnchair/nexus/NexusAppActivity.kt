package app.lawnchair.nexus

import android.content.Context
import android.content.Intent
import app.lawnchair.nexus.buds.NexusBudsBridge
import app.lawnchair.nexus.buds.NexusBudsTrigger
import io.flutter.embedding.android.FlutterFragmentActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

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
 * [onNewIntent]).
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
        if (intent.getStringExtra(EXTRA_START) == START_HOME) launcherLine?.invokeMethod("home", null)
    }

    override fun onResume() {
        super.onResume()
        NexusBudsBridge.resumed = true
        // In front is where the double-tap service may be started (e.g. after a reboot), if it is wanted.
        NexusBudsTrigger.sync(this)
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
    }
}
