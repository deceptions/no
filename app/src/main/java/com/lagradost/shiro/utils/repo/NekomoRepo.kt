package com.lagradost.shiro.utils.repo

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.shiro.utils.ContentPrefs
import com.lagradost.shiro.utils.cs3.CsPluginManager
import com.lagradost.shiro.utils.mvvm.logError
import com.lagradost.shiro.utils.zg.ZangetsuManager
import java.util.concurrent.TimeUnit

/**
 * The optional official "Nekomo repository". It is a small manifest that points at the free/legal source repositories
 * Nekomo recommends and lists the metadata APIs it uses. Nothing happens until the user adds it (private code or its URL) and confirms. It only ever handles script/plugin sources (CloudStream .cs3, Zangetsu .js). APK extensions
 * (Aniyomi, Mihon) are never downloaded, installed or updated by this system.
 */
object NekomoRepo {
    const val OFFICIAL_URL = "https://raw.githubusercontent.com/Nekomo-App/neko-source/main/repo/index.json"

    data class Entry(
        @JsonProperty("type") val type: String,
        @JsonProperty("name") val name: String,
        @JsonProperty("url") val url: String,
        @JsonProperty("description") val description: String? = null,
    )

    data class Api(
        @JsonProperty("id") val id: String,
        @JsonProperty("name") val name: String,
        @JsonProperty("description") val description: String? = null,
        @JsonProperty("url") val url: String? = null,
    )

    data class Manifest(
        @JsonProperty("nekomoRepository") val marker: Int? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("description") val description: String? = null,
        @JsonProperty("version") val version: Int = 1,
        @JsonProperty("updated") val updated: String? = null,
        @JsonProperty("repositories") val repositories: List<Entry> = emptyList(),
        @JsonProperty("apis") val apis: List<Api> = emptyList(),
    )

    data class Added(
        @JsonProperty("url") val url: String,
        @JsonProperty("name") val name: String,
        @JsonProperty("version") val version: Int,
        @JsonProperty("appliedAt") val appliedAt: Long,
        @JsonProperty("repos") val repos: List<String>,
    )

    data class Event(
        @JsonProperty("time") val time: Long,
        @JsonProperty("kind") val kind: String,
        @JsonProperty("item") val item: String,
        @JsonProperty("detail") val detail: String,
        @JsonProperty("ok") val ok: Boolean,
    )

    data class Update(
        @JsonProperty("kind") val kind: String, // "cs" or "zg"
        @JsonProperty("id") val id: String,
        @JsonProperty("name") val name: String,
        @JsonProperty("from") val from: String,
        @JsonProperty("to") val to: String,
        @JsonProperty("repoUrl") val repoUrl: String,
    )

