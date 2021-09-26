package com.lagradost.shiro.utils

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.core.content.getSystemService
import androidx.fragment.app.FragmentActivity
import androidx.preference.PreferenceManager
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.shiro.BuildConfig
import com.lagradost.shiro.R
import com.lagradost.shiro.ui.tv.TvActivity.Companion.tvActivity
import java.io.File
import kotlin.concurrent.thread

// Stolen from LagradOst's quicknovel :)
object InAppUpdater {
    // === IN APP UPDATER ===
    data class GithubAsset(
        @JsonProperty("name") val name: String,
        @JsonProperty("size") val size: Int, // Size bytes
        @JsonProperty("browser_download_url") val browser_download_url: String, // download link
        @JsonProperty("content_type") val content_type: String, // application/vnd.android.package-archive
    )

    data class GithubRelease(
        @JsonProperty("tag_name") val tag_name: String, // Version code
        @JsonProperty("body") val body: String, // Desc
        @JsonProperty("assets") val assets: List<GithubAsset>,
        @JsonProperty("target_commitish") val target_commitish: String, // branch
        @JsonProperty("draft") val draft: Boolean,
        @JsonProperty("prerelease") val prerelease: Boolean,
    )

    data class Update(
        @JsonProperty("shouldUpdate") val shouldUpdate: Boolean,
        @JsonProperty("updateURL") val updateURL: String?,
        @JsonProperty("updateVersion") val updateVersion: String?,
        @JsonProperty("changelog") val changelog: String?,
    )

