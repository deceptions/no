package com.lagradost.shiro.utils

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
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
import com.lagradost.shiro.utils.mvvm.logError
import java.io.File
import kotlin.concurrent.thread

/**
 * GitHub-releases based updater.
 *
 * Releases are read from every repository in the `update_repos` setting (default: the Nekomo-App repos) and the
 * newest matching one wins. Release assets are matched to *this* build, so a Beta app is only ever offered the Beta
 * APK (same package name/signature) and a TV build only the TV APK:
 *   Nekomo-<phone|TV>-<release|beta|alpha>.apk   (legacy `x.y.z.apk` / `x.y.z-TV.apk` names still work for stable)
 * Pre-releases are offered to beta/debug builds always, and to stable builds when "beta_mode" is enabled.
 */
object InAppUpdater {
    const val DEFAULT_REPOS = "Nekomo-App/Nekomo,Nekomo-App/neko-source"
    private const val AUTO_CHECK_INTERVAL_MS = 60 * 60 * 1000L

    data class GithubAsset(
        @JsonProperty("name") val name: String,
        @JsonProperty("size") val size: Long = 0,
        @JsonProperty("browser_download_url") val browser_download_url: String,
        @JsonProperty("content_type") val content_type: String? = null,
    )

    data class GithubRelease(
        @JsonProperty("tag_name") val tag_name: String,
        @JsonProperty("body") val body: String? = null,
        @JsonProperty("assets") val assets: List<GithubAsset> = emptyList(),
        @JsonProperty("draft") val draft: Boolean = false,
        @JsonProperty("prerelease") val prerelease: Boolean = false,
    )

    data class Update(
        @JsonProperty("shouldUpdate") val shouldUpdate: Boolean,
        @JsonProperty("updateURL") val updateURL: String?,
        @JsonProperty("updateVersion") val updateVersion: String?,
        @JsonProperty("changelog") val changelog: String?,
        @JsonProperty("repo") val repo: String? = null,
        @JsonProperty("prerelease") val prerelease: Boolean = false,
    )

    private val NO_UPDATE = Update(false, null, null, null)