    private val mapper = JsonMapper.builder().addModule(KotlinModule.Builder().build())
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false).build()

    private fun prefs(ctx: Context) = PreferenceManager.getDefaultSharedPreferences(ctx)

    // ---------------------------------------------------------------------------------------------------------
    // Resolving and reading the manifest
    // ---------------------------------------------------------------------------------------------------------

    /** True when [input] is one of the private shortcodes (see [Shortcodes]); the codes are never displayed anywhere. */
    fun isNickname(input: String): Boolean = Shortcodes.lookup(input) != null

    /** A repository shortcode -> its URL; an http(s) URL is used as is. */
    fun resolve(input: String): String? {
        val t = input.trim()
        Shortcodes.lookup(t)?.takeIf { it.kind == "repo" }?.url?.let { return it }
        return if (Regex("^https?://\\S+$").matches(t)) t else null
    }

    /** Blocking. Returns null when the URL is not a Nekomo repository manifest. */
    fun fetch(url: String): Manifest? = try {
        val res = khttp.get(url, timeout = 30.0)
        if (res.statusCode != 200) null else mapper.readValue<Manifest>(res.text).takeIf { it.marker != null }
    } catch (t: Throwable) {
        logError(t)
        null
    }

    // ---------------------------------------------------------------------------------------------------------
    // Plan + apply
    // ---------------------------------------------------------------------------------------------------------

    /** 18+ plugins are skipped unless enabled. Movie/TV plugins are installed; the "Show movies and TV shows" switch only controls where they appear. */
    private fun installable(ctx: Context, p: CsPluginManager.CsSitePlugin): Boolean =
        p.status != 0 && ("nsfw" !in p.tvTypes.orEmpty().map { it.lowercase() } || ContentPrefs.nsfw(ctx))

    data class PlanItem(val entry: Entry, val names: List<String>, val reachable: Boolean)

    /** Blocking: what adding this repository would register and install. */
    fun plan(ctx: Context, m: Manifest): List<PlanItem> = m.repositories.map { e ->
        when (e.type) {
            "cloudstream" -> {
                val have = CsPluginManager.getInstalled(ctx).map { it.internalName }.toSet()
                val plugins = CsPluginManager.fetchRepoPlugins(e.url).filter { installable(ctx, it) && it.internalName !in have }
                PlanItem(e, plugins.map { it.displayName }, CsPluginManager.fetchRepository(e.url) != null)
            }
            "zangetsu" -> {
                val man = ZangetsuManager.fetchManifest(e.url)
                val have = ZangetsuManager.getInstalled(ctx).map { it.id }.toSet()
                PlanItem(e, man?.sources.orEmpty().filter { (!it.nsfw || ContentPrefs.nsfw(ctx)) && it.id !in have }.map { it.name }, man != null)
            }
            else -> PlanItem(e, emptyList(), false)
        }
    }

    data class ApplyResult(val registered: Int, val installed: Int, val failed: List<String>)

    /** Blocking: registers every repository in the manifest and installs the plugins/sources it offers. */
    fun apply(ctx: Context, url: String, m: Manifest, progress: (String) -> Unit): ApplyResult {
        var registered = 0
        var installed = 0
        val failed = ArrayList<String>()
        for (e in m.repositories) {
            try {
                when (e.type) {
                    "cloudstream" -> {
                        CsPluginManager.addRepo(ctx, CsPluginManager.CsRepo(e.url, e.name))
                        registered++
                        val have = CsPluginManager.getInstalled(ctx).map { it.internalName }.toSet()
                        for (p in CsPluginManager.fetchRepoPlugins(e.url)) {
                            if (!installable(ctx, p) || p.internalName in have) continue
                            progress("Installing ${p.displayName}…")
                            val r = CsPluginManager.installPlugin(ctx, e.url, p)
                            if (r.success) installed++ else failed += "${p.displayName}: ${r.message}"
                        }
                    }
                    "zangetsu" -> {
                        ZangetsuManager.addRepo(ctx, ZangetsuManager.ZgRepo(e.url, e.name))
                        registered++
                        val man = ZangetsuManager.fetchManifest(e.url)
                        if (man == null) failed += "${e.name}: could not read the repository"
                        man?.sources.orEmpty().forEach { s ->
                            if ((s.nsfw && !ContentPrefs.nsfw(ctx)) || ZangetsuManager.getInstalled(ctx).any { it.id == s.id }) return@forEach
                            progress("Installing ${s.name}…")
                            val (ok, msg) = ZangetsuManager.install(ctx, e.url, s)
                            if (ok) installed++ else failed += "${s.name}: $msg"
                        }
                    }
                    else -> failed += "${e.name}: unsupported repository type \"${e.type}\""
                }
            } catch (t: Throwable) {
                logError(t)
                failed += "${e.name}: ${t.message ?: t.javaClass.simpleName}"
            }
        }
        saveAdded(ctx, getAdded(ctx).filter { it.url != url } + Added(url, m.name ?: "Nekomo repository", m.version, System.currentTimeMillis(), m.repositories.map { it.url }))
        log(ctx, failed.isEmpty(), "repository", m.name ?: url, "Registered $registered, installed $installed" + if (failed.isNotEmpty()) ", ${failed.size} failed" else "")
        return ApplyResult(registered, installed, failed)
    }

    fun getAdded(ctx: Context): List<Added> = try {
        prefs(ctx).getString("nekomo_repos_v1", null)?.let { mapper.readValue<List<Added>>(it) } ?: emptyList()
    } catch (e: Exception) {
        emptyList()
    }

    private fun saveAdded(ctx: Context, list: List<Added>) =
        prefs(ctx).edit().putString("nekomo_repos_v1", mapper.writeValueAsString(list)).apply()

    /** Removes the repository from Nekomo. With [uninstall] its plugins/sources are removed too. */
    fun remove(ctx: Context, added: Added, uninstall: Boolean) {
        for (repoUrl in added.repos) {
            if (uninstall) {
                CsPluginManager.getInstalled(ctx).filter { it.repoUrl == repoUrl }.forEach { CsPluginManager.uninstallPlugin(ctx, it.internalName) }
                ZangetsuManager.getInstalled(ctx).filter { it.repoUrl == repoUrl }.forEach { ZangetsuManager.uninstall(ctx, it.id) }
            }
            CsPluginManager.removeRepo(ctx, repoUrl)
            ZangetsuManager.removeRepo(ctx, repoUrl)
        }
        saveAdded(ctx, getAdded(ctx).filter { it.url != added.url })
        log(ctx, true, "repository", added.name, if (uninstall) "Removed with its sources" else "Removed (sources kept)")
    }

    // ---------------------------------------------------------------------------------------------------------
    // History
    // ---------------------------------------------------------------------------------------------------------

    fun history(ctx: Context): List<Event> = try {
        prefs(ctx).getString("ext_history_v1", null)?.let { mapper.readValue<List<Event>>(it) } ?: emptyList()
    } catch (e: Exception) {
        emptyList()
    }

    fun log(ctx: Context, ok: Boolean, kind: String, item: String, detail: String) {
        val list = (listOf(Event(System.currentTimeMillis(), kind, item, detail, ok)) + history(ctx)).take(100)
        prefs(ctx).edit().putString("ext_history_v1", mapper.writeValueAsString(list)).apply()
    }

    fun clearHistory(ctx: Context) = prefs(ctx).edit().remove("ext_history_v1").apply()
}

