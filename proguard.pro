# The rules from AOSP are located in proguard.flags file, we can just maintain Lawnchair related rules here.

# Optimization options.
-allowaccessmodification
-dontusemixedcaseclassnames
-allowaccessmodification
-keepattributes InnerClasses, *Annotation*, Signature, SourceFile, LineNumberTable

# Remove some Kotlin overhead
-processkotlinnullchecks remove

# Common rules.
-keep class android.window.** { *; }
-keep class android.view.** { *; }

-keepclassmembers class * implements android.os.Parcelable {
  public static final ** CREATOR;
}

# Lawnchair specific rules.
-keep,allowshrinking,allowoptimization class app.lawnchair.LawnchairProto$* { *; }
-keep,allowshrinking,allowoptimization class app.lawnchair.LawnchairApp { *; }
-keep,allowshrinking,allowoptimization class app.lawnchair.LawnchairLauncher { *; }
-keep,allowshrinking,allowoptimization class app.lawnchair.compatlib.** { *; }

-keep,allowshrinking,allowoptimization class com.google.protobuf.Timestamp { *; }
-keep class * extends com.google.protobuf.GeneratedMessageLite { *; }

# The embedded Nexus app keeps its saved copy of the server in a SQLCipher database
# (sqflite_sqlcipher); shrinking must keep the classes the plugin reaches by name.
-keep class net.sqlcipher.** { *; }

# The embedded Nexus app's Flutter engine and plugins, kept as a Flutter app's own build keeps
# them: the engine finds its plugin registrant by name and registers plugins by class, so a plugin
# R8 merged into another's class is taken for that one and never registered (the release app then
# has no permissions, no orb view, and stops on its loading screen).
-keep class io.flutter.** { *; }
-keep class * implements io.flutter.embedding.engine.plugins.FlutterPlugin { *; }
-dontwarn io.flutter.embedding.**

# The embedded Nexus app's plugins that reach their Android side over JNI by class and member
# name (jni, jni_flutter, shared_preferences_android's native interop): renamed, the release
# app cannot open its settings and stops on its loading screen.
-keep class com.github.dart_lang.jni.** { *; }
-keep class com.github.dart_lang.jni_flutter.** { *; }
-keep class io.flutter.plugins.sharedpreferences.** { *; }
-keep class kotlin.Unit { *; }
-keep class kotlin.Result { *; }
-keep class kotlin.Result$Failure { *; }
-keep class kotlin.coroutines.Continuation { *; }
-keep class kotlin.coroutines.intrinsics.CoroutineSingletons { *; }
-keep class kotlin.coroutines.intrinsics.IntrinsicsKt { *; }

# We intentionally remove it to replace Smartspacer's widget popup with our own Launcher3 popup
-dontwarn com.skydoves.balloon.*

# This shouldn't concern us much
-dontwarn androidx.window.extensions.**
-dontwarn androidx.window.sidecar.**
