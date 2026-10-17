package app.lawnchair.nexus

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
 */
class NexusAppActivity : FlutterFragmentActivity() {
    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        // The line the earbud service passes taps on by, and the app says when it can take them.
        val line = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "no.nordli.nexus/buds")
        line.setMethodCallHandler { call, result ->
            when (call.method) {
                "home" -> NexusBudsBridge.homeReady = call.arguments as? Boolean ?: false
                "busy" -> NexusBudsBridge.busy = call.arguments as? Boolean ?: false
                else -> {
                    result.notImplemented()
                    return@setMethodCallHandler
                }
            }
            result.success(null)
        }
        NexusBudsBridge.channel = line
    }

    override fun cleanUpFlutterEngine(flutterEngine: FlutterEngine) {
        NexusBudsBridge.reset()
        super.cleanUpFlutterEngine(flutterEngine)
    }

    override fun onResume() {
        super.onResume()
        NexusBudsBridge.resumed = true
        // In front is where the double-tap service may be started (e.g. after a reboot), if it is wanted.
        NexusBudsTrigger.sync(this)
    }

    override fun onPause() {
        NexusBudsBridge.resumed = false
        super.onPause()
    }
}
