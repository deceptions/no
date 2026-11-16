package com.lagradost.shiro

import com.lagradost.shiro.utils.AppIdentity
import ANILIST_SHOULD_UPDATE_LIST
import DataStore.setKey
import MAL_SHOULD_UPDATE_LIST
import android.app.Application
import android.content.Context
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import com.google.auto.service.AutoService
import com.jaredrummler.cyanea.Cyanea
import com.lagradost.shiro.utils.ErrorLogger
import com.lagradost.shiro.utils.cs3.CsPluginManager
import com.lagradost.shiro.utils.mvvm.logError
import com.lagradost.shiro.utils.mvvm.normalSafeApiCall
import org.acra.ReportField
import org.acra.config.CoreConfiguration
import org.acra.config.toast
import org.acra.data.CrashReportData
import org.acra.data.StringFormat
import org.acra.ktx.initAcra
import org.acra.sender.ReportSender
import org.acra.sender.ReportSenderFactory
import java.lang.ref.WeakReference
import kotlin.concurrent.thread

class CustomReportSender : ReportSender {
    // Sends all your crashes to google forms
    override fun send(context: Context, errorContent: CrashReportData) {
        try {
            println("Report sent")
            val url =
                "https://docs.google.com/forms/u/0/d/e/1FAIpQLSf8U6zVn4YPGhbCQXUBNH4k5wlYC2KmmGuUZz4O6TL2o62cAw/formResponse"
            val data = mapOf(
                "entry.1083318133" to errorContent.toJSON()
            )
            thread {
                khttp.post(url, data = data)
            }
        } catch (e: Exception) {
            logError(e)
        }
    }
}

@AutoService(ReportSenderFactory::class)
class CustomSenderFactory : ReportSenderFactory {
    override fun create(context: Context, config: CoreConfiguration): ReportSender {
        return CustomReportSender()
    }

    override fun enabled(config: CoreConfiguration): Boolean {
        // Disabled: the endpoint posts crash reports to the original Shiro
        // developer's Google Form. Nekomo has no replacement sink yet.
        return false
    }
}

class AcraApplication : Application() {
    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        context = base

        // Persist crashes/errors to app storage so they're visible from Settings even on a
        // release build with no debugger attached (see utils/ErrorLogger.kt).
        normalSafeApiCall { base?.let { ErrorLogger.init(it) } }

        normalSafeApiCall {
            base?.setKey(MAL_SHOULD_UPDATE_LIST, true)
            base?.setKey(ANILIST_SHOULD_UPDATE_LIST, true)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            initAcra {
                //core configuration:
                buildConfigClass = BuildConfig::class.java
                reportFormat = StringFormat.JSON
                reportContent = listOf(
                    ReportField.BUILD_CONFIG, ReportField.USER_CRASH_DATE,
                    ReportField.ANDROID_VERSION, ReportField.PHONE_MODEL,
                    ReportField.STACK_TRACE, ReportField.LOGCAT
                )

                //each plugin you chose above can be configured in a block like this:
                toast {
                    text = getString(R.string.acra_report_toast)
                    //opening this block automatically enables the plugin.
                }

            }
        }

        normalSafeApiCall { ErrorLogger.installHandler() }
    }

    override fun onCreate() {
        super.onCreate()
        context = context ?: baseContext
        normalSafeApiCall { Cyanea.init(this, resources) }
        // CloudStream extension subsystem: injects our Context into the vendored
        // cloudstream3 classes and loads installed .cs3 plugins on a bg thread.
        // Must run here (not attachBaseContext) because applicationContext is null until now.
        // Screenshots, screen recording and recents thumbnails are blocked in every screen of the app
        registerActivityLifecycleCallbacks(object : android.app.Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) {
                com.lagradost.shiro.utils.CapturePolicy.apply(activity)
            }
            override fun onActivityStarted(activity: android.app.Activity) {}
            override fun onActivityResumed(activity: android.app.Activity) { com.lagradost.shiro.utils.CapturePolicy.apply(activity); AppIdentity.applyTaskDescription(activity) }
            override fun onActivityPaused(activity: android.app.Activity) {}
            override fun onActivityStopped(activity: android.app.Activity) {}
            override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) {}
            override fun onActivityDestroyed(activity: android.app.Activity) {}
        })
        normalSafeApiCall { CsPluginManager.init(this) }
        // Optional automatic updates for installed script/plugin sources (never APK extensions)
        // New-episode notifications for subscribed shows (no-ops while the setting is off)
        normalSafeApiCall { com.lagradost.shiro.utils.NewEpisodeWorker.schedule(this) }
        normalSafeApiCall {
            com.lagradost.shiro.utils.repo.ExtensionUpdater.schedule(this)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                kotlin.concurrent.thread { com.lagradost.shiro.utils.repo.ExtensionUpdater.runAuto(this) }
            }, 30_000)
        }
        AppCompatDelegate.setCompatVectorFromResourcesEnabled(true)
//        normalSafeApiCall {
//            // Cleaning up update folder
//            File("$filesDir/Download/apk/").deleteOnExit()
//        }
    }

    companion object {
        private var _context: WeakReference<Context>? = null
        var context
            get() = _context?.get()
            private set(value) {
                _context = WeakReference(value)
            }

        fun getAppContext(): Context? {
            return context
        }

    }

}