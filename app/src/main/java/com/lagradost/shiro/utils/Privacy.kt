package com.lagradost.shiro.utils

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import androidx.preference.PreferenceManager
import com.lagradost.shiro.AcraApplication

/** Privacy switches from Settings -> History and privacy. */
object Privacy {
    private fun prefs(context: Context?) =
        (context ?: AcraApplication.context)?.let { PreferenceManager.getDefaultSharedPreferences(it) }

    /** While on: no watch progress, search history or AniList/MAL progress sync is saved. */
    fun incognito(context: Context? = null) = prefs(context)?.getBoolean("incognito_mode", false) ?: false

    /** Require the device screen lock before 18+ content can be switched on. */
    fun adultLock(context: Context? = null) = prefs(context)?.getBoolean("adult_lock", false) ?: false

    fun isDeviceSecure(context: Context): Boolean =
        (context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)?.isDeviceSecure ?: false

    /** Intent for the system PIN/pattern/fingerprint prompt, or null when no lock is set. */
    fun confirmLockIntent(context: Context): Intent? =
        (context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)
            ?.createConfirmDeviceCredentialIntent("Confirm it's you", "Unlock to show 18+ content")
}

/** The last few searches, newest first. Nothing is stored while incognito is on. */
object SearchHistory {
    private const val KEY = "search_history_v1"
    private const val MAX = 15

    private fun prefs(context: Context) = PreferenceManager.getDefaultSharedPreferences(context)

    fun all(context: Context): List<String> =
        prefs(context).getString(KEY, "")?.split("\n")?.filter { it.isNotBlank() } ?: emptyList()

    fun add(context: Context, query: String) {
        val q = query.trim().replace("\n", " ")
        if (q.length < 2 || Privacy.incognito(context)) return
        val list = listOf(q) + all(context).filter { !it.equals(q, ignoreCase = true) }
        prefs(context).edit().putString(KEY, list.take(MAX).joinToString("\n")).apply()
    }

    fun remove(context: Context, query: String) {
        prefs(context).edit().putString(KEY, all(context).filter { it != query }.joinToString("\n")).apply()
    }

    fun clear(context: Context) = prefs(context).edit().remove(KEY).apply()
}

/** "Reduce motion" (Settings -> General) or the system's animation-off setting: skip decorative animations. */
object Motion {
    fun reduced(context: Context? = null): Boolean {
        val c = context ?: AcraApplication.context ?: return false
        if (PreferenceManager.getDefaultSharedPreferences(c).getBoolean("reduce_motion", false)) return true
        return try {
            android.provider.Settings.Global.getFloat(c.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        } catch (e: Exception) {
            false
        }
    }
}
