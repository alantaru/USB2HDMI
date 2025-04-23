# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in /usr/local/lib/android-sdk/tools/proguard/proguard-android-optimize.txt
# You can edit the include path and order by changing the proguardFiles
# directive in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Add any project specific keep options here:

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# --- Regras Gerais ---
-keepattributes Signature
-keepattributes *Annotation*
-keep class kotlin.Metadata { *; }
-keepclassmembers class kotlinx.coroutines.internal.MainDispatcherFactory {
    public static final kotlinx.coroutines.MainCoroutineDispatcher INSTANCE;
}

# --- Kotlin Coroutines ---
# Based on https://github.com/Kotlin/kotlinx.coroutines/blob/master/kotlinx-coroutines-core/jvm/resources/META-INF/proguard/coroutines.pro
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepnames interface kotlinx.coroutines.Job
-keepnames interface kotlinx.coroutines.AbstractCoroutine
-keepnames class kotlinx.coroutines.CompletableJob
-keepnames class kotlinx.coroutines.flow.internal.AbstractSharedFlowKt
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
    private final <fields>;
}
-keepclassmembers class kotlinx.coroutines.flow.** {
    volatile <fields>;
    private final <fields>;
}
-keepclassmembers class kotlinx.coroutines.selects.** {
    volatile <fields>;
    private final <fields>;
}
-keepclassmembers class kotlinx.coroutines.internal.** {
    volatile <fields>;
    private final <fields>;
}
-keepclassmembers class kotlinx.coroutines.channels.** {
    volatile <fields>;
    private final <fields>;
}
-keepclassmembers class kotlinx.coroutines.sync.** {
    volatile <fields>;
    private final <fields>;
}
-keepclassmembers class kotlinx.coroutines.debug.** {
    volatile <fields>;
    private final <fields>;
}
-keepclassmembers class kotlinx.coroutines.scheduling.** {
    volatile <fields>;
    private final <fields>;
}
-keepclassmembers class kotlinx.coroutines.android.** {
    volatile <fields>;
    private final <fields>;
}
-keepclassmembers class kotlinx.coroutines.test.** {
    volatile <fields>;
    private final <fields>;
}

# --- Jetpack Lifecycle ---
-keep class androidx.lifecycle.** { *; }

# --- Jetpack DataStore ---
# DataStore uses reflection internally, keep relevant classes
-keep class androidx.datastore.preferences.protobuf.** { *; }
-keepclassmembers class androidx.datastore.preferences.protobuf.* {
 <fields>;
 <methods>;
}
-keep class androidx.datastore.core.handlers.** { *; }
-keepclassmembers class androidx.datastore.core.handlers.* {
 <fields>;
 <methods>;
}

# --- Application Specific ---
# Keep application classes used in AndroidManifest.xml
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Application
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider
-keep public class * extends android.view.View

# Keep Parcelable implementations
-keep class * implements android.os.Parcelable {
  public static final android.os.Parcelable$Creator *;
}
# Keep @Parcelize annotated classes and their creators
-keep class kotlin.* { *; } # Keep Kotlin stdlib
-keep class kotlinx.parcelize.* { *; }
-keep @kotlinx.parcelize.Parcelize class * { *; }
-keepclassmembers class * {
    @kotlinx.parcelize.Parcelize *;
}

# Keep enumeration classes
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Keep R class members
-keepclassmembers class **.R$* {
    public static <fields>;
}

# Keep native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep custom Views' constructors
-keepclasseswithmembers class * {
    public <init>(android.content.Context, android.util.AttributeSet);
}
-keepclasseswithmembers class * {
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# Keep our specific model classes if needed (usually covered by Parcelable or other rules)
# -keep class com.perfectcorp.usb2hdmi.data.model.** { *; }