package com.lagradost.shiro.utils.integrations

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager

/**
 * User-entered credentials/options for the third-party services (SIMKL, OpenSubtitles,
 * SubDL, Anime Skip). Stored in the app's default SharedPreferences (app-private).
 * Nothing is bundled in the APK: every service needs the user's own key/app id.
 */
class IntegrationPrefs(private val prefs: SharedPreferences) {
    constructor(context: Context) : this(PreferenceManager.getDefaultSharedPreferences(context))

    private fun str(key: String) = prefs.getString(key, "")?.trim().orEmpty()
    private fun put(key: String, v: String) = prefs.edit().putString(key, v.trim()).apply()

    var simklClientId: String
        get() = str("simkl_client_id"); set(v) = put("simkl_client_id", v)

    var openSubsApiKey: String
        get() = str("opensubs_api_key"); set(v) = put("opensubs_api_key", v)
    var openSubsUser: String
        get() = str("opensubs_username"); set(v) = put("opensubs_username", v)
    var openSubsPassword: String
        get() = prefs.getString("opensubs_password", "").orEmpty(); set(v) = prefs.edit().putString("opensubs_password", v).apply()

    var subDlApiKey: String
        get() = str("subdl_api_key"); set(v) = put("subdl_api_key", v)

    var animeSkipClientId: String
        get() = str("animeskip_client_id"); set(v) = put("animeskip_client_id", v)

    /** ISO 639-1 language code, e.g. "en". */
    var subtitleLanguage: String
        get() = str("subtitle_language").ifEmpty { "en" }; set(v) = put("subtitle_language", v.lowercase())

    var autoFetchSubtitles: Boolean
        get() = prefs.getBoolean("subtitle_auto_fetch", true); set(v) = prefs.edit().putBoolean("subtitle_auto_fetch", v).apply()

    var autoSkip: Boolean
        get() = prefs.getBoolean("skip_auto", false); set(v) = prefs.edit().putBoolean("skip_auto", v).apply()
    var skipIntro: Boolean
        get() = prefs.getBoolean("skip_intro", true); set(v) = prefs.edit().putBoolean("skip_intro", v).apply()
    var skipOutro: Boolean
        get() = prefs.getBoolean("skip_outro", true); set(v) = prefs.edit().putBoolean("skip_outro", v).apply()
    var skipRecap: Boolean
        get() = prefs.getBoolean("skip_recap", false); set(v) = prefs.edit().putBoolean("skip_recap", v).apply()

    val hasSubtitleProvider get() = (openSubsApiKey.isNotEmpty()) || subDlApiKey.isNotEmpty()
}
