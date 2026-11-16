package com.lagradost.shiro.ui.tv
import com.lagradost.shiro.utils.fv

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.FocusFinder
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.findNavController
import androidx.preference.PreferenceManager
import com.jaredrummler.cyanea.Cyanea
import com.jaredrummler.cyanea.app.CyaneaAppCompatActivity
import com.jaredrummler.cyanea.prefs.CyaneaTheme
import com.lagradost.shiro.R
import com.lagradost.shiro.utils.mvvm.normalSafeApiCall
import com.lagradost.shiro.ui.MainActivity.Companion.masterViewModel
import com.lagradost.shiro.ui.MainActivity.Companion.navController
import com.lagradost.shiro.ui.MasterViewModel
import com.lagradost.shiro.ui.result.ResultFragment.Companion.isInResults
import com.lagradost.shiro.ui.tv.PlayerFragmentTv.Companion.isInPlayer
import com.lagradost.shiro.utils.AppUtils.handleIntent
import com.lagradost.shiro.utils.AppUtils.init
import com.lagradost.shiro.utils.AppUtils.settingsManager
import com.lagradost.shiro.utils.InAppUpdater.runAutoUpdate
import com.lagradost.shiro.utils.NekomoApi.Companion.initShiroApi
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import java.lang.ref.WeakReference
import kotlin.concurrent.thread

/**
 * Loads [MainFragment].
 */
class TvActivity : CyaneaAppCompatActivity() {
    companion object {
        private var _tvActivity: WeakReference<TvActivity>? = null
        var tvActivity
            get() = _tvActivity?.get()
            private set(value) {
                _tvActivity = WeakReference(value)
            }

        var isInSearch = false

        fun FragmentActivity.applyThemes() {
            // ----- Themes ----
            //AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            //theme.applyStyle(R.style.AppTheme, true)

            /* val currentTheme = when (settingsManager!!.getString("theme", "Black")) {
                 "Black" -> R.style.AppTheme
                 "Dark" -> R.style.DarkMode
                 "Light" -> R.style.LightMode
                 else -> R.style.AppTheme
             }*/

            /*if (settingsManager.getBoolean("cool_mode", false)) {
                theme.applyStyle(R.style.OverlayPrimaryColorBlue, true)
            } else if (BuildConfig.BETA && settingsManager.getBoolean("beta_theme", false)) {
                theme.applyStyle(R.style.OverlayPrimaryColorGreen, true)
            }*/
            //theme.applyStyle(R.style.AppTheme, true)
            theme.applyStyle(R.style.Theme_LeanbackCustom, true)
            /*theme.applyStyle(currentTheme, true)
            AppUtils.getTheme()?.let {
                theme.applyStyle(it, true)
            }*/
            // -----------------
        }

    }

    @SuppressLint("MissingSuperCall")
    override fun onCreate(savedInstanceState: Bundle?) {
        // Disables ssl check - Needed for development with Android TV VM

        /*
        val sslContext: SSLContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf(SSLTrustManager()), SecureRandom())
        sslContext.createSSLEngine()
        HttpsURLConnection.setDefaultHostnameVerifier { _: String, _: SSLSession ->
            true
        }
        HttpsURLConnection.setDefaultSSLSocketFactory(sslContext.socketFactory)
        */

        installSplashScreen()
        masterViewModel = masterViewModel ?: ViewModelProvider(this).get(MasterViewModel::class.java)
        settingsManager = PreferenceManager.getDefaultSharedPreferences(this)
        init()
        thread {
            normalSafeApiCall { initShiroApi() }
        }
        supportActionBar?.hide()
        if (cyanea.isDark) {
            theme.applyStyle(R.style.lightText, true)
        } else {
            theme.applyStyle(R.style.darkText, true)
        }
        val themePrefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
        if (!Cyanea.instance.isThemeModified || !themePrefs.getBoolean("nekomo_theme_v2", false)) {
            normalSafeApiCall {
                val list: List<CyaneaTheme> = CyaneaTheme.Companion.from(assets, "themes/cyanea_themes.json")
                themePrefs.edit().putBoolean("nekomo_theme_v2", true).apply()
                list[0].apply(Cyanea.instance).recreate(this)
            }
        }
        applyThemes()
        super.onCreate(savedInstanceState)
        /*if (!isTv()) {
            val intent = Intent(this, MainActivity::class.java)
            startActivity(intent)
            finish()
            return
        }*/
        // ------ Init -----
        tvActivity = this
        thread {
            normalSafeApiCall { runAutoUpdate() }
        }

        setContentView(R.layout.activity_tv)
        // The SplashTheme windowBackground (logo + glow) is only meant for launch;
        // swap it for the plain theme background once content is up.
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Cyanea.instance.backgroundColor))
        normalSafeApiCall { com.lagradost.shiro.ui.welcome.WelcomeGate.attachIfNeeded(this) }
        normalSafeApiCall { com.lagradost.shiro.ui.welcome.AppLockGate.attachIfNeeded(this) }
        navController = fv<androidx.fragment.app.FragmentContainerView>(R.id.home_root_tv).findNavController()
        handleIntent(intent)
    }


    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return if (keyCode == KeyEvent.KEYCODE_DPAD_UP && !isInPlayer && !isInResults) {
            try {
                val nextFocused =
                    FocusFinder.getInstance()
                        .findNextFocus(fv<androidx.fragment.app.FragmentContainerView>(R.id.home_root_tv) as? ViewGroup?, currentFocus, View.FOCUS_UP)
                nextFocused?.requestFocus()
                    ?: fv<android.widget.ImageView>(R.id.search_icon)?.requestFocus()
                    ?: false
            } catch (e: Exception) {
                return false
            }
        } else {
            //println("Not")
            super.onKeyDown(keyCode, event)
        }
    }

    @SuppressLint("MissingSuperCall")
    override fun onResume() {
        super.onResume()
        // This is needed to avoid NPE crash due to missing context
        init()
    }


    // AUTH FOR LOGIN
    override fun onNewIntent(intent: Intent?) {
        handleIntent(intent)
        super.onNewIntent(intent)
    }

}