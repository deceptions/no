package com.lagradost.shiro.ui.settings

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.jaredrummler.cyanea.app.CyaneaAppCompatActivity
import com.lagradost.shiro.R
import com.lagradost.shiro.utils.cs3.CsPluginManager
import com.lagradost.shiro.utils.aniyomi.AniyomiManager
import com.lagradost.shiro.utils.mihon.MihonManager
import com.lagradost.shiro.utils.zg.ZangetsuManager
import kotlin.concurrent.thread

/**
 * CloudStream extension management: add/remove repositories, browse their
 * plugin catalogues, install/update/enable/disable/uninstall .cs3 extensions.
 */
class ExtensionsActivity : CyaneaAppCompatActivity() {

    private lateinit var repoList: LinearLayout
    private lateinit var pluginList: LinearLayout
    private lateinit var statusText: TextView

    override fun onResume() {
        super.onResume()
        // The Extensions manager shares these stores; pick up anything it installed, updated or removed
        if (::repoList.isInitialized) {
            refreshRepos()
            refreshPlugins()
            refreshZangetsu()
            refreshAniyomi()
            refreshMihon()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_extensions)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = "Extensions"

        repoList = findViewById(R.id.repo_list)
        pluginList = findViewById(R.id.plugin_list)
        statusText = findViewById(R.id.extension_status)

        findViewById<Button>(R.id.add_repo_button).setOnClickListener {
            val input = findViewById<EditText>(R.id.repo_url_input)
            val raw = input.text.toString()
            if (raw.isBlank()) return@setOnClickListener
            confirmRisk { addRepoFromInput(raw, input) }
        }

        refreshRepos()
        refreshPlugins()
        refreshZangetsu()
        refreshAniyomi()
        refreshMihon()
    }

    // ---------- Aniyomi extensions ----------