    private val mapper = JsonMapper.builder().addModule(KotlinModule())
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false).build()


    private fun FragmentActivity.getAppUpdate(): Update {
        try {
            val settingsManager = PreferenceManager.getDefaultSharedPreferences(this)

            val isBetaMode = settingsManager.getBoolean("beta_mode", false)
            val isTv = tvActivity != null

            val url = "https://api.github.com/repos/Blatzar/shiro-app/releases"
            val headers = mapOf("Accept" to "application/vnd.github.v3+json")

            val response = try {
                mapper.readValue<List<GithubRelease>>(khttp.get(url, headers = headers).text)
            } catch (e: java.lang.Exception) {
                null
            } ?: return Update(false, null, null, null)
            //val response =
            //     mapper.readValue<List<GithubRelease>>(khttp.get(url, headers = headers).text)

            val cleanedResponse = response.filter { (!it.prerelease || isBetaMode) && !it.draft }

            //val versionRegexUniversal = Regex("""(.*?((\d)\.(\d)\.(\d)).*?\.apk)""")
            val versionRegex = if (isTv) {
                Regex("""(.*?((\d)\.(\d)\.(\d))-TV\.apk)""")
            } else {
                Regex("""(.*?((\d)\.(\d)\.(\d))\.apk)""")
            }

            /*
            val releases = response.map { it.assets }.flatten()
                .filter { it.content_type == "application/vnd.android.package-archive" }
            val found =
                releases.sortedWith(compareBy {
                    versionRegex.find(it.name)?.groupValues?.get(2)
                }).toList().lastOrNull()*/

            fun getReleaseAssetVersion(release: GithubRelease): String? {
                return release.assets.mapNotNull { versionRegex.find(it.name)?.groupValues?.get(2) }.firstOrNull()
            }

            val found =
                cleanedResponse.sortedWith(compareBy { release ->
                    getReleaseAssetVersion(release)
                }).toList().lastOrNull()
            val foundAsset =
                found?.assets?.filter { !versionRegex.find(it.name)?.groupValues.isNullOrEmpty() }?.getOrNull(0)

            val currentVersion = this.packageName?.let {
                packageManager.getPackageInfo(
                    it,
                    0
                )
            }

            val foundVersion = foundAsset?.name?.let { versionRegex.find(it) }
            val shouldUpdate =
                if (found != null && foundAsset?.browser_download_url != "" && foundVersion != null) currentVersion?.versionName?.compareTo(
                    foundVersion.groupValues[2]
                )!! < 0 else false
            return if (foundVersion != null) {
                Update(shouldUpdate, foundAsset.browser_download_url, foundVersion.groupValues[2], found.body)
            } else {
                Update(false, null, null, null)
            }

        } catch (e: Exception) {
            e.printStackTrace()
            return Update(false, null, null, null)
        }
    }

    private fun FragmentActivity.downloadUpdate(url: String): Boolean {
        println("DOWNLOAD UPDATE $url")
//        var fullResume = false // IF FULL RESUME
        val downloadManager = getSystemService<DownloadManager>()!!

        val request = DownloadManager.Request(Uri.parse(url))
            .setMimeType("application/vnd.android.package-archive")
            .setTitle("Shiro update")
            .setDestinationInExternalFilesDir(
                this,
                Environment.DIRECTORY_DOWNLOADS,
                "shiro.apk"
            )
            .setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE)
            .setAllowedOverRoaming(true)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)

        val id = downloadManager.enqueue(request)
        registerReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    val downloadId = intent?.getLongExtra(
                        DownloadManager.EXTRA_DOWNLOAD_ID, id
                    ) ?: id

                    val query = DownloadManager.Query()
                    query.setFilterById(downloadId)
                    val c = downloadManager.query(query)

                    if (c.moveToFirst()) {
                        val columnIndex = c
                            .getColumnIndex(DownloadManager.COLUMN_STATUS)
                        if (DownloadManager.STATUS_SUCCESSFUL == c
                                .getInt(columnIndex)
                        ) {
                            c.getColumnIndex(DownloadManager.COLUMN_MEDIAPROVIDER_URI)
                            val uri = Uri.parse(
                                c.getString(c.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI))
                            )
//                            val uri = downloadManager.getUriForDownloadedFile(downloadId)
                            openApk(context ?: this@downloadUpdate, uri)

                        }
                    }
                }
            }, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        )
        return true
    }

    fun openApk(context: Context, uri: Uri) {
        uri.path?.let {
            val contentUri = FileProvider.getUriForFile(
                context,
                BuildConfig.APPLICATION_ID + ".provider",
                File(it)
            )
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
                data = contentUri
            }
            context.startActivity(installIntent)
        }
    }


    fun FragmentActivity.runAutoUpdate(checkAutoUpdate: Boolean = true): Boolean {
        val settingsManager = PreferenceManager.getDefaultSharedPreferences(this)

        if (!checkAutoUpdate || settingsManager.getBoolean("auto_update", true)
        ) {
            val update = getAppUpdate()
            if (update.shouldUpdate && update.updateURL != null) {
                this.runOnUiThread {
                    val currentVersion = this.packageName?.let {
                        this.packageManager.getPackageInfo(
                            it,
                            0
                        )
                    }

                    val builder: AlertDialog.Builder = AlertDialog.Builder(this, R.style.AlertDialogCustom)
                    builder.setTitle("New update found!\n${currentVersion?.versionName} -> ${update.updateVersion}")
                    builder.setMessage("${update.changelog}")

                    builder.apply {
                        setPositiveButton("Update") { _, _ ->
                            Toast.makeText(this@runAutoUpdate, "Download started", Toast.LENGTH_LONG).show()
                            thread {
                                val downloadStatus = downloadUpdate(update.updateURL)
                                if (!downloadStatus) {
                                    runOnUiThread {
                                        Toast.makeText(
                                            this@runAutoUpdate,
                                            "Download Failed",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                }
                            }
                        }

                        setNegativeButton("Cancel") { _, _ -> }

                        if (checkAutoUpdate) {
                            setNeutralButton("Don't show again") { _, _ ->
                                settingsManager.edit().putBoolean("auto_update", false).apply()
                            }
                        }
                    }
                    builder.show()
                }
                return true
            }
            return false
        }
        return false
    }
}
