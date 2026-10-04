package app.lawnchair.nexus

import io.flutter.embedding.android.FlutterFragmentActivity

/**
 * The Nexus app — nexus_mobile, embedded as a Flutter AAR (`melos app:aar` in nexus). This is the
 * APK's drawer entry; [app.lawnchair.LawnchairLauncher] is only its HOME activity.
 *
 * A FlutterFragmentActivity, not a FlutterActivity: health_sync's Health Connect permission request
 * needs a ComponentActivity. It is also the APK's only MAIN/LAUNCHER activity, so a check-in
 * notification's launch intent (route `/coach?checkIn=<id>`) lands here.
 */
class NexusAppActivity : FlutterFragmentActivity()