    private fun refreshAniyomi() {
        // One combined section for Aniyomi (anime) and Mihon (manga): they share an extension format
        val box = findViewById<LinearLayout>(R.id.aniyomi_container)
        box.removeAllViews()
        val ay = AniyomiManager.getRepos(this)
        val mh = MihonManager.getRepos(this)
        val urls = (ay.map { it.url } + mh.map { it.url }).distinct()
        if (urls.isEmpty()) box.addView(rowText("No Aniyomi or Mihon repositories added yet"))
        for (url in urls) {
            val a = ay.firstOrNull { it.url == url }
            val m = mh.firstOrNull { it.url == url }
            val kind = if (a != null && m != null) "Aniyomi + Mihon" else if (a != null) "Aniyomi" else "Mihon"
            val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            row.addView(rowText("${a?.name ?: m?.name ?: url}  \u2022  $kind"))
            val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            if (a != null) buttons.addView(rowButton(if (m != null) "Browse anime" else "Browse") { browseAniyomi(url) })
            if (m != null) buttons.addView(rowButton(if (a != null) "Browse manga" else "Browse") { browseMihon(url) })
            buttons.addView(rowButton("Remove") {
                AniyomiManager.removeRepo(this, url)
                MihonManager.removeRepo(this, url)
                refreshAniyomi()
            })
            row.addView(buttons)
            box.addView(rowCard(row))
        }

        val input = EditText(this).apply {
            hint = "Aniyomi or Mihon repository URL (index.min.json or index.pb)"
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
            maxLines = 1
        }
        box.addView(input)
        box.addView(rowButton("Add repository") {
            val raw = input.text.toString().trim()
            if (!raw.startsWith("http")) {
                toast("Enter the full https URL of the repository")
                return@rowButton
            }
            confirmRisk {
                setStatus("Reading repository\u2026")
                thread {
                    val ayUrl = AniyomiManager.normaliseRepoUrl(raw)
                    val mhUrl = MihonManager.normaliseRepoUrl(raw)
                    val ayIdx = AniyomiManager.fetchIndex(ayUrl)
                    val mhIdx = if (ayIdx == null || ayIdx.none { !it.pkg.contains("animeextension") }) MihonManager.fetchIndex(mhUrl) else null
                    val anime = ayIdx?.count { it.pkg.contains("animeextension") } ?: 0
                    val manga = maxOf(ayIdx?.count { !it.pkg.contains("animeextension") } ?: 0, mhIdx?.count { !it.pkg.contains("animeextension") } ?: 0)
                    runOnUiThread {
                        setStatus("")
                        if (anime + manga == 0) {
                            toast("Not a valid Aniyomi or Mihon repository")
                            return@runOnUiThread
                        }
                        if (anime > 0) AniyomiManager.addRepo(this, AniyomiManager.AyRepo(ayUrl, repoLabel(ayUrl)))
                        if (manga > 0) MihonManager.addRepo(this, MihonManager.MhRepo(mhUrl, repoLabel(mhUrl)))
                        toast("Added: $anime anime and $manga manga extension(s) available")
                        refreshAniyomi()
                    }
                }
            }
        })

        val ayInst = AniyomiManager.getInstalled(this)
        val mhInst = MihonManager.getInstalled(this)
        box.addView(rowText(if (ayInst.isEmpty() && mhInst.isEmpty()) "No Aniyomi or Mihon extensions installed" else "Installed extensions"))
        for (ext in ayInst) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            row.addView(rowText("${ext.name.removePrefix("Aniyomi: ")}  v${ext.version}  \u2022  ${ext.lang}  \u2022  Aniyomi"))
            val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            buttons.addView(Switch(this).apply {
                isChecked = AniyomiManager.isEnabled(this@ExtensionsActivity, ext.pkg)
                text = "Enabled"
                setOnCheckedChangeListener { _, checked -> AniyomiManager.setEnabled(this@ExtensionsActivity, ext.pkg, checked) }
            })
            buttons.addView(rowButton("Report") { reportExtension(ext.repoUrl) })
            buttons.addView(rowButton("Uninstall") {
                AniyomiManager.uninstall(this, ext.pkg)
                refreshAniyomi()
            })
            row.addView(buttons)
            box.addView(rowCard(row))
        }
        for (ext in mhInst) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            row.addView(rowText("${ext.name.removePrefix("Mihon: ")}  v${ext.version}  \u2022  ${ext.lang}  \u2022  Mihon"))
            val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            buttons.addView(Switch(this).apply {
                isChecked = MihonManager.isEnabled(this@ExtensionsActivity, ext.pkg)
                text = "Enabled"
                setOnCheckedChangeListener { _, checked -> MihonManager.setEnabled(this@ExtensionsActivity, ext.pkg, checked) }
            })
            buttons.addView(rowButton("Report") { reportExtension(ext.repoUrl) })
            buttons.addView(rowButton("Uninstall") {
                MihonManager.uninstall(this, ext.pkg)
                refreshAniyomi()
            })
            row.addView(buttons)
            box.addView(rowCard(row))
        }
    }

    private fun browseAniyomi(repoUrl: String) {
        setStatus("Fetching extensions…")
        thread {
            val index = AniyomiManager.fetchIndex(repoUrl)
            runOnUiThread {
                setStatus("")
                val entries = index.orEmpty().filter { com.lagradost.shiro.utils.ContentPrefs.nsfw(this) || it.nsfw != 1 }.sortedBy { it.name }
                if (entries.isEmpty()) {
                    toast("No extensions found (or repository is offline)")
                    return@runOnUiThread
                }
                val installed = AniyomiManager.getInstalled(this)
                val labels = entries.map { e ->
                    val inst = installed.firstOrNull { it.pkg == e.pkg }
                    val marker = when {
                        inst == null -> ""
                        inst.version != e.version -> "  [update]"
                        else -> "  [installed]"
                    }
                    "${e.name.removePrefix("Aniyomi: ")} (${e.lang})${if (e.nsfw == 1) " 18+" else ""}$marker"
                }.toTypedArray()
                searchableListDialog("Aniyomi extensions — ${repoLabel(repoUrl)}", labels) { which ->
                    confirmRisk {
                        setStatus("Installing ${entries[which].name}…")
                        thread {
                            val (ok, msg) = AniyomiManager.install(this, repoUrl, entries[which])
                            runOnUiThread {
                                setStatus(msg)
                                if (ok) refreshAniyomi()
                            }
                        }
                    }
                }
            }
        }
    }

    // ---------- Mihon extensions ----------

    private fun refreshMihon() = refreshAniyomi()

    private fun browseMihon(repoUrl: String) {
        setStatus("Fetching extensions…")
        thread {
            val index = MihonManager.fetchIndex(repoUrl)
            runOnUiThread {
                setStatus("")
                val entries = index.orEmpty().filter { com.lagradost.shiro.utils.ContentPrefs.nsfw(this) || it.nsfw != 1 }.sortedBy { it.name }
                if (entries.isEmpty()) {
                    toast("No extensions found (or repository is offline)")
                    return@runOnUiThread
                }
                val installed = MihonManager.getInstalled(this)
                val labels = entries.map { e ->
                    val inst = installed.firstOrNull { it.pkg == e.pkg }
                    val marker = when {
                        inst == null -> ""
                        inst.version != e.version -> "  [update]"
                        else -> "  [installed]"
                    }
                    "${e.name.removePrefix("Mihon: ")} (${e.lang})${if (e.nsfw == 1) " 18+" else ""}$marker"
                }.toTypedArray()
                searchableListDialog("Mihon extensions — ${repoLabel(repoUrl)}", labels) { which ->
                    confirmRisk {
                        setStatus("Installing ${entries[which].name}…")
                        thread {
                            val (ok, msg) = MihonManager.install(this, repoUrl, entries[which])
                            runOnUiThread {
                                setStatus(msg)
                                if (ok) refreshMihon()
                            }
                        }
                    }
                }
            }
        }
    }

    // ---------- Zangetsu sources ----------

    private fun refreshZangetsu() {
        val box = findViewById<LinearLayout>(R.id.zangetsu_container)
        box.removeAllViews()
        val repos = ZangetsuManager.getRepos(this)
        if (repos.isEmpty()) box.addView(rowText("No Zangetsu repositories added yet"))
        for (repo in repos) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            row.addView(rowText("📦  " + (repo.name ?: repo.url)))
            val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            buttons.addView(rowButton("Browse") { browseZangetsu(repo.url) })
            buttons.addView(rowButton("Remove") {
                ZangetsuManager.removeRepo(this, repo.url)
                refreshZangetsu()
            })
            row.addView(buttons)
            box.addView(rowCard(row))
        }

        val input = EditText(this).apply {
            hint = "Zangetsu repository URL (index.json)"
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
            maxLines = 1
        }
        box.addView(input)
        box.addView(rowButton("Add Zangetsu repository") {
            val url = input.text.toString().trim()
            if (!url.startsWith("http")) {
                toast("Enter the full https URL of the repository's index.json")
                return@rowButton
            }
            confirmRisk {
                setStatus("Reading repository…")
                thread {
                    val manifest = ZangetsuManager.fetchManifest(url)
                    runOnUiThread {
                        if (manifest == null) {
                            setStatus("Could not read a Zangetsu manifest at that URL")
                            toast("Not a valid Zangetsu repository")
                        } else {
                            ZangetsuManager.addRepo(this, ZangetsuManager.ZgRepo(url, manifest.name))
                            setStatus("")
                            refreshZangetsu()
                        }
                    }
                }
            }
        })

        val installed = ZangetsuManager.getInstalled(this)
        box.addView(rowText(if (installed.isEmpty()) "No Zangetsu sources installed" else "Installed sources"))
        if (installed.isNotEmpty()) box.addView(rowButton("Update all sources") {
            setStatus("Updating Zangetsu sources…")
            thread {
                val n = ZangetsuManager.updateAll(this)
                runOnUiThread {
                    setStatus("Updated $n source(s)")
                    refreshZangetsu()
                }
            }
        })
        for (src in installed) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            row.addView(rowText("🧩  ${src.name}"))
            row.addView(rowMeta("v${src.version}  •  ${src.type} / ${src.lang}"))
            val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            buttons.addView(Switch(this).apply {
                isChecked = ZangetsuManager.isEnabled(this@ExtensionsActivity, src.id)
                text = "Enabled"
                setOnCheckedChangeListener { _, checked -> ZangetsuManager.setEnabled(this@ExtensionsActivity, src.id, checked) }
            })
            buttons.addView(rowButton("Report") { reportExtension(src.repoUrl) })
            buttons.addView(rowButton("Uninstall") {
                ZangetsuManager.uninstall(this, src.id)
                refreshZangetsu()
            })
            row.addView(buttons)
            box.addView(rowCard(row))
        }
    }

    private fun browseZangetsu(repoUrl: String) {
        setStatus("Fetching sources…")
        thread {
            val manifest = ZangetsuManager.fetchManifest(repoUrl)
            runOnUiThread {
                setStatus("")
                val sources = manifest?.sources.orEmpty().filter { (com.lagradost.shiro.utils.ContentPrefs.nsfw(this) || !it.nsfw) && (com.lagradost.shiro.utils.ContentPrefs.nonAnime(this) || it.type != "movie") }
                if (sources.isEmpty()) {
                    toast("No sources found (or repository is offline)")
                    return@runOnUiThread
                }
                val installed = ZangetsuManager.getInstalled(this)
                val labels = sources.map { s ->
                    val inst = installed.firstOrNull { it.id == s.id }
                    val marker = when {
                        inst == null -> ""
                        inst.version != s.version -> "  [update ${inst.version}→${s.version}]"
                        else -> "  [installed]"
                    }
                    "${s.name} (${s.type}, ${s.lang})$marker"
                }.toTypedArray()
                searchableListDialog("Zangetsu sources — ${repoLabel(repoUrl)}", labels) { which ->
                    confirmRisk {
                        setStatus("Installing ${sources[which].name}…")
                        thread {
                            val (ok, msg) = ZangetsuManager.install(this, repoUrl, sources[which])
                            runOnUiThread {
                                setStatus(msg)
                                if (ok) refreshZangetsu()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun addRepoFromInput(raw: String, input: EditText) {
        // A private repository code opens the manager, which shows what it will install first
        if (com.lagradost.shiro.utils.repo.NekomoRepo.isNickname(raw)) {
            input.text.clear()
            startActivity(android.content.Intent(this, ExtensionManagerActivity::class.java).putExtra("add", raw))
            return
        }
        run {
            setStatus("Resolving repository…")
            thread {
                val url = CsPluginManager.parseRepoUrl(raw)
                if (url == null) {
                    setStatus("Could not parse repository: $raw")
                    toast("Invalid repository")
                    return@thread
                }
                val repo = CsPluginManager.fetchRepository(url)
                runOnUiThread {
                    CsPluginManager.addRepo(this, CsPluginManager.CsRepo(url, repo?.name))
                    input.text.clear()
                    setStatus("")
                    refreshRepos()
                }
            }
        }

    }

    // ---------- repositories ----------

    private fun refreshRepos() {
        repoList.removeAllViews()
        val repos = CsPluginManager.getRepos(this)
        if (repos.isEmpty()) {
            repoList.addView(rowText("No repositories added yet"))
        }
        for (repo in repos) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(rowText("📦  " + (repo.name ?: repo.url)))
            buttons.addView(rowButton("Browse") { browseRepo(repo.url) })
            buttons.addView(rowButton("Remove") {
                CsPluginManager.removeRepo(this, repo.url)
                refreshRepos()
            })
            row.addView(buttons)
            repoList.addView(rowCard(row))
        }
    }

    private fun browseRepo(url: String) {
        setStatus("Fetching plugins…")
        thread {
            val all = CsPluginManager.fetchRepoPlugins(url)
            val plugins = all.filter { CsPluginManager.allowed(this, it) }
            runOnUiThread {
                setStatus("")
                if (plugins.isEmpty()) {
                    toast(
                        if (all.isEmpty()) "No plugins found (or repository is offline)"
                        else "${all.size} extension(s) are hidden by your content filters. Enable \"Show movies and TV shows\" / \"Show 18+\" in Settings → General to see them."
                    )
                    return@runOnUiThread
                }
                val installed = CsPluginManager.getInstalled(this)
                val labels = plugins.map { p ->
                    val inst = installed.firstOrNull { it.internalName == p.internalName }
                    val marker = when {
                        inst == null -> ""
                        inst.version < p.version -> "  [update v${inst.version}→v${p.version}]"
                        else -> "  [installed]"
                    }
                    "${p.displayName} v${p.version}$marker"
                }.toTypedArray()
                searchableListDialog("Extensions — ${repoLabel(url)}", labels) { which ->
                    confirmRisk { installPlugin(url, plugins[which]) }
                }
            }
        }
    }

    private fun updateAll() {
        val installed = CsPluginManager.getInstalled(this)
        setStatus("Updating ${installed.size} extension(s)…")
        thread {
            var ok = 0
            installed.forEach { if (CsPluginManager.updatePlugin(this, it.internalName).success) ok++ }
            runOnUiThread {
                setStatus("Updated $ok of ${installed.size} extensions")
                refreshPlugins()
            }
        }
    }

    /** One-time warning: extensions are third-party code running inside the app. */
    private fun confirmRisk(action: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle("Only install extensions you trust")
            .setMessage(
                "Extensions are third-party code that runs inside this app with the same access " +
                        "it has (network, your stored accounts and settings). Nekomo does not review them. " +
                        "Only add repositories and install extensions from sources you trust."
            )
            .setPositiveButton("Continue") { _, _ -> action() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun installPlugin(repoUrl: String, plugin: CsPluginManager.CsSitePlugin) {
        if (plugin.fileHash == null) {
            toast("Warning: this repository provides no file hash, so the download can't be verified")
        }
        setStatus("Installing ${plugin.displayName}…")
        thread {
            val result = CsPluginManager.installPlugin(this, repoUrl, plugin)
            runOnUiThread {
                setStatus(
                    if (result.success)
                        "${result.message} (${result.providerCount} provider(s))"
                    else result.message
                )
                refreshPlugins()
            }
        }
    }

    // ---------- installed plugins ----------

    private fun refreshPlugins() {
        pluginList.removeAllViews()
        val installed = CsPluginManager.getInstalled(this)
        if (installed.isEmpty()) {
            pluginList.addView(rowText("No extensions installed"))
        } else {
            pluginList.addView(rowButton("Update all") { updateAll() })
        }
        for (plugin in installed) {
            val providers = CsPluginManager.loaded()
                .firstOrNull { it.installed.internalName == plugin.internalName }
                ?.apis?.size ?: 0
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            row.addView(rowText("🧩  ${plugin.name}"))
            row.addView(rowMeta("v${plugin.version}  •  $providers provider(s)"))
            val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

            val toggle = Switch(this).apply {
                isChecked = CsPluginManager.isPluginEnabled(this@ExtensionsActivity, plugin.internalName)
                text = "Enabled"
                setOnCheckedChangeListener { _, checked ->
                    CsPluginManager.setPluginEnabled(this@ExtensionsActivity, plugin.internalName, checked)
                    refreshPlugins()
                }
            }
            buttons.addView(toggle)
            buttons.addView(rowButton("Update") {
                setStatus("Updating ${plugin.name}…")
                thread {
                    val result = CsPluginManager.updatePlugin(this@ExtensionsActivity, plugin.internalName)
                    runOnUiThread {
                        setStatus(result.message)
                        refreshPlugins()
                    }
                }
            })
            buttons.addView(rowButton("Report") { reportExtension(plugin.repoUrl) })
            buttons.addView(rowButton("Uninstall") {
                CsPluginManager.uninstallPlugin(this, plugin.internalName)
                refreshPlugins()
            })
            row.addView(buttons)
            CsPluginManager.loaded()
                .firstOrNull { it.installed.internalName == plugin.internalName }
                ?.apis?.forEach { api ->
                    row.addView(Switch(this).apply {
                        text = "  ${api.name}"
                        isChecked = CsPluginManager.isProviderEnabled(this@ExtensionsActivity, api.name)
                        setOnCheckedChangeListener { _, checked ->
                            CsPluginManager.setProviderEnabled(this@ExtensionsActivity, api.name, checked)
                        }
                    })
                }
            pluginList.addView(rowCard(row))
        }
    }

    /** "owner/repo" for GitHub raw URLs, otherwise the host. */
    private fun repoLabel(url: String): String {
        Regex("""https?://raw\.githubusercontent\.com/([^/]+)/([^/]+)/""").find(url)?.let { return "${it.groupValues[1]}/${it.groupValues[2]}" }
        return Regex("""^https?://([^/]+)""").find(url)?.groupValues?.get(1) ?: url
    }

    /** Opens the issue tracker of the repository an extension came from (the author, not Nekomo, maintains it). */
    private fun reportExtension(repoUrl: String?) {
        val url = repoUrl ?: run { toast("No repository recorded for this extension"); return }
        val gh = Regex("""https?://raw\.githubusercontent\.com/([^/]+)/([^/]+)/""").find(url)
        val target = if (gh != null) "https://github.com/${gh.groupValues[1]}/${gh.groupValues[2]}/issues"
        else Regex("""^(https?://[^/]+)""").find(url)?.groupValues?.get(1) ?: url
        try {
            startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(target)))
        } catch (e: Exception) {
            toast("No browser available")
        }
    }

    // ---------- helpers ----------

    private fun rowText(text: String, weight: Float = 0f): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 15f
            setPadding(0, 16, 0, 16)
            layoutParams = LinearLayout.LayoutParams(
                if (weight > 0f) 0 else LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT, weight
            )
        }

    /** Wraps a repo/extension row in a subtle card: keeps the same vertical flow, adds grouping. */
    private fun rowCard(content: View): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.outline_background)
        setPadding(20, 10, 20, 10)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = 14 }
        addView(content)
    }

    /** Small dimmed meta line under a row's title (name  •  version  •  language style info). */
    private fun rowMeta(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 12f
        alpha = 0.62f
        setPadding(0, 0, 0, 10)
    }

    /**
     * Browse dialog with a live search filter — repository catalogues can hold thousands of
     * entries, so the flat pick-list gets an EditText on top that narrows the list as you type.
     */
    private fun searchableListDialog(
        title: String,
        labels: Array<String>,
        onPick: (Int) -> Unit
    ) {
        val input = EditText(this).apply {
            hint = "Search…"
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            setPadding(24, 12, 24, 12)
        }
        val listView = android.widget.ListView(this)
        val adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_list_item_1, labels.toMutableList())
        listView.adapter = adapter
        listView.onItemClickListener = android.widget.AdapterView.OnItemClickListener { _, _, pos, _ ->
            val label = adapter.getItem(pos) ?: return@OnItemClickListener
            onPick(labels.indexOf(label))
        }
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val q = s?.toString()?.lowercase().orEmpty()
                adapter.clear()
                adapter.addAll(labels.filter { it.lowercase().contains(q) })
            }
        })
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(input)
            addView(listView)
        }
        val dlg = AlertDialog.Builder(this).setTitle(title).setView(box).create()
        // Back/cancel behaviour is unchanged; the list is D-pad friendly on TV.
        dlg.show()
    }

    private fun rowButton(label: String, onClick: (View) -> Unit): Button =
        androidx.appcompat.widget.AppCompatButton(this).apply {
            text = label
            // Explicit tint: the theme-derived default of a programmatic Button can render white-on-white
            val tv = android.util.TypedValue()
            val primary = if (theme.resolveAttribute(androidx.appcompat.R.attr.colorAccent, tv, true)) tv.data else 0xFFD01010.toInt()
            backgroundTintList = android.content.res.ColorStateList.valueOf(primary)
            setTextColor(android.graphics.Color.WHITE)
            setOnClickListener(onClick)
        }

    private fun setStatus(msg: String) {
        runOnUiThread { statusText.text = msg }
    }

    private fun toast(msg: String) {
        runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }
}