    private val mapper = JsonMapper.builder().addModule(KotlinModule.Builder().build())
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false).build()

    private val repoRegex = Regex("""^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$""")
    private val versionRegex = Regex("""(\d+(?:\.\d+)+)""")
    private val tvRegex = Regex("""(^|[^a-z])tv([^a-z]|$)""")
    private val typeWords = listOf("debug", "alpha", "beta", "release")

    /** "Stable", "Beta" or "Debug" - the channel of the installed build. */
    fun installedChannel() = when (BuildConfig.BUILD_TYPE) {
        "debug" -> "Alpha"
        "beta" -> "Beta"
        else -> "Stable"
    }

    fun isTvBuild() = BuildConfig.FLAVOR == "tv"

    fun repos(context: Context): List<String> {
        val raw = PreferenceManager.getDefaultSharedPreferences(context).getString("update_repos", DEFAULT_REPOS)
        return (raw ?: DEFAULT_REPOS).split(',', ';', '\n', ' ').map { it.trim() }
            .filter { repoRegex.matches(it) }.distinct().ifEmpty { DEFAULT_REPOS.split(',') }
    }

    /** Release notes are GitHub markdown; the dialog is plain text, so drop tables, headings, bold, code ticks and link targets. */
    internal fun plainNotes(md: String?): String = md.orEmpty().lines()
        .filterNot { it.trim().startsWith("|") }
        .joinToString("\n") {
            it.replace(Regex("^\\s*#+\\s*"), "").replace("**", "").replace("`", "").replace(Regex("\\[([^\\]]*)]\\([^)]*\\)"), "$1")
        }
        .replace(Regex("\n{3,}"), "\n\n").trim()

    internal fun parseVersion(s: String?): List<Int>? =
        s?.let { versionRegex.find(it) }?.value?.split('.')?.mapNotNull { it.toIntOrNull() }?.takeIf { it.isNotEmpty() }

    internal fun compareVersions(a: List<Int>, b: List<Int>): Int {
        for (i in 0 until maxOf(a.size, b.size)) {
            val d = a.getOrElse(i) { 0 }.compareTo(b.getOrElse(i) { 0 })
            if (d != 0) return d
        }
        return 0
    }

    /** True if the asset is an APK built for this flavor (phone/tv) and build type (stable/beta/debug). */
    private fun assetMatches(asset: GithubAsset): Boolean {
        val name = asset.name.lowercase()
        if (!name.endsWith(".apk")) return false
        if (tvRegex.containsMatchIn(name) != isTvBuild()) return false
        val words = typeWords.filter { name.contains(it) }
        val type = BuildConfig.BUILD_TYPE
        return if (words.isEmpty()) type == "release" else (type in words || (type == "debug" && "alpha" in words))
    }

    private fun isTrustedDownload(url: String): Boolean {
        val host = try { Uri.parse(url).host?.lowercase() } catch (e: Exception) { null } ?: return false
        return url.startsWith("https://") && (host == "github.com" || host.endsWith(".githubusercontent.com"))
    }

    /** GitHub's releases API first; if it fails or is rate-limited, the repo's own apks/latest.json (raw.githubusercontent.com). */
    private fun fetchReleases(repo: String): List<GithubRelease> {
        val fromApi = try {
            val headers = mapOf("Accept" to "application/vnd.github.v3+json")
            mapper.readValue<List<GithubRelease>>(khttp.get("https://api.github.com/repos/$repo/releases?per_page=30", headers = headers).text)
        } catch (e: Exception) {
            logError(e)
            emptyList()
        }
        return fromApi.ifEmpty { fetchFromManifest(repo) }
    }

    private fun fetchFromManifest(repo: String): List<GithubRelease> = try {
        val base = "https://raw.githubusercontent.com/$repo/main/apks/"
        val node = mapper.readTree(khttp.get(base + "latest.json").text)
        val assets = node["builds"].map { GithubAsset(it["file"].asText(), it["size"]?.asLong() ?: 0, base + it["file"].asText()) }
        listOf(GithubRelease("v" + node["version"].asText(), "Published in the $repo repository.", assets, false, true))
    } catch (e: Exception) {
        logError(e)
        emptyList()
    }

    fun getAppUpdate(context: Context): Update {
        try {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val allowPrerelease = BuildConfig.BUILD_TYPE != "release" || prefs.getBoolean("beta_mode", false)
            val current = parseVersion(BuildConfig.VERSION_NAME) ?: return NO_UPDATE

            var best: Update? = null
            var bestVersion: List<Int>? = null
            for (repo in repos(context)) {
                for (release in fetchReleases(repo)) {
                    if (release.draft || (release.prerelease && !allowPrerelease)) continue
                    val version = parseVersion(release.tag_name) ?: continue
                    val asset = release.assets.firstOrNull { assetMatches(it) && isTrustedDownload(it.browser_download_url) } ?: continue
                    if (bestVersion == null || compareVersions(version, bestVersion) > 0) {
                        bestVersion = version
                        best = Update(false, asset.browser_download_url, version.joinToString("."), release.body, repo, release.prerelease)
                    }
                }
            }
            val found = best ?: return NO_UPDATE
            return found.copy(shouldUpdate = compareVersions(bestVersion!!, current) > 0)
        } catch (e: Exception) {
            logError(e)
            return NO_UPDATE
        }
    }

    private fun FragmentActivity.downloadUpdate(url: String): Boolean {
        if (!isTrustedDownload(url)) return false
        val downloadManager = getSystemService<DownloadManager>() ?: return false
        File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "nekomo-update.apk").delete()

        val request = DownloadManager.Request(Uri.parse(url))
            .setMimeType("application/vnd.android.package-archive")
            .setTitle("Nekomo update")
            .setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, "nekomo-update.apk")
            .setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE)
            .setAllowedOverRoaming(true)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)

        val id = downloadManager.enqueue(request)
        var receiver: BroadcastReceiver? = null
        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val downloadId = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, id) ?: id
                if (downloadId != id) return
                downloadManager.query(DownloadManager.Query().setFilterById(downloadId))?.use { c ->
                    if (c.moveToFirst() && DownloadManager.STATUS_SUCCESSFUL == c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                        val uri = Uri.parse(c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI)))
                        openApk(context ?: this@downloadUpdate, uri)
                    } else {
                        runOnUiThread { Toast.makeText(this@downloadUpdate, "Update download failed", Toast.LENGTH_LONG).show() }
                    }
                }
                try { unregisterReceiver(receiver) } catch (e: Exception) { }
            }
        }
        ContextCompat.registerReceiver(
            this, receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), ContextCompat.RECEIVER_EXPORTED
        )
        return true
    }

    fun openApk(context: Context, uri: Uri) {
        val path = uri.path ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            Toast.makeText(context, "Allow Nekomo to install apps, then run the update again", Toast.LENGTH_LONG).show()
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        }
        val contentUri = FileProvider.getUriForFile(context, BuildConfig.APPLICATION_ID + ".provider", File(path))
        context.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
                setDataAndType(contentUri, "application/vnd.android.package-archive")
            }
        )
    }

    /**
     * @param checkAutoUpdate true for the startup check (respects "auto_update" and is throttled), false for the
     * manual "Check for update" button. Returns true when an update dialog was shown.
     */
    fun FragmentActivity.runAutoUpdate(checkAutoUpdate: Boolean = true): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        if (checkAutoUpdate) {
            if (!prefs.getBoolean("auto_update", true)) return false
            val now = System.currentTimeMillis()
            if (now - prefs.getLong("update_last_check", 0L) < AUTO_CHECK_INTERVAL_MS) return false
            prefs.edit().putLong("update_last_check", now).apply()
        }

        val update = getAppUpdate(this)
        if (!update.shouldUpdate || update.updateURL == null) return false

        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            val channel = if (update.prerelease) "pre-release" else "stable"
            AlertDialog.Builder(this, R.style.AlertDialogCustom).apply {
                setTitle("New update found!\n${BuildConfig.VERSION_NAME} -> ${update.updateVersion}")
                setMessage("${plainNotes(update.changelog)}\n\nChannel: $channel (${update.repo})")
                setPositiveButton("Update") { _, _ ->
                    Toast.makeText(this@runAutoUpdate, "Download started", Toast.LENGTH_LONG).show()
                    thread {
                        if (!downloadUpdate(update.updateURL)) {
                            runOnUiThread { Toast.makeText(this@runAutoUpdate, "Download failed", Toast.LENGTH_LONG).show() }
                        }
                    }
                }
                setNegativeButton("Cancel") { _, _ -> }
                if (checkAutoUpdate) setNeutralButton("Don't show again") { _, _ ->
                    prefs.edit().putBoolean("auto_update", false).apply()
                }
            }.show()
        }
        return true
    }
}