/** Update checks and installs for script/plugin sources. APK extensions are deliberately not part of this. */
object ExtensionUpdater {
    /** Called by the managers after any successful install/update so every page agrees on what is outdated. */
    fun dropPending(ctx: Context, kind: String, id: String) =
        savePending(ctx, pending(ctx).filter { !(it.kind == kind && it.id == id) })

    private val mapper = JsonMapper.builder().addModule(KotlinModule.Builder().build())
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false).build()

    private fun prefs(ctx: Context) = PreferenceManager.getDefaultSharedPreferences(ctx)

    fun autoEnabled(ctx: Context) = prefs(ctx).getBoolean("ext_auto_update", true)
    fun setAutoEnabled(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean("ext_auto_update", on).apply()
        schedule(ctx)
    }

    val intervals = listOf(6, 12, 24, 72, 168)
    fun intervalHours(ctx: Context) = prefs(ctx).getInt("ext_update_hours", 24)
    fun setIntervalHours(ctx: Context, h: Int) {
        prefs(ctx).edit().putInt("ext_update_hours", h).apply()
        schedule(ctx, replace = true)
    }

    fun lastCheck(ctx: Context) = prefs(ctx).getLong("ext_last_check", 0L)
    fun lastError(ctx: Context): String? = prefs(ctx).getString("ext_last_error", null)

    fun pending(ctx: Context): List<NekomoRepo.Update> = try {
        prefs(ctx).getString("ext_pending_v1", null)?.let { mapper.readValue<List<NekomoRepo.Update>>(it) } ?: emptyList()
    } catch (e: Exception) {
        emptyList()
    }

    private fun savePending(ctx: Context, list: List<NekomoRepo.Update>) =
        prefs(ctx).edit().putString("ext_pending_v1", mapper.writeValueAsString(list)).apply()

    /** Blocking. Looks for newer versions of every installed plugin/source. Returns (updates, errors). */
    fun check(ctx: Context): Pair<List<NekomoRepo.Update>, List<String>> {
        val updates = ArrayList<NekomoRepo.Update>()
        val errors = ArrayList<String>()
        CsPluginManager.getInstalled(ctx).filter { it.repoUrl != null }.groupBy { it.repoUrl!! }.forEach { (repo, installed) ->
            val plugins = CsPluginManager.fetchRepoPlugins(repo)
            if (plugins.isEmpty()) {
                errors += "Could not reach repository $repo"
                return@forEach
            }
            installed.forEach { inst ->
                val latest = plugins.firstOrNull { it.internalName == inst.internalName } ?: return@forEach
                if (latest.version > inst.version || (latest.fileHash != null && latest.fileHash != inst.fileHash && latest.version >= inst.version))
                    updates += NekomoRepo.Update("cs", inst.internalName, inst.name, "v${inst.version}", "v${latest.version}", repo)
            }
        }
        ZangetsuManager.getInstalled(ctx).filter { it.repoUrl != null }.groupBy { it.repoUrl!! }.forEach { (repo, installed) ->
            val man = ZangetsuManager.fetchManifest(repo)
            if (man == null) {
                errors += "Could not reach repository $repo"
                return@forEach
            }
            installed.forEach { inst ->
                val latest = man.sources.firstOrNull { it.id == inst.id } ?: return@forEach
                if (latest.version != inst.version) updates += NekomoRepo.Update("zg", inst.id, inst.name, "v${inst.version}", "v${latest.version}", repo)
            }
        }
        savePending(ctx, updates)
        prefs(ctx).edit().putLong("ext_last_check", System.currentTimeMillis()).apply()
        NekomoRepo.log(ctx, errors.isEmpty(), "check", "Update check", if (updates.isEmpty()) "Everything is up to date" else "${updates.size} update(s) available" + if (errors.isNotEmpty()) "; ${errors.size} repository error(s)" else "")
        return updates to errors
    }

    /** Blocking. Installs one update. The previous version stays in place unless the new one downloads and verifies. */
    fun apply(ctx: Context, u: NekomoRepo.Update): Pair<Boolean, String> {
        val result: Pair<Boolean, String> = try {
            if (u.kind == "cs") {
                val r = CsPluginManager.updatePlugin(ctx, u.id)
                r.success to r.message
            } else {
                val src = ZangetsuManager.fetchManifest(u.repoUrl)?.sources?.firstOrNull { it.id == u.id }
                if (src == null) false to "No longer offered by the repository"
                else ZangetsuManager.install(ctx, u.repoUrl, src)
            }
        } catch (t: Throwable) {
            logError(t)
            false to (t.message ?: t.javaClass.simpleName)
        }
        if (result.first) savePending(ctx, pending(ctx).filter { !(it.kind == u.kind && it.id == u.id) })
        return result
    }

    /** Called from the background worker and at app start: only acts when auto-update is on and a check is due. */
    fun runAuto(ctx: Context, force: Boolean = false) {
        if (!force && !autoEnabled(ctx)) return
        if (!force && System.currentTimeMillis() - lastCheck(ctx) < intervalHours(ctx) * 3_600_000L - 60_000L) return
        val (updates, errors) = try {
            check(ctx)
        } catch (t: Throwable) {
            logError(t)
            emptyList<NekomoRepo.Update>() to listOf(t.message ?: "Update check failed")
        }
        var installed = 0
        val failures = ArrayList<String>(errors)
        updates.forEach { u ->
            val (ok, msg) = apply(ctx, u)
            if (ok) installed++ else failures += "${u.name}: $msg"
        }
        prefs(ctx).edit().apply { if (failures.isEmpty()) remove("ext_last_error") else putString("ext_last_error", failures.first()) }.apply()
        if (installed > 0) notify(ctx, "Sources updated", "$installed source(s) were updated.")
        if (failures.isNotEmpty()) notify(ctx, "Source update problem", failures.first())
    }

    fun schedule(ctx: Context, replace: Boolean = false) {
        val wm = WorkManager.getInstance(ctx)
        if (!autoEnabled(ctx)) {
            wm.cancelUniqueWork("ext_updates")
            return
        }
        val request = PeriodicWorkRequestBuilder<ExtensionUpdateWorker>(intervalHours(ctx).toLong(), TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        wm.enqueueUniquePeriodicWork("ext_updates", if (replace) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP, request)
    }

    private fun notify(ctx: Context, title: String, text: String) {
        try {
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
            if (!NotificationManagerCompat.from(ctx).areNotificationsEnabled()) return
            if (Build.VERSION.SDK_INT >= 26) {
                (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                    .createNotificationChannel(NotificationChannel("ext_updates", "Source updates", NotificationManager.IMPORTANCE_LOW))
            }
            val n = NotificationCompat.Builder(ctx, "ext_updates").setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(title).setContentText(text).setAutoCancel(true).build()
            NotificationManagerCompat.from(ctx).notify(7301, n)
        } catch (e: Exception) {
            logError(e)
        }
    }
}

class ExtensionUpdateWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        ExtensionUpdater.runAuto(applicationContext)
        return Result.success()
    }
}
