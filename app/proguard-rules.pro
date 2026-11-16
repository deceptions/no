# Add project specific ProGuard rules here.

# Annotation-processor / compile-time-only classes referenced by jars
-dontwarn javax.lang.model.**
-dontwarn com.google.auto.**
-dontwarn org.jspecify.**

# WebView JS bridge — the site crypto bridge must keep its @JavascriptInterface method
-keepclassmembers class com.lagradost.shiro.utils.Mkissa$Bridge {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes JavascriptInterface
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   var *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# Proguard configuration for Jackson 2.x
# Jackson
-keep public class com.lagradost.shiro.utils.AniListApi**
-keep public enum com.lagradost.shiro.AniListApi$AniListStatusType** {
    **[] $VALUES;
    public *;
}
#-keep class com.lagradost.shiro.ui.result.ResultFragment.** { *; }

-keepattributes *Annotation*

-keep class kotlin.$** { *; }
-keep class org.jetbrains.$** { *; }

-keep class * {
    enum **;
}

-keep        class android.support.v13** { *; }
-keep        class android.support.v7** { *; }
-keep        class android.support.v4** { *; }
-keep class androidx.mediarouter.app.MediaRouteActionProvider { public <methods>; }
-keep public class androidx.mediarouter.app.MediaRouteActionProvider { public <methods>; }
-keepclassmembernames class * { *;}
# Jackson
-keep @com.fasterxml.jackson.annotation.JsonIgnoreProperties class * { *; }
-keep @com.fasterxml.jackson.annotation.JsonCreator class * { *; }
-keep @com.fasterxml.jackson.annotation.JsonValue class * { *; }
-keep class com.fasterxml** { *; }
-keep class org.codehaus** { *; }
-keepnames class com.fasterxml.jackson** { *; }
-keepclassmembers public final enum com.fasterxml.jackson.annotation.JsonAutoDetect$Visibility {
    public static final com.fasterxml.jackson.annotation.JsonAutoDetect$Visibility *;
}

# Proguard configuration for Jackson 2.x
-keep class com.fasterxml.jackson.databind.ObjectMapper {
    public <methods>;
    protected <methods>;
}
-keep class com.fasterxml.jackson.databind.ObjectWriter {
    public ** writeValueAsString(**);
}
-keepnames class com.fasterxml.jackson** { *; }
-dontwarn com.fasterxml.jackson.databind.**

# Proguard configuration for Jackson 2.x
-keepclassmembers class * {
     @com.fasterxml.jackson.annotation.* *;
}

-keep class com.lagradost.shiro.utils.CastOptionsProvider{ *; }
-keep class androidx.mediarouter.app.MediaRouteActionProvider { *; }


# CloudStream extension API — .cs3 plugins reference these classes dynamically via
# PathClassLoader, so nothing inside may be renamed or stripped in release builds.
-keep class com.lagradost.cloudstream3.** { *; }
-keep class com.lagradost.cloudstream4.** { *; }
-keep class com.lagradost.api.** { *; }
-keep class com.lagradost.safefile.** { *; }
-keep class com.lagradost.nicehttp.** { *; }
-dontwarn com.lagradost.**
-dontwarn org.mozilla.javascript.**
-dontwarn org.mozilla.**
-dontwarn com.github.teamnewpipe.**
-dontwarn org.schabi.**
-dontwarn com.fleeksoft.**
-dontwarn dev.whyoleg.**
-dontwarn io.ktor.**
-dontwarn coil3.**
-dontwarn androidx.media3.**
-dontwarn app.cash.**

# keep this class so that logging will show 'ACRA' and not a obfuscated name like 'a'.
# Note: if you are removing log messages elsewhere in this file then this isn't necessary
-keep class org.acra.ACRA {
    *;
}

# keep this around for some enums that ACRA needs
-keep class org.acra.ReportingInteractionMode {
    *;
}

-keepnames class org.acra.sender.HttpSender$** {
    *;
}

-keepnames class org.acra.ReportField {
    *;
}

# keep this otherwise it is removed by ProGuard
-keep public class org.acra.ErrorReporter {
    public void addCustomData(java.lang.String,java.lang.String);
    public void putCustomData(java.lang.String,java.lang.String);
    public void removeCustomData(java.lang.String);
}

# keep this otherwise it is removed by ProGuard
-keep public class org.acra.ErrorReporter {
    public void handleSilentException(java.lang.Throwable);
}

# The app relies heavily on Jackson/reflection over its own model classes and on event/function
# references; R8 shrinking of these broke the home feed in release builds (spinner forever).
-keep class com.lagradost.shiro.** { *; }
-keep class khttp.** { *; }
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,RuntimeVisibleAnnotations,KotlinMetadata
-keep class kotlin.Metadata { *; }
-keepclassmembers class kotlin.collections.EmptyMap { *; }
-keepclassmembers class kotlin.collections.EmptyList { *; }
-keepclassmembers class kotlin.collections.EmptySet { *; }

# Synthetics.fv() creates detached placeholder views reflectively when an id is missing from a layout.
-keepclassmembers class * extends android.view.View {
    public <init>(android.content.Context);
}

# Zangetsu provider scripts run on Rhino, which is reflection-heavy.
-keep class org.mozilla.javascript.** { *; }
-dontwarn org.mozilla.javascript.**
-dontwarn javax.script.**
-dontwarn org.mozilla.classfile.**

# Aniyomi/Mihon extension APKs are loaded at runtime and link against the vendored extension API and these
# libraries by name, so R8 must not rename/remove anything they could call.
-keep class eu.kanade.** { *; }
-keep interface eu.kanade.** { *; }
-keep class tachiyomi.** { *; }
-keep class uy.kohesive.injekt.** { *; }
-keep class rx.** { *; }
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-keep class okio.** { *; }
-keep class org.jsoup.** { *; }
-keep class kotlinx.serialization.** { *; }
-keep class kotlinx.coroutines.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn rx.**
-keep class kotlin.** { *; }
-keep class kotlin.comparisons.** { *; }
-dontwarn kotlin.**
-keep class androidx.preference.** { *; }

# In-app trailers: NewPipe extractor (reflection + JS signature handling)
-keep class org.schabi.newpipe.extractor.** { *; }
-keep class org.mozilla.javascript.** { *; }
-dontwarn org.schabi.newpipe.**
-dontwarn org.mozilla.javascript.**
