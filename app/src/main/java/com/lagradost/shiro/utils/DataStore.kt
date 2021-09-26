import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule

const val PREFERENCES_NAME: String = "rebuild_preference"
const val VIEW_POS_KEY: String = "view_pos" // VIDEO POSITION
const val VIEW_DUR_KEY: String = "view_dur" // VIDEO DURATION
const val VIEW_LST_KEY: String = "view_lst_watched" // LAST WATCHED, ONE PER TITLE ID
const val BOOKMARK_KEY: String = "bookmark" // BOOKMARK/FAVORITE BookmarkedTitle
const val VIEWSTATE_KEY: String = "viewstate" // BOOKMARK/FAVORITE BookmarkedTitle
const val DOWNLOAD_CHILD_KEY: String = "dload_child" // DownloadFileMetadata
const val DOWNLOAD_PARENT_KEY: String = "dload_parent" // DownloadParentFileMetadata
const val PLAYBACK_SPEED_KEY: String = "playback_speed" // Last used playback speed
const val RESIZE_MODE_KEY: String = "resize_mode" // Last used resize mode

const val LEGACY_BOOKMARKS: String = "legacy_bookmarks_1" // for converting old bookmark system
const val LEGACY_SUBS: String = "legacy_subbed" // for converting old subbed system

const val LEGACY_RECENTS: String = "legacy_recents" // for converting old bookmark system

const val SUBSCRIPTIONS_KEY: String = "subscriptions"
const val SUBSCRIPTIONS_BOOKMARK_KEY: String = "subscriptions_bookmarked"

const val ANILIST_UNIXTIME_KEY: String = "anilist_unixtime" // When token expires
const val MAL_UNIXTIME_KEY: String = "mal_unixtime" // When token expires

const val ANILIST_CACHED_LIST: String = "anilist_cached_list"
const val ANILIST_SHOULD_UPDATE_LIST: String = "anilist_should_update_list"

const val ANILIST_TOKEN_KEY: String = "anilist_token" // anilist token for api
const val MAL_TOKEN_KEY: String = "mal_token" // anilist token for api
const val MAL_REFRESH_TOKEN_KEY: String = "mal_refresh_token" // refresh token

const val ANILIST_USER_KEY: String = "anilist_user" // user data like profile
const val MAL_USER_KEY: String = "mal_user" // user data like profile
const val MAL_CACHED_LIST: String = "mal_cached_list"
const val MAL_SHOULD_UPDATE_LIST: String = "mal_should_update_list"

const val HAS_DISMISSED_SEARCH_INFO: String = "HAS_DISMISSED_SEARCH_INFO" // For the search tutorial

const val LIBRARY_IS_MAL: String = "library_is_mal"

const val RESULTS_PAGE_OVERRIDE_MAL: String = "override_mal_id"
const val RESULTS_PAGE_OVERRIDE_ANILIST: String = "override_anilist_id"

const val LIBRARY_PAGE_MAL_OVERRIDE_SLUG: String = "override_slug_mal"


object DataStore {
    val mapper: JsonMapper = JsonMapper.builder().addModule(KotlinModule())
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false).build()

    private fun getPreferences(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    }

    fun Context.getSharedPrefs(): SharedPreferences {
        return getPreferences(this)
    }

    fun getFolderName(folder: String, path: String): String {
        return "${folder}/${path}"
    }

    fun Context.getKeys(folder: String): List<String> {
        return this.getSharedPrefs().all.keys.filter { it.startsWith(folder) }
    }

    fun Context.removeKey(folder: String, path: String) {
        removeKey(getFolderName(folder, path))
    }

    fun Context.containsKey(folder: String, path: String): Boolean {
        return containsKey(getFolderName(folder, path))
    }

    fun Context.containsKey(path: String): Boolean {
        val prefs = getSharedPrefs()
        return prefs.contains(path)
    }

    fun Context.removeKey(path: String) {
        val prefs = getSharedPrefs()
        if (prefs.contains(path)) {
            val editor: SharedPreferences.Editor = prefs.edit()
            editor.remove(path)
            editor.apply()
        }
    }

    fun Context.removeKeys(folder: String): Int {
        val keys = getKeys(folder)
        keys.forEach { value ->
            removeKey(value)
        }
        return keys.size
    }

    fun <T> Context.setKey(path: String, value: T) {
        val editor: SharedPreferences.Editor = getSharedPrefs().edit()
        editor.putString(path, mapper.writeValueAsString(value))
        editor.apply()
    }

    fun <T> Context.setKey(folder: String, path: String, value: T) {
        setKey(getFolderName(folder, path), value)
    }

    inline fun <reified T : Any> String.toKotlinObject(): T {
        return mapper.readValue(this, T::class.java)
    }

    fun Context.getDefaultSharedPrefs(): SharedPreferences {
        return PreferenceManager.getDefaultSharedPreferences(this)
    }

    // GET KEY GIVEN PATH AND DEFAULT VALUE, NULL IF ERROR
    inline fun <reified T : Any> Context.getKey(path: String, defVal: T?): T? {
        try {
            val json: String = getSharedPrefs().getString(path, null) ?: return defVal
            return json.toKotlinObject()
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    // Needed for restore
    fun <T> Context.setKeyRaw(path: String, value: T, isEditingAppSettings: Boolean = false) {
        val editor: SharedPreferences.Editor =
            if (isEditingAppSettings) getDefaultSharedPrefs().edit() else getSharedPrefs().edit()
        when (value) {
            is Boolean -> editor.putBoolean(path, value)
            is Int -> editor.putInt(path, value)
            is String -> editor.putString(path, value)
            is Float -> editor.putFloat(path, value)
            is Long -> editor.putLong(path, value)
            (value as? Set<String> != null) -> editor.putStringSet(path, value as Set<String>)
        }
        editor.apply()
    }


    inline fun <reified T : Any> Context.getKey(path: String): T? {
        return getKey(path, null)
    }

    inline fun <reified T : Any> Context.getKey(folder: String, path: String): T? {
        return getKey(getFolderName(folder, path), null)
    }

    inline fun <reified T : Any> Context.getKey(folder: String, path: String, defVal: T?): T? {
        return getKey(getFolderName(folder, path), defVal) ?: defVal
    }
}