package com.lagradost.shiro.ui.settings

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.preference.PreferenceManager
import com.jaredrummler.cyanea.app.CyaneaAppCompatActivity
import com.lagradost.shiro.utils.aniyomi.AniyomiManager
import com.lagradost.shiro.utils.cs3.CsPluginManager
import com.lagradost.shiro.utils.meta.ApiHealth
import com.lagradost.shiro.utils.mihon.MihonManager
import com.lagradost.shiro.utils.repo.ExtensionUpdater
import com.lagradost.shiro.utils.repo.NekomoRepo
import com.lagradost.shiro.utils.repo.Shortcodes
import com.lagradost.shiro.utils.zg.ZangetsuManager
import java.text.DateFormat
import java.util.Date
import kotlin.concurrent.thread

/**
 * Settings -> Extensions manager. One place for: the optional Nekomo repository, the automatic updater, repositories,
 * installed sources (CloudStream plugins, Zangetsu scripts), the built-in metadata APIs, and APK extensions.
 * APK extensions are listed separately and are never downloaded, installed or updated automatically.
 */
class ExtensionManagerActivity : CyaneaAppCompatActivity() {
    private lateinit var b: PageBuilder

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = "Extensions manager"
        b = PageBuilder(this)
        setContentView(b.finish())
        render()
        // Deep link from NEX or a browser: only after the welcome gate has been completed
        val data = intent.data?.takeIf { it.scheme == "nekomo-repo" }
        val link = data?.takeIf { it.host == "add" }?.getQueryParameter("url")
        val sourceLink = data?.takeIf { it.host == "source" }
        val apiLink = data?.takeIf { it.host == "api" }?.getQueryParameter("id")
        if ((link != null || sourceLink != null || apiLink != null) && !com.lagradost.shiro.utils.auth.AuthManager.canEnter(this)) {
            toast("Open Nekomo and finish the welcome steps first, then try again.")
            startActivity(packageManager.getLaunchIntentForPackage(packageName))
            finish()
            return
        }
        (link ?: intent.getStringExtra("add"))?.let { startAdd(it) }
        sourceLink?.let { startSource(it.getQueryParameter("repo"), it.getQueryParameter("id"), it.getQueryParameter("type")) }
        apiLink?.let { apiOpeners[it]?.invoke() ?: toast("Unknown API: $it") }
    }

    override fun onResume() {
        super.onResume()
        if (::b.isInitialized) render()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    private fun date(t: Long) = if (t == 0L) "never" else DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(t))
    /** A readable name for a repository URL: owner/repo for GitHub-hosted ones, otherwise the host. */
    private fun host(url: String?): String {
        val rest = url?.substringAfter("://") ?: return "unknown"
        val host = rest.substringBefore('/')
        val parts = rest.substringAfter('/', "").split('/').filter { it.isNotBlank() }
        return if ((host == "raw.githubusercontent.com" || host == "github.com") && parts.size >= 2) "${parts[0]}/${parts[1]}" else host
    }

    private fun busy(message: String): Pair<AlertDialog, TextView> {
        val tv = TextView(this).apply { text = message; textSize = 15f; setPadding(b.dp(24), b.dp(20), b.dp(24), b.dp(20)) }
        val d = AlertDialog.Builder(this).setView(tv).setCancelable(false).create()
        d.show()
        return d to tv
    }

    private fun render() {
        b.root.removeAllViews()
        val r = b.root
        r.addView(b.text("One place to manage your sources, repositories and metadata APIs. Nothing is installed until you add something.", 13f, dim = true))
        nekomoSection(r)
        updaterSection(r)
        repositoriesSection(r)
        sourcesSection(r)
        apisSection(r)
        apkSection(r)
    }

    // ---------------------------------------------------------------------------------------------------------
    // Nekomo repository
    // ---------------------------------------------------------------------------------------------------------

    private fun nekomoSection(r: LinearLayout) {
        r.addView(b.heading("Nekomo repository"))
        val added = NekomoRepo.getAdded(this)
        if (added.isEmpty()) {
            val input = EditText(this).apply {
                hint = "Repository URL or code"
                inputType = InputType.TYPE_TEXT_VARIATION_URI
                setSingleLine()
                setTextColor(b.fg)
                setHintTextColor(b.fg and 0x00FFFFFF or 0x66000000)
            }
            r.addView(b.card(
                b.text("Nekomo Official Repository", 16f, bold = true),
                b.text("Free and legal sources recommended by Nekomo. Adding it registers its repositories and installs the sources they offer. It never touches APK extensions.", 13f, dim = true),
                input
            ))
            r.addView(b.primaryButton("Add repository") { startAdd(input.text.toString()) })
        } else {
            added.forEach { a ->
                r.addView(b.card(b.row(a.name, "v${a.version} · ${a.repos.size} source repositor${if (a.repos.size == 1) "y" else "ies"} · synced ${date(a.appliedAt)}") {
                    AlertDialog.Builder(this).setTitle(a.name)
                        .setItems(arrayOf("Sync again (add anything new)", "Remove, keep installed sources", "Remove and uninstall its sources")) { _, which ->
                            when (which) {
                                0 -> startAdd(a.url)
                                1 -> { NekomoRepo.remove(this, a, false); render() }
                                else -> { NekomoRepo.remove(this, a, true); render() }
                            }
                        }.setNegativeButton("Cancel", null).show()
                }))
            }
            // More repositories, sources or APIs can always be added by URL or code
            val more = EditText(this).apply {
                hint = "Repository URL or code"
                inputType = InputType.TYPE_TEXT_VARIATION_URI
                setSingleLine()
                setTextColor(b.fg)
                setHintTextColor(b.fg and 0x00FFFFFF or 0x66000000)
            }
            r.addView(b.card(b.text("Add another repository, source or API", 15f, bold = true), more))
            r.addView(b.primaryButton("Add") { startAdd(more.text.toString()) })
        }
    }

    private fun startAdd(raw: String) {
        // Source and API shortcodes open the matching dialog directly; repository shortcodes continue below
        Shortcodes.lookup(raw)?.let { e ->
            when (e.kind) {
                "source" -> { startSource(e.url, e.id, e.type); return }
                "api" -> { apiOpeners[e.id]?.invoke() ?: toast("That API is not available."); return }
            }
        }
        val url = NekomoRepo.resolve(raw)
        if (url == null) {
            toast("Enter a repository URL starting with https:// or a valid code")
            return
        }
        val (loading, label) = busy("Looking up the repository…")
        thread {
            val manifest = NekomoRepo.fetch(url)
            val plan = manifest?.let { NekomoRepo.plan(this, it) }
            val foreign = if (manifest == null) foreignKind(url) else null
            runOnUiThread {
                loading.dismiss()
                if (manifest == null || plan == null) {
                    if (foreign != null) {
                        showForeign(url, foreign.first, foreign.second)
                        return@runOnUiThread
                    }
                    AlertDialog.Builder(this).setTitle("Can't add repository")
                        .setMessage("That address is not a Nekomo repository, or it could not be reached. Check the address and your connection. Nekomo accepts Nekomo, CloudStream, Zangetsu, Aniyomi and Mihon repository addresses.")
                        .setPositiveButton("OK", null).show()
                    return@runOnUiThread
                }
                val summary = plan.joinToString("\n\n") { p ->
                    "${p.entry.name} (${p.entry.type})\n" + when {
                        !p.reachable -> "Could not be reached right now."
                        p.names.isEmpty() -> "Nothing new to install."
                        else -> "Will install: " + p.names.joinToString(", ")
                    }
                }
                AlertDialog.Builder(this)
                    .setTitle(manifest.name ?: "Nekomo repository")
                    .setMessage(
                        (manifest.description?.let { "$it\n\n" } ?: "") + summary +
                                "\n\nSources are third-party code that runs inside this app. Nekomo does not control them. Only continue if you trust this repository. APK extensions are never installed by this."
                    )
                    .setPositiveButton("Add and install") { _, _ -> runApply(url, manifest) }
                    .setNegativeButton("Cancel", null).show()
            }
        }
    }

    /** Recognises CloudStream, Zangetsu and Aniyomi/Mihon repository addresses. Blocking. Returns (kind, name). */
    private fun foreignKind(url: String): Pair<String, String>? {
        CsPluginManager.fetchRepository(url)?.takeIf { !it.pluginLists.isNullOrEmpty() }?.let { return "CloudStream" to (it.name ?: host(url)) }
        ZangetsuManager.fetchManifest(url)?.takeIf { it.sources.isNotEmpty() }?.let { return "Zangetsu" to (it.name ?: host(url)) }
        // Aniyomi (anime) and Mihon (manga) share one extension format; classify by package name instead of asking
        val ay = AniyomiManager.fetchIndex(AniyomiManager.normaliseRepoUrl(url))
        val mh = if (ay == null || ay.none { !it.pkg.contains("animeextension") }) MihonManager.fetchIndex(MihonManager.normaliseRepoUrl(url)) else null
        val anime = ay?.count { it.pkg.contains("animeextension") } ?: 0
        val manga = (ay?.count { !it.pkg.contains("animeextension") } ?: 0).coerceAtLeast(mh?.count { !it.pkg.contains("animeextension") } ?: 0)
        if (anime + manga > 0) return "APK" to "${host(url)}|$anime|$manga"
        return null
    }

    /** Registers a repository address only. Nothing is downloaded or installed, and APK extensions stay manual. */
    private fun showForeign(url: String, kind: String, name: String) {
        val warn = "\n\nThis only saves the repository address. Nothing is installed; browse and install from Settings > Extensions > Manual repositories after you confirm you trust it."
        val b = AlertDialog.Builder(this).setNegativeButton("Cancel", null)
        if (kind == "APK") {
            val parts = name.split("|")
            val label = parts[0]
            val anime = parts.getOrNull(1)?.toIntOrNull() ?: 0
            val manga = parts.getOrNull(2)?.toIntOrNull() ?: 0
            b.setTitle("Add Aniyomi / Mihon repository?")
                .setMessage("$label offers $anime anime extension(s) (Aniyomi) and $manga manga extension(s) (Mihon). It is added for whichever it contains.\n$url$warn")
                .setPositiveButton("Add") { _, _ ->
                    if (anime > 0) AniyomiManager.addRepo(this, AniyomiManager.AyRepo(AniyomiManager.normaliseRepoUrl(url), label))
                    if (manga > 0) MihonManager.addRepo(this, MihonManager.MhRepo(MihonManager.normaliseRepoUrl(url), label))
                    done(if (anime > 0 && manga > 0) "Aniyomi + Mihon" else if (anime > 0) "Aniyomi" else "Mihon", label)
                }
        } else {
            b.setTitle("Add $kind repository?").setMessage("$name\n$url$warn")
                .setPositiveButton("Add") { _, _ ->
                    if (kind == "CloudStream") CsPluginManager.addRepo(this, CsPluginManager.CsRepo(url, name))
                    else ZangetsuManager.addRepo(this, ZangetsuManager.ZgRepo(url, name))
                    done(kind, name)
                }
        }
        b.show()
    }

    private fun done(kind: String, name: String) {
        NekomoRepo.log(this, true, "repository", name, "Added $kind repository (address only)")
        toast("Added $name. Install its sources from Manual repositories.")
        render()
    }

    private fun runApply(url: String, manifest: NekomoRepo.Manifest) {
        val (dialog, label) = busy("Adding repository…")
        thread {
            val res = NekomoRepo.apply(this, url, manifest) { msg -> runOnUiThread { label.text = msg } }
            runOnUiThread {
                dialog.dismiss()
                render()
                AlertDialog.Builder(this).setTitle(if (res.failed.isEmpty()) "Repository added" else "Added with problems")
                    .setMessage("Registered ${res.registered} repositor${if (res.registered == 1) "y" else "ies"} and installed ${res.installed} source(s)." +
                            if (res.failed.isNotEmpty()) "\n\nCouldn't install:\n" + res.failed.joinToString("\n") else "")
                    .setPositiveButton("OK", null).show()
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Updater
    // ---------------------------------------------------------------------------------------------------------

    private fun updaterSection(r: LinearLayout) {
        r.addView(b.heading("Automatic updates"))
        val switch = Switch(this).apply {
            text = "Update sources automatically"
            isChecked = ExtensionUpdater.autoEnabled(this@ExtensionManagerActivity)
            setTextColor(b.fg)
            setOnCheckedChangeListener { _, on -> ExtensionUpdater.setAutoEnabled(this@ExtensionManagerActivity, on) }
        }
        val pending = ExtensionUpdater.pending(this)
        val err = ExtensionUpdater.lastError(this)
        r.addView(b.card(
            switch,
            b.row("Check every ${ExtensionUpdater.intervalHours(this).let { if (it >= 24 && it % 24 == 0) "${it / 24} day(s)" else "$it hours" }}", "Tap to change how often Nekomo checks") {
                val labels = ExtensionUpdater.intervals.map { if (it >= 24) "${it / 24} day(s)" else "$it hours" }.toTypedArray()
                AlertDialog.Builder(this).setTitle("Check for updates every")
                    .setItems(labels) { _, i -> ExtensionUpdater.setIntervalHours(this, ExtensionUpdater.intervals[i]); render() }.show()
            },
            b.text("Last check: ${date(ExtensionUpdater.lastCheck(this))}", 12f, dim = true),
            b.text(if (pending.isEmpty()) "Everything is up to date." else "${pending.size} update(s) available.", 13f),
            *(if (err != null) arrayOf<android.view.View>(b.text("Last problem: $err", 12f).apply { setTextColor(0xFFE57373.toInt()) }) else emptyArray())
        ))
        r.addView(b.primaryButton("Check for updates now") {
            val (d, _) = busy("Checking repositories…")
            thread {
                val (updates, errors) = ExtensionUpdater.check(this)
                runOnUiThread {
                    d.dismiss(); render()
                    toast(if (errors.isNotEmpty()) "Check finished with problems: ${errors.first()}" else if (updates.isEmpty()) "Everything is up to date" else "${updates.size} update(s) available")
                }
            }
        })
        if (pending.isNotEmpty()) {
            r.addView(b.card(*pending.map { u ->
                b.row("Update ${u.name}", "${u.from} -> ${u.to} · ${u.kind.let { if (it == "cs") "CloudStream" else "Zangetsu" }}") { installUpdate(listOf(u)) }
            }.toTypedArray()))
            r.addView(b.primaryButton("Update all (${pending.size})") { installUpdate(pending) })
        }
        val history = NekomoRepo.history(this).take(8)
        if (history.isNotEmpty()) {
            r.addView(b.card(
                b.text("Update history", 15f, bold = true),
                *history.map { e ->
                    b.text("${if (e.ok) "✓" else "✗"} ${date(e.time)} · ${e.item}\n    ${e.detail}", 12f, dim = e.ok).apply { if (!e.ok) setTextColor(0xFFE57373.toInt()) }
                }.toTypedArray(),
                b.row("Clear history") { NekomoRepo.clearHistory(this); render() }
            ))
        }
    }

    private fun installUpdate(list: List<NekomoRepo.Update>) {
        val (d, label) = busy("Updating…")
        thread {
            var ok = 0
            val failures = ArrayList<String>()
            list.forEach { u ->
                runOnUiThread { label.text = "Updating ${u.name}…" }
                val (success, msg) = ExtensionUpdater.apply(this, u)
                if (success) ok++ else failures += "${u.name}: $msg"
            }
            runOnUiThread {
                d.dismiss(); render()
                AlertDialog.Builder(this).setTitle("Updates finished")
                    .setMessage("$ok updated." + if (failures.isNotEmpty()) "\n\nNot updated (the previous version was kept):\n" + failures.joinToString("\n") else "")
                    .setPositiveButton("OK", null).show()
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Repositories
    // ---------------------------------------------------------------------------------------------------------

    private fun repositoriesSection(r: LinearLayout) {
        r.addView(b.heading("Repositories"))
        val cs = CsPluginManager.getRepos(this).map { Triple("CloudStream", it.name ?: host(it.url), it.url) }
        val zg = ZangetsuManager.getRepos(this).map { Triple("Zangetsu", it.name ?: host(it.url), it.url) }
        val ayRepos = AniyomiManager.getRepos(this)
        val mhRepos = MihonManager.getRepos(this)
        val shared = ayRepos.filter { a -> mhRepos.any { it.url == a.url } }.map { it.url }.toSet()
        val ay = ayRepos.filter { it.url !in shared }.map { Triple("Aniyomi", it.name ?: host(it.url), it.url) }
        val mh = mhRepos.filter { it.url !in shared }.map { Triple("Mihon", it.name ?: host(it.url), it.url) }
        val both = ayRepos.filter { it.url in shared }.map { Triple("Aniyomi + Mihon", it.name ?: host(it.url), it.url) }
        val all = cs + zg + both + ay + mh
        if (all.isEmpty()) {
            r.addView(b.card(b.text("No repositories yet. Add the Nekomo repository above, or add your own in Manual repositories (below).", 13f, dim = true)))
            return
        }
        r.addView(b.card(*all.map { (type, name, url) ->
            b.row(name, "$type · $url") {
                if (type == "Aniyomi" || type == "Mihon" || type == "Aniyomi + Mihon") {
                    AlertDialog.Builder(this).setTitle(name).setMessage("$url\n\nRemoving the repository does not touch any APK extension you installed.")
                        .setPositiveButton("Remove repository") { _, _ -> removeRepo(type, url, false) }
                        .setNegativeButton("Cancel", null).show()
                } else {
                    AlertDialog.Builder(this).setTitle(name).setMessage(url)
                        .setPositiveButton("Remove, keep sources") { _, _ -> removeRepo(type, url, false) }
                        .setNeutralButton("Remove and uninstall sources") { _, _ -> removeRepo(type, url, true) }
                        .setNegativeButton("Cancel", null).show()
                }
            }
        }.toTypedArray()))
    }

    private fun removeRepo(type: String, url: String, uninstall: Boolean) {
        if (type == "Aniyomi + Mihon") { AniyomiManager.removeRepo(this, url); MihonManager.removeRepo(this, url) }
        else if (type == "Aniyomi") AniyomiManager.removeRepo(this, url)
        else if (type == "Mihon") MihonManager.removeRepo(this, url)
        else if (type == "CloudStream") {
            if (uninstall) CsPluginManager.getInstalled(this).filter { it.repoUrl == url }.forEach { CsPluginManager.uninstallPlugin(this, it.internalName) }
            CsPluginManager.removeRepo(this, url)
        } else {
            if (uninstall) ZangetsuManager.getInstalled(this).filter { it.repoUrl == url }.forEach { ZangetsuManager.uninstall(this, it.id) }
            ZangetsuManager.removeRepo(this, url)
        }
        NekomoRepo.log(this, true, "repository", host(url), if (uninstall) "Removed with its sources" else "Removed (sources kept)")
        render()
    }

    // ---------------------------------------------------------------------------------------------------------
    // Sources
    // ---------------------------------------------------------------------------------------------------------

    private fun sourcesSection(r: LinearLayout) {
        r.addView(b.heading("Sources"))
        val pending = ExtensionUpdater.pending(this)
        val rows = ArrayList<android.view.View>()
        CsPluginManager.getInstalled(this).forEach { p ->
            val enabled = CsPluginManager.isPluginEnabled(this, p.internalName)
            val loaded = CsPluginManager.isLoaded(p.filePath)
            val up = pending.firstOrNull { it.kind == "cs" && it.id == p.internalName }
            rows += b.row("${p.name}  v${p.version}", "CloudStream · ${if (!enabled) "disabled" else if (loaded && CsPluginManager.hiddenByFilter(p.internalName)) "hidden by content filter (Settings > General)" else if (loaded) "active" else "not loaded"} · ${host(p.repoUrl)}" + (up?.let { " · update ${it.to}" } ?: "")) { csDialog(p, up) }
        }
        ZangetsuManager.getInstalled(this).forEach { s ->
            val enabled = ZangetsuManager.isEnabled(this, s.id)
            val up = pending.firstOrNull { it.kind == "zg" && it.id == s.id }
            rows += b.row("${s.name}  v${s.version}", "Zangetsu · ${s.type}/${s.lang} · ${if (enabled) "enabled" else "disabled"} · ${host(s.repoUrl)}" + (up?.let { " · update ${it.to}" } ?: "")) { zgDialog(s, up) }
        }
        if (rows.isEmpty()) r.addView(b.card(b.text("No sources installed. Nekomo works as a tracker until you add some.", 13f, dim = true)))
        else {
            r.addView(b.card(*rows.toTypedArray()))
            r.addView(b.primaryButton("Uninstall all sources") {
                AlertDialog.Builder(this).setTitle("Uninstall all sources?")
                    .setMessage("This removes every CloudStream plugin and Zangetsu script. Your repositories stay, APK extensions are not touched.")
                    .setPositiveButton("Uninstall all") { _, _ ->
                        CsPluginManager.getInstalled(this).forEach { CsPluginManager.uninstallPlugin(this, it.internalName) }
                        ZangetsuManager.getInstalled(this).forEach { ZangetsuManager.uninstall(this, it.id) }
                        render()
                    }.setNegativeButton("Cancel", null).show()
            })
        }
    }

    private fun csDialog(p: CsPluginManager.CsInstalledPlugin, up: NekomoRepo.Update?) {
        val enabled = CsPluginManager.isPluginEnabled(this, p.internalName)
        val options = ArrayList<Pair<String, () -> Unit>>()
        options += "Details" to { csDetails(p) }
        options += (if (enabled) "Disable" else "Enable") to { CsPluginManager.setPluginEnabled(this, p.internalName, !enabled); render() }
        val apis = CsPluginManager.loaded().firstOrNull { it.installed.internalName == p.internalName }?.apis.orEmpty()
        if (apis.size > 1 || apis.isNotEmpty()) options += "Providers in this plugin…" to {
            val names = apis.map { it.name }.toTypedArray()
            val checked = BooleanArray(names.size) { CsPluginManager.isProviderEnabled(this, names[it]) }
            AlertDialog.Builder(this).setTitle("${p.name} providers")
                .setMultiChoiceItems(names, checked) { _, i, on -> checked[i] = on }
                .setPositiveButton("Save") { _, _ -> names.forEachIndexed { i, n -> CsPluginManager.setProviderEnabled(this, n, checked[i]) }; render() }
                .setNegativeButton("Cancel", null).show()
        }
        if (up != null) options += "Update to ${up.to}" to { installUpdate(listOf(up)) }
        options += "Uninstall" to { CsPluginManager.uninstallPlugin(this, p.internalName); render() }
        AlertDialog.Builder(this).setTitle(p.name).setItems(options.map { it.first }.toTypedArray()) { _, i -> options[i].second() }
            .setNegativeButton("Close", null).show()
    }

    private fun csDetails(p: CsPluginManager.CsInstalledPlugin) {
        val (d, _) = busy("Loading details…")
        thread {
            val site = p.repoUrl?.let { CsPluginManager.fetchRepoPlugins(it) }?.firstOrNull { it.internalName == p.internalName }
            runOnUiThread {
                d.dismiss()
                AlertDialog.Builder(this).setTitle(p.name).setMessage(
                    listOfNotNull(
                        site?.description?.takeIf { it.isNotBlank() },
                        "Version: ${p.version}" + (site?.let { " (latest ${it.version})" } ?: ""),
                        "Type: CloudStream plugin (.cs3), not an APK",
                        site?.authors?.takeIf { it.isNotEmpty() }?.let { "Authors: ${it.joinToString()}" },
                        site?.tvTypes?.takeIf { it.isNotEmpty() }?.let { "Content: ${it.joinToString()}" },
                        site?.language?.let { "Language: $it" },
                        "Compatibility: " + if (CsPluginManager.isLoaded(p.filePath)) "loaded and working" else "not loaded (disabled or incompatible)",
                        site?.let { if (it.status == 0) "Reported down by its repository" else null },
                        "Repository: ${p.repoUrl ?: "unknown"}"
                    ).joinToString("\n\n")
                ).setPositiveButton("OK", null).show()
            }
        }
    }

    private fun zgDialog(s: ZangetsuManager.ZgInstalled, up: NekomoRepo.Update?) {
        val enabled = ZangetsuManager.isEnabled(this, s.id)
        val options = ArrayList<Pair<String, () -> Unit>>()
        options += "Details" to {
            AlertDialog.Builder(this).setTitle(s.name).setMessage(
                "Version: ${s.version}\nType: Zangetsu script (.js), not an APK\nContent: ${s.type}\nLanguage: ${s.lang}\n" +
                        "Compatibility: ${if (ZangetsuManager.scriptOf(s) != null) "script present" else "script file missing"}\nRepository: ${s.repoUrl ?: "unknown"}"
            ).setPositiveButton("OK", null).show()
        }
        options += (if (enabled) "Disable" else "Enable") to { ZangetsuManager.setEnabled(this, s.id, !enabled); render() }
        if (up != null) options += "Update to ${up.to}" to { installUpdate(listOf(up)) }
        options += "Uninstall" to { ZangetsuManager.uninstall(this, s.id); render() }
        AlertDialog.Builder(this).setTitle(s.name).setItems(options.map { it.first }.toTypedArray()) { _, i -> options[i].second() }
            .setNegativeButton("Close", null).show()
    }

    // ---------------------------------------------------------------------------------------------------------
    // Metadata APIs (built in)
    // ---------------------------------------------------------------------------------------------------------

    private val apiOpeners = HashMap<String, () -> Unit>()

    private fun apisSection(r: LinearLayout) {
        r.addView(b.heading("Metadata APIs (built in)"))
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val apis = listOf(
            Triple("anilist", "AniList", "Main catalogue. Always on."),
            Triple("kitsu", "Kitsu", "Backup metadata and episode lists."),
            Triple("jikan", "MyAnimeList (Jikan)", "Backup metadata."),
            Triple("tvmaze", "TVMaze", "TV shows."),
            Triple("tmdb", "TMDB", "Movies and TV. Needs your own API key.")
        )
        r.addView(b.card(*apis.map { (id, name, desc) ->
            val on = id == "anilist" || prefs.getBoolean("api_enabled_$id", true)
            val keyNote = if (id == "tmdb") (if (com.lagradost.shiro.utils.meta.MetaHub.tmdbKey(this).isEmpty()) " · no key set" else " · key set") else ""
            val open: () -> Unit = {
                val options = ArrayList<Pair<String, () -> Unit>>()
                if (id == "anilist") options += "Built in (main catalogue, can't be removed)" to { }
                if (id != "anilist") options += (if (on) "Disable" else "Enable") to { prefs.edit().putBoolean("api_enabled_$id", !on).apply(); render() }
                if (id == "tmdb") options += "Set API key…" to {
                    val input = EditText(this).apply { setText(com.lagradost.shiro.utils.meta.MetaHub.tmdbKey(this@ExtensionManagerActivity)); setSingleLine() }
                    AlertDialog.Builder(this).setTitle("TMDB API key").setMessage("Free key from themoviedb.org/settings/api. Stored only on this device.")
                        .setView(input).setPositiveButton("Save") { _, _ -> prefs.edit().putString("tmdb_api_key", input.text.toString().trim()).apply(); ApiHealth.reset(); render() }
                        .setNegativeButton("Cancel", null).show()
                }
                if (id == "tmdb" && com.lagradost.shiro.utils.meta.MetaHub.tmdbKey(this).isNotEmpty()) options += "Remove API key" to {
                    prefs.edit().remove("tmdb_api_key").apply(); ApiHealth.reset(); render()
                }
                options += "Reset status" to { ApiHealth.reset(); render() }
                AlertDialog.Builder(this).setTitle(name).setMessage("$desc\n\nStatus: ${if (on) ApiHealth.status(id) else "Disabled"}$keyNote")
                    .setItems(options.map { it.first }.toTypedArray()) { _, i -> options[i].second() }
                    .setNegativeButton("Close", null).show()
            }
            apiOpeners[id] = open
            b.row(name, "$desc ${if (on) ApiHealth.status(id) else "Disabled"}$keyNote") { open() }
        }.toTypedArray()))
    }

    /** nekomo-repo://source?repo=<url>&id=<plugin id>&type=cloudstream|zangetsu|aniyomi|mihon: show a source and install it after confirmation. */
    private fun startSource(repo: String?, id: String?, type: String?) {
        if (repo.isNullOrBlank() || !repo.startsWith("http") || id.isNullOrBlank()) { toast("That link is incomplete."); return }
        val t = (type ?: "").lowercase()
        if (t == "aniyomi" || t == "mihon") {
            // APK extensions are never installed by Nekomo: only the repository address is saved, you install the APK yourself
            val (loading, _) = busy("Looking up the repository…")
            thread {
                val name = host(repo)
                runOnUiThread { loading.dismiss(); showForeign(repo, "APK", name) }
            }
            return
        }
        val (loading, _) = busy("Looking up the source…")
        thread {
            data class Found(val name: String, val version: String, val desc: String, val nsfw: Boolean)
            val found: Found? = try {
                if (t == "zangetsu") ZangetsuManager.fetchManifest(repo)?.sources?.firstOrNull { it.id == id }
                    ?.let { Found(it.name, it.version, "${it.type} · ${it.lang}", it.nsfw) }
                else CsPluginManager.fetchRepoPlugins(repo).firstOrNull { it.internalName == id || it.name == id }
                    ?.let { Found(it.displayName, "v${it.version}", listOfNotNull(it.description, it.tvTypes?.joinToString()).joinToString("\n"), "nsfw" in it.tvTypes.orEmpty().map { x -> x.lowercase() }) }
            } catch (e: Exception) { null }
            runOnUiThread {
                loading.dismiss()
                if (found == null) {
                    AlertDialog.Builder(this).setTitle("Source not found").setMessage("That source isn't offered by the repository right now.").setPositiveButton("OK", null).show()
                    return@runOnUiThread
                }
                if (found.nsfw && !com.lagradost.shiro.utils.ContentPrefs.nsfw(this)) {
                    AlertDialog.Builder(this).setTitle("18+ source").setMessage("${found.name} is an 18+ source. Turn on the 18+ option in Settings > General first.").setPositiveButton("OK", null).show()
                    return@runOnUiThread
                }
                AlertDialog.Builder(this).setTitle("Install ${found.name}?")
                    .setMessage("${found.version}\n${found.desc}\n\nFrom: $repo\n\nSources are third-party code that runs inside this app. Nekomo does not control them. Only continue if you trust this repository.")
                    .setPositiveButton("Install") { _, _ -> installOne(repo, id, t, found.name) }
                    .setNegativeButton("Cancel", null).show()
            }
        }
    }

    private fun installOne(repo: String, id: String, type: String, name: String) {
        val (dialog, label) = busy("Installing $name…")
        thread {
            val result: Pair<Boolean, String> = try {
                if (type == "zangetsu") {
                    ZangetsuManager.addRepo(this, ZangetsuManager.ZgRepo(repo, null))
                    val src = ZangetsuManager.fetchManifest(repo)?.sources?.firstOrNull { it.id == id }
                    if (src == null) false to "No longer offered by the repository" else ZangetsuManager.install(this, repo, src)
                } else {
                    CsPluginManager.addRepo(this, CsPluginManager.CsRepo(repo, null))
                    val p = CsPluginManager.fetchRepoPlugins(repo).firstOrNull { it.internalName == id || it.name == id }
                    if (p == null) false to "No longer offered by the repository" else CsPluginManager.installPlugin(this, repo, p).let { it.success to it.message }
                }
            } catch (e: Exception) { false to (e.message ?: "Error") }
            runOnUiThread {
                dialog.dismiss()
                render()
                AlertDialog.Builder(this).setTitle(if (result.first) "Installed" else "Couldn't install").setMessage(if (result.first) "$name is ready to use." else result.second)
                    .setPositiveButton("OK", null).show()
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // APK extensions: manual only
    // ---------------------------------------------------------------------------------------------------------

    private fun apkSection(r: LinearLayout) {
        r.addView(b.heading("Aniyomi & Mihon extensions (APK, you install and update these)"))
        val rows = ArrayList<android.view.View>()
        AniyomiManager.getInstalled(this).forEach { e ->
            val on = AniyomiManager.isEnabled(this, e.pkg)
            rows += b.row("${e.name}  v${e.version}", "Aniyomi · ${e.lang} · ${if (on) "enabled" else "disabled"}") {
                AlertDialog.Builder(this).setTitle(e.name).setItems(arrayOf(if (on) "Disable" else "Enable", "Remove")) { _, i ->
                    if (i == 0) AniyomiManager.setEnabled(this, e.pkg, !on) else AniyomiManager.uninstall(this, e.pkg)
                    render()
                }.setNegativeButton("Close", null).show()
            }
        }
        MihonManager.getInstalled(this).forEach { e ->
            val on = MihonManager.isEnabled(this, e.pkg)
            rows += b.row("${e.name}  v${e.version}", "Mihon · ${e.lang} · ${if (on) "enabled" else "disabled"}") {
                AlertDialog.Builder(this).setTitle(e.name).setItems(arrayOf(if (on) "Disable" else "Enable", "Remove")) { _, i ->
                    if (i == 0) MihonManager.setEnabled(this, e.pkg, !on) else MihonManager.uninstall(this, e.pkg)
                    render()
                }.setNegativeButton("Close", null).show()
            }
        }
        r.addView(b.card(
            b.text("Aniyomi and Mihon extensions are APK-based. Nekomo never downloads, installs or updates them automatically and the Nekomo repository does not include them. Add repositories and install or update them yourself.", 13f, dim = true),
            *rows.toTypedArray()
        ))
        r.addView(b.primaryButton("Manual repositories and APK extensions") { startActivity(Intent(this, ExtensionsActivity::class.java)) })
        r.addView(b.primaryButton("Sources guide (website)") { b.open("https://nekomoapp.netlify.app/sources") })
        val nexPkg = listOf("net.nekomo.nex", "net.nekomo.nex.debug").firstOrNull { p -> runCatching { packageManager.getPackageInfo(p, 0) }.isSuccess }
        r.addView(b.primaryButton(if (nexPkg != null) "Open NEX (companion manager)" else "Get NEX (companion manager)") {
            val launch = nexPkg?.let { packageManager.getLaunchIntentForPackage(it) }
            if (launch != null) startActivity(launch) else b.open("https://github.com/Nekomo-App/NEX/releases")
        })
    }
}
