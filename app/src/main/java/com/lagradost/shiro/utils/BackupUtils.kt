package com.lagradost.shiro.utils

import ANILIST_CACHED_LIST
import ANILIST_SHOULD_UPDATE_LIST
import ANILIST_TOKEN_KEY
import ANILIST_UNIXTIME_KEY
import DataStore.getDefaultSharedPrefs
import DataStore.getSharedPrefs
import DataStore.mapper
import DataStore.setKeyRaw
import MAL_CACHED_LIST
import MAL_SHOULD_UPDATE_LIST
import MAL_UNIXTIME_KEY
import MAL_USER_KEY
import android.content.Context
import android.os.Environment
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.shiro.ui.settings.SettingsFragment.Companion.restoreFileSelector
import com.lagradost.shiro.utils.AppUtils.checkWrite
import com.lagradost.shiro.utils.AppUtils.requestRW
import java.io.File
import java.lang.System.currentTimeMillis
import java.text.SimpleDateFormat
import java.util.*

object BackupUtils {
    private val blackList = listOf(
        "cool_mode",
        "beta_theme",
        "purple_theme",
        "subscribe_to_updates",
        "subscribe_to_announcements",
        "subscriptions_bookmarked",
        "subscriptions",
        "legacy_bookmarks",
        "legacy_bookmarks_1",
        "pink_theme",
        ANILIST_ACCOUNT_ID,
        ANILIST_CLIENT_ID,
        ANILIST_TOKEN_KEY,
        ANILIST_CACHED_LIST,
        ANILIST_SHOULD_UPDATE_LIST,
        ANILIST_UNIXTIME_KEY,
        MAL_ACCOUNT_ID,
        MAL_CLIENT_ID,
        MAL_SHOULD_UPDATE_LIST,
        MAL_CACHED_LIST,
        MAL_UNIXTIME_KEY,
        MAL_USER_KEY
    )
    private val filterRegex = Regex("""^(${blackList.joinToString(separator = "|")})""")


    // Kinda hack, but I couldn't think of a better way
    data class BackupVars(
        @JsonProperty("_Bool") val _Bool: Map<String, Boolean>?,
        @JsonProperty("_Int") val _Int: Map<String, Int>?,
        @JsonProperty("_String") val _String: Map<String, String>?,
        @JsonProperty("_Float") val _Float: Map<String, Float>?,
        @JsonProperty("_Long") val _Long: Map<String, Long>?,
        @JsonProperty("_StringSet") val _StringSet: Map<String, Set<String>?>?,
    )

    data class BackupFile(
        @JsonProperty("datastore") val datastore: BackupVars,
        @JsonProperty("settings") val settings: BackupVars
    )

    fun FragmentActivity.backup() {
        try {
            if (checkWrite()) {
                val downloadDir =
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                        .toString() + "/Shiro/"
                val date = SimpleDateFormat("yyyy_MM_dd_HH_mm").format(Date(currentTimeMillis()))
                val allDataFile = File(downloadDir + "Shiro_Backup_${date}.xml")
                allDataFile.parentFile?.mkdirs()

                val allData = getSharedPrefs().all
                val allSettings = getDefaultSharedPrefs().all

                val allDataSorted = BackupVars(
                    allData.filter { it.value is Boolean && !isBlacklisted(it.key) } as? Map<String, Boolean>,
                    allData.filter { it.value is Int && !isBlacklisted(it.key) } as? Map<String, Int>,
                    allData.filter { it.value is String && !isBlacklisted(it.key) } as? Map<String, String>,
                    allData.filter { it.value is Float && !isBlacklisted(it.key) } as? Map<String, Float>,
                    allData.filter { it.value is Long && !isBlacklisted(it.key) } as? Map<String, Long>,
                    allData.filter { it.value as? Set<String> != null && !isBlacklisted(it.key) } as? Map<String, Set<String>>
                )

                val allSettingsSorted = BackupVars(
                    allSettings.filter { it.value is Boolean && !isBlacklisted(it.key) } as? Map<String, Boolean>,
                    allSettings.filter { it.value is Int && !isBlacklisted(it.key) } as? Map<String, Int>,
                    allSettings.filter { it.value is String && !isBlacklisted(it.key) } as? Map<String, String>,
                    allSettings.filter { it.value is Float && !isBlacklisted(it.key) } as? Map<String, Float>,
                    allSettings.filter { it.value is Long && !isBlacklisted(it.key) } as? Map<String, Long>,
                    allSettings.filter { it.value as? Set<String> != null && !isBlacklisted(it.key) } as? Map<String, Set<String>>
                )

                val backupFile = BackupFile(
                    allDataSorted,
                    allSettingsSorted
                )

                allDataFile.writeText(mapper.writeValueAsString(backupFile))

                /*val customPreferences = File(filesDir.parent + "/shared_prefs/${packageName}_preferences.xml")
                val customPreferencesNew = File(downloadDir + "Shiro_Backup_Data_${date}.xml")
                val settingsPreferences = File(filesDir.parent + "/shared_prefs/rebuild_preference.xml")
                val settingsPreferencesNew = File(downloadDir + "Shiro_Backup_Settings_${date}.xml")

                customPreferences.copyTo(customPreferencesNew)
                settingsPreferences.copyTo(settingsPreferencesNew)*/
                Toast.makeText(this, "Successfully stored settings and data to $downloadDir", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "Storage permissions missing, please try again", Toast.LENGTH_LONG).show()
                requestRW()
                return
            }

            /*SharedPreferencesBackupHelper(this)
                val all = DataStore.getSharedPrefs().all
                //println(all)*/
        } catch (e: Exception) {
            Toast.makeText(this, "Error backing up :(", Toast.LENGTH_LONG).show()
        }
    }

    fun FragmentActivity.restorePrompt() {
        runOnUiThread {
            restoreFileSelector?.launch("*/*")
        }
    }

    fun isBlacklisted(key: String): Boolean {
        return filterRegex.containsMatchIn(key)
    }

    private fun <T> Context.restoreMap(map: Map<String, T>?, isEditingAppSettings: Boolean = false) {
        map?.filter { !isBlacklisted(it.key) }?.forEach {
            setKeyRaw(it.key, it.value, isEditingAppSettings)
        }
    }

    fun Context.restore(backupFile: BackupFile, restoreSettings: Boolean, restoreDataStore: Boolean) {
        try {
            if (restoreSettings) {
                restoreMap(backupFile.settings._Bool, true)
                restoreMap(backupFile.settings._Int, true)
                restoreMap(backupFile.settings._String, true)
                restoreMap(backupFile.settings._Float, true)
                restoreMap(backupFile.settings._Long, true)
                restoreMap(backupFile.settings._StringSet, true)
            }

            if (restoreDataStore) {
                restoreMap(backupFile.datastore._Bool)
                restoreMap(backupFile.datastore._Int)
                restoreMap(backupFile.datastore._String)
                restoreMap(backupFile.datastore._Float)
                restoreMap(backupFile.datastore._Long)
                restoreMap(backupFile.datastore._StringSet)
            }
        } catch (e: Exception) {

        }
    }
}