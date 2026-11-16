package com.lagradost.shiro.utils.cs3

import DataStore.getKey
import DataStore.setKey
import android.content.Context
import android.content.res.AssetManager
import android.content.res.Resources
import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.shiro.utils.mvvm.logError
import dalvik.system.PathClassLoader
import java.io.File
import java.lang.ref.WeakReference
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.zip.ZipFile
import kotlin.concurrent.thread

/**
 * CloudStream extension (.cs3) subsystem.
 *
 * CloudStream plugins are zip/apk-like files with a manifest.json in the root
 * ("pluginClassName", "name", "version", "requiresResources") whose classes are
 * compiled against the cloudstream3 API that this app ships inside
 * app/libs/cs3api.jar. Loading mirrors upstream PluginManager: a PathClassLoader
 * over the file, instantiate the BasePlugin/Plugin entry class, then call load()
 * which registers MainAPI providers (and extractors) into APIHolder.
 *
 * Repository index format (matches upstream RepositoryManager):
 *   repo url -> {"name", "description", "manifestVersion", "pluginLists": [url]}
 *   each plugin list url -> SitePlugin[]
 *
 * Everything here runs on whatever thread calls it (callers use thread{}) —
 * plugin load is I/O+classload work so init() defers it off the main thread.
 */
object CsPluginManager {
    private const val TAG = "CsPluginManager"
    private const val EXTENSIONS_FOLDER = "Extensions"
    private const val INSTALLED_KEY = "cs3_installed_plugins"
    private const val REPOS_KEY = "cs3_repositories"
    private const val DISABLED_PLUGINS_KEY = "cs3_disabled_plugins"

    private val mapper: JsonMapper = JsonMapper.builder()
        .addModule(KotlinModule.Builder().build())
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .build()

    // ---------- data models ----------

    data class CsRepository(
        @JsonProperty("iconUrl") val iconUrl: String?,
        @JsonProperty("name") val name: String?,
        @JsonProperty("description") val description: String?,
        @JsonProperty("manifestVersion") val manifestVersion: Int?,
        @JsonProperty("pluginLists") val pluginLists: List<String>?,
    )

    data class CsSitePlugin(
        @JsonProperty("url") val url: String,
        @JsonProperty("status") val status: Int = 1,
        @JsonProperty("version") val version: Int = 1,
        @JsonProperty("apiVersion") val apiVersion: Int = 1,
        @JsonProperty("name") val name: String?,
        @JsonProperty("internalName") val internalName: String?,
        @JsonProperty("authors") val authors: List<String>?,
        @JsonProperty("description") val description: String?,
        @JsonProperty("repositoryUrl") val repositoryUrl: String?,
        @JsonProperty("tvTypes") val tvTypes: List<String>?,
        @JsonProperty("language") val language: String?,
        @JsonProperty("iconUrl") val iconUrl: String?,
        @JsonProperty("fileSize") val fileSize: Long?,
        @JsonProperty("fileHash") val fileHash: String?,
    ) {
        val displayName get() = name ?: internalName ?: url
    }

    data class CsRepo(
        @JsonProperty("url") val url: String,
        @JsonProperty("name") val name: String? = null,
    )

    data class CsInstalledPlugin(
        @JsonProperty("internalName") val internalName: String,
        @JsonProperty("name") val name: String,
        @JsonProperty("version") val version: Int,
        @JsonProperty("filePath") val filePath: String,
        @JsonProperty("sourceUrl") val sourceUrl: String?,
        @JsonProperty("repoUrl") val repoUrl: String?,
        @JsonProperty("fileHash") val fileHash: String? = null,
        @JsonProperty("nsfw") val nsfw: Boolean = false,
    )

    data class LoadedPlugin(
        val plugin: BasePlugin,
        val installed: CsInstalledPlugin,
        val apis: List<MainAPI>,
    )

    // ---------- runtime state ----------

    /** filePath -> loaded plugin */
    private val loadedPlugins = ConcurrentHashMap<String, LoadedPlugin>()

    /** Set to non-null once init() kicked off background loading */
    private var loadLatch = CountDownLatch(0)

    // ---------- context injection ----------

    /**
     * Plugins (and the vendored cloudstream3 classes) pull their Context through
     * CloudStreamApp.Companion.context / AcraApplication, which only their
     * Application would set. Inject ours via reflection once.
     */
    private fun injectAppContext(context: Context) {
        try {
            val cls = Class.forName("com.lagradost.cloudstream3.CloudStreamApp")
            val field = cls.getDeclaredField("_context")
            field.isAccessible = true
            field.set(null, WeakReference(context.applicationContext))
        } catch (t: Throwable) {
            logError(t)
        }
    }

    // ---------- init / loading ----------

    fun init(context: Context) {
        injectAppContext(context)
        disabledProviders(context)
        val latch = CountDownLatch(1)
        loadLatch = latch
        thread(name = "CsPluginLoad") {
            try {
                loadAllInstalled(context.applicationContext)
            } catch (t: Throwable) {
                logError(t)
            } finally {
                latch.countDown()
            }
        }
    }

    /** Block briefly until startup plugin loading finished (call from worker threads only). */
    private fun awaitPlugins() {
        try {
            loadLatch.await(15, java.util.concurrent.TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            // proceed with whatever loaded
        }
    }

    private fun loadAllInstalled(context: Context) {
        for (installed in getInstalled(context)) {
            if (installed.internalName in disabledPlugins(context)) continue
            val file = File(installed.filePath)
            if (!file.exists()) {
                Log.w(TAG, "Plugin file missing: ${installed.filePath}")
                continue
            }
            loadPlugin(context, file, installed)
        }
    }

    /** True if a plugin file at this path is already loaded. */
    fun isLoaded(filePath: String) = loadedPlugins.containsKey(filePath)

    fun loaded(): List<LoadedPlugin> = loadedPlugins.values.toList()

    /** All currently usable providers from loaded + enabled plugins. */
    fun providers(): List<MainAPI> {
        awaitPlugins()
        val enabledFiles = loadedPlugins.values
            .filter { it.installed.internalName !in disabledPluginNames }
            .map { it.installed.filePath }
            .toSet()
        val ctx = com.lagradost.shiro.AcraApplication.context
        val nsfwOk = com.lagradost.shiro.utils.ContentPrefs.nsfw(ctx)
        val nonAnimeOk = com.lagradost.shiro.utils.ContentPrefs.nonAnime(ctx)
        val nsfwFiles = loadedPlugins.values.filter { it.installed.nsfw }.map { it.installed.filePath }.toSet()
        val animeTypes = setOf("Anime", "AnimeMovie", "OVA")
        return try {
            APIHolder.allProviders.filter { api ->
                val types = try { api.supportedTypes.map { it.name }.toSet() } catch (t: Throwable) { emptySet() }
                api.sourcePlugin in enabledFiles && api.name !in disabledProviderNames &&
                        (nsfwOk || (api.sourcePlugin !in nsfwFiles && "NSFW" !in types)) &&
                        (nonAnimeOk || types.any { it in animeTypes })
            }
        } catch (t: Throwable) {
            logError(t)
            emptyList()
        }
    }

    /** True when the plugin is loaded and enabled but all its providers are hidden by the movies/TV or 18+ filters. */
    fun hiddenByFilter(internalName: String): Boolean {
        val lp = loadedPlugins.values.firstOrNull { it.installed.internalName == internalName } ?: return false
        if (lp.apis.isEmpty()) return false
        val ctx = com.lagradost.shiro.AcraApplication.context
        val animeTypes = setOf("Anime", "AnimeMovie", "OVA")
        val nonAnimeOk = com.lagradost.shiro.utils.ContentPrefs.nonAnime(ctx)
        val nsfwOk = com.lagradost.shiro.utils.ContentPrefs.nsfw(ctx)
        return lp.apis.none { api ->
            val types = try { api.supportedTypes.map { it.name }.toSet() } catch (t: Throwable) { emptySet() }
            (nsfwOk || (!lp.installed.nsfw && "NSFW" !in types)) && (nonAnimeOk || types.any { it in animeTypes })
        }
    }

    /** Whether a repository entry passes the user's 18+ / movies-and-shows content filters. */
    fun allowed(context: Context, p: CsSitePlugin): Boolean {
        val types = p.tvTypes.orEmpty().map { it.lowercase() }
        val nsfwOk = com.lagradost.shiro.utils.ContentPrefs.nsfw(context) || "nsfw" !in types
        val animeOk = com.lagradost.shiro.utils.ContentPrefs.nonAnime(context) || types.isEmpty() ||
                types.any { it == "anime" || it == "animemovie" || it == "ova" }
        return nsfwOk && animeOk
    }

    fun findProvider(apiName: String): MainAPI? {
        return providers().firstOrNull { it.name == apiName }
            ?: try {
                APIHolder.getApiFromNameNull(apiName)
                    ?.takeIf { it.sourcePlugin in loadedPlugins.keys }
            } catch (t: Throwable) {
                null
            }
    }

    /**
     * Load a .cs3 file. Returns true on success. Safe to call from any thread.
     * Mirrors upstream: PathClassLoader -> manifest.json -> pluginClassName ->
     * BasePlugin subclass -> load() registers providers into APIHolder.
     */
    @Synchronized
    fun loadPlugin(context: Context, file: File, installed: CsInstalledPlugin): Boolean {
        val filePath = file.absolutePath
        return try {
            file.setReadOnly()

            val manifest = readManifest(file) ?: run {
                Log.e(TAG, "No manifest.json in ${file.name}")
                return false
            }
            val pluginClassName = manifest.pluginClassName ?: run {
                Log.e(TAG, "manifest.json missing pluginClassName in ${file.name}")
                return false
            }

            val loader = PathClassLoader(filePath, context.classLoader)

            @Suppress("UNCHECKED_CAST")
            val pluginClass = loader.loadClass(pluginClassName) as? Class<out BasePlugin>
                ?: throw ClassCastException("$pluginClassName is not a BasePlugin")
            val instance = pluginClass.getDeclaredConstructor().newInstance()
            instance.filename = filePath

            // Legacy Plugin (not BasePlugin) supports embedding its own resources
            if (manifest.requiresResources && instance is Plugin) {
                try {
                    val assets = AssetManager::class.java.getDeclaredConstructor().newInstance()
                    AssetManager::class.java
                        .getMethod("addAssetPath", String::class.java)
                        .invoke(assets, filePath)
                    @Suppress("DEPRECATION")
                    instance.resources = Resources(
                        assets,
                        context.resources.displayMetrics,
                        context.resources.configuration
                    )
                } catch (t: Throwable) {
                    logError(t)
                }
            }

            // load() throws Throwable upstream; a broken plugin must not kill the caller
            if (instance is Plugin) {
                instance.load(context)
            } else {
                instance.load()
            }

            val apis = try {
                APIHolder.allProviders.filter { it.sourcePlugin == filePath }
            } catch (t: Throwable) {
                emptyList()
            }
            for (api in apis) {
                try {
                    api.init()
                } catch (t: Throwable) {
                    logError(t)
                }
            }

            loadedPlugins[filePath] = LoadedPlugin(instance, installed.copy(filePath = filePath), apis)
            Log.i(TAG, "Loaded plugin ${installed.internalName} (${apis.size} providers)")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to load plugin ${file.name}: ${t.message}")
            logError(t)
            false
        }
    }

    @Synchronized
    fun unloadPlugin(context: Context, internalName: String): Boolean {
        val entry = loadedPlugins.entries.firstOrNull { it.value.installed.internalName == internalName }
            ?: return false
        val (path, loaded) = entry
        try {
            loaded.plugin.beforeUnload()
        } catch (t: Throwable) {
            logError(t)
        }
        try {
            // Remove the providers this plugin registered
            for (api in loaded.apis) APIHolder.allProviders.remove(api)
        } catch (t: Throwable) {
            logError(t)
        }
        try {
            @Suppress("UNCHECKED_CAST")
            val extractors = Class.forName("com.lagradost.cloudstream3.utils.ExtractorApiKt")
                .getMethod("getExtractorApis").invoke(null) as MutableList<com.lagradost.cloudstream3.utils.ExtractorApi>
            extractors.removeAll { it.sourcePlugin == path }
        } catch (t: Throwable) {
            logError(t)
        }
        loadedPlugins.remove(path)
        return true
    }

    // ---------- repositories ----------

    fun getRepos(context: Context): List<CsRepo> =
        context.getKey<Array<CsRepo>>(REPOS_KEY)?.toList() ?: emptyList()

    fun addRepo(context: Context, repo: CsRepo) {
        val current = getRepos(context)
        if (current.any { it.url == repo.url }) return
        context.setKey(REPOS_KEY, current + repo)
    }

    fun removeRepo(context: Context, url: String) {
        context.setKey(REPOS_KEY, getRepos(context).filter { it.url != url })
    }

    /**
     * Accepts a repository URL the user pasted: a raw http(s) url or a cloudstreamrepo:// / cs.repo wrapped url.
     * Shortcodes (which would need a lookup against a third-party directory) are intentionally not supported.
     */
    fun parseRepoUrl(input: String): String? {
        val fixed = input.trim()
        val wrapper = Regex("""^(cloudstreamrepo://)|(https://cs\.repo/\??)""")
        return when {
            fixed.contains(Regex("^https?://")) -> fixed
            fixed.contains(wrapper) -> fixed.replace(wrapper, "").let { if (it.contains(Regex("^https?://"))) it else "https://$it" }
            else -> null
        }
    }

    fun fetchRepository(url: String): CsRepository? {
        return try {
            val res = khttp.get(url, timeout = 30.0)
            if (res.statusCode != 200) return null
            mapper.readValue(res.text)
        } catch (t: Throwable) {
            logError(t)
            null
        }
    }

    fun fetchRepoPlugins(repoUrl: String): List<CsSitePlugin> {
        val repo = fetchRepository(repoUrl) ?: return emptyList()
        val lists = repo.pluginLists ?: return emptyList()
        return lists.flatMap { listUrl ->
            try {
                val res = khttp.get(listUrl, timeout = 30.0)
                if (res.statusCode != 200) emptyList()
                else mapper.readValue<List<CsSitePlugin>>(res.text)
            } catch (t: Throwable) {
                logError(t)
                emptyList()
            }
        }
    }

    // ---------- install / uninstall ----------

    private fun sanitizedName(name: String): String =
        name.replace(Regex("[^a-zA-Z0-9_.-]"), "_")

    private fun pluginFile(context: Context, repoUrl: String?, internalName: String): File {
        val repoDir = sanitizedName(repoUrl ?: "local") + "." + (repoUrl ?: "local").hashCode()
        return File(
            context.filesDir,
            "$EXTENSIONS_FOLDER/$repoDir/${sanitizedName(internalName)}.${internalName.hashCode()}.cs3"
        )
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { fis ->
            val buffer = ByteArray(8192)
            var read = fis.read(buffer)
            while (read != -1) {
                digest.update(buffer, 0, read)
                read = fis.read(buffer)
            }
        }
        return "sha256-" + digest.digest().joinToString("") { "%02x".format(it) }
    }

    data class InstallResult(
        val success: Boolean,
        val message: String,
        val providerCount: Int = 0,
    )

    /**
     * Download + verify + load a plugin. Blocking — call off the main thread.
     */
    fun installPlugin(context: Context, repoUrl: String?, plugin: CsSitePlugin): InstallResult {
        val id = plugin.internalName ?: plugin.name ?: ""
        val had = getInstalled(context).any { it.internalName == id }
        val r = installPluginRaw(context, repoUrl, plugin)
        com.lagradost.shiro.utils.repo.NekomoRepo.log(context, r.success, if (had) "update" else "install", plugin.displayName, if (r.success) "v${plugin.version}" else r.message)
        if (r.success) com.lagradost.shiro.utils.repo.ExtensionUpdater.dropPending(context, "cs", id)
        return r
    }

    private fun installPluginRaw(
        context: Context,
        repoUrl: String?,
        plugin: CsSitePlugin,
    ): InstallResult {
        val internalName = plugin.internalName ?: plugin.name ?: return InstallResult(
            false, "Plugin has no name"
        )
        return try {
            val target = pluginFile(context, repoUrl, internalName)
            target.parentFile?.mkdirs()

            val res = khttp.get(plugin.url, timeout = 60.0)
            if (res.statusCode != 200 || res.content.isEmpty()) {
                return InstallResult(false, "Download failed (${res.statusCode})")
            }

            val temp = File.createTempFile(target.name, ".tmp", context.cacheDir)
            try {
                temp.writeBytes(res.content)

                if (plugin.fileHash != null) {
                    val actual = sha256(temp)
                    if (actual != plugin.fileHash) {
                        return InstallResult(false, "Hash mismatch — refusing to install")
                    }
                }

                if (target.exists()) {
                    unloadPlugin(context, internalName)
                    target.delete()
                }
                if (!temp.renameTo(target)) {
                    temp.copyTo(target, overwrite = true)
                    temp.delete()
                }
            } finally {
                if (temp.exists()) temp.delete()
            }

            val installed = CsInstalledPlugin(
                internalName = internalName,
                name = plugin.displayName,
                version = plugin.version,
                filePath = target.absolutePath,
                sourceUrl = plugin.url,
                repoUrl = repoUrl,
                fileHash = plugin.fileHash,
                nsfw = plugin.tvTypes?.any { it.equals("NSFW", true) } == true,
            )

            val ok = loadPlugin(context, target, installed)
            if (!ok) {
                target.delete()
                return InstallResult(false, "Downloaded but failed to load")
            }

            saveInstalled(context, getInstalled(context)
                .filter { it.internalName != internalName } + installed)

            InstallResult(
                true, "Installed ${plugin.displayName}",
                loadedPlugins[target.absolutePath]?.apis?.size ?: 0
            )
        } catch (t: Throwable) {
            logError(t)
            InstallResult(false, "Error: ${t.message}")
        }
    }

    fun uninstallPlugin(context: Context, internalName: String): Boolean {
        val name = getInstalled(context).firstOrNull { it.internalName == internalName }?.name ?: internalName
        val ok = uninstallPluginRaw(context, internalName)
        com.lagradost.shiro.utils.repo.NekomoRepo.log(context, ok, "remove", name, if (ok) "Uninstalled" else "Could not uninstall")
        com.lagradost.shiro.utils.repo.ExtensionUpdater.dropPending(context, "cs", internalName)
        return ok
    }

    private fun uninstallPluginRaw(context: Context, internalName: String): Boolean {
        val installed = getInstalled(context).firstOrNull { it.internalName == internalName }
        unloadPlugin(context, internalName)
        installed?.let {
            File(it.filePath).delete()
            // also remove the enclosing repo dir if empty
            File(it.filePath).parentFile?.let { dir ->
                if (dir.listFiles()?.isEmpty() == true) dir.delete()
            }
        }
        saveInstalled(context, getInstalled(context).filter { it.internalName != internalName })
        return true
    }

    fun getInstalled(context: Context): List<CsInstalledPlugin> =
        context.getKey<Array<CsInstalledPlugin>>(INSTALLED_KEY)?.toList() ?: emptyList()

    private fun saveInstalled(context: Context, list: List<CsInstalledPlugin>) {
        context.setKey(INSTALLED_KEY, list)
    }

    // ---------- enable / disable ----------

    private val disabledPluginNames = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private var disabledLoaded = false

    private fun disabledPlugins(context: Context): Set<String> {
        if (!disabledLoaded) {
            disabledPluginNames.addAll(
                context.getKey<List<String>>(DISABLED_PLUGINS_KEY) ?: emptyList()
            )
            disabledLoaded = true
        }
        return disabledPluginNames
    }

    fun isPluginEnabled(context: Context, internalName: String) =
        internalName !in disabledPlugins(context)

    fun setPluginEnabled(context: Context, internalName: String, enabled: Boolean) {
        disabledPlugins(context)
        if (enabled) disabledPluginNames.remove(internalName)
        else disabledPluginNames.add(internalName)
        context.setKey(DISABLED_PLUGINS_KEY, disabledPluginNames.toList())

        val installed = getInstalled(context).firstOrNull { it.internalName == internalName }
            ?: return
        if (enabled && !isLoaded(installed.filePath)) {
            thread {
                loadPlugin(context, File(installed.filePath), installed)
            }
        } else if (!enabled && isLoaded(installed.filePath)) {
            unloadPlugin(context, internalName)
        }
    }

    /** Re-download a plugin from its stored source url (used for updates). */
    fun updatePlugin(context: Context, internalName: String): InstallResult {
        val installed = getInstalled(context).firstOrNull { it.internalName == internalName }
            ?: return InstallResult(false, "Not installed")
        val repoUrl = installed.repoUrl ?: return InstallResult(false, "No repository recorded")
        val latest = fetchRepoPlugins(repoUrl).firstOrNull { it.internalName == internalName }
            ?: return InstallResult(false, "Not found in repository (offline or removed)")
        if (latest.version <= installed.version && latest.fileHash == installed.fileHash) {
            return InstallResult(true, "${installed.name} is up to date")
        }
        val wasEnabled = isPluginEnabled(context, internalName)
        val result = installPlugin(context, repoUrl, latest)
        if (result.success && !wasEnabled) setPluginEnabled(context, internalName, false)
        return result
    }

    // ---------- per-provider enable / disable ----------

    private const val DISABLED_PROVIDERS_KEY = "cs3_disabled_providers"
    private val disabledProviderNames = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private var disabledProvidersLoaded = false

    private fun disabledProviders(context: Context): Set<String> {
        if (!disabledProvidersLoaded) {
            disabledProviderNames.addAll(
                context.getKey<List<String>>(DISABLED_PROVIDERS_KEY) ?: emptyList()
            )
            disabledProvidersLoaded = true
        }
        return disabledProviderNames
    }

    fun isProviderEnabled(context: Context, apiName: String) = apiName !in disabledProviders(context)

    fun setProviderEnabled(context: Context, apiName: String, enabled: Boolean) {
        disabledProviders(context)
        if (enabled) disabledProviderNames.remove(apiName) else disabledProviderNames.add(apiName)
        context.setKey(DISABLED_PROVIDERS_KEY, disabledProviderNames.toList())
    }

    // ---------- manifest ----------

    private data class PluginManifest(
        @JsonProperty("pluginClassName") val pluginClassName: String?,
        @JsonProperty("name") val name: String?,
        @JsonProperty("version") val version: Int?,
        @JsonProperty("requiresResources") val requiresResources: Boolean = false,
    )

    private fun readManifest(file: File): PluginManifest? {
        return try {
            ZipFile(file).use { zip ->
                val entry = zip.getEntry("manifest.json") ?: return null
                zip.getInputStream(entry).bufferedReader().use { reader ->
                    mapper.readValue(reader.readText())
                }
            }
        } catch (t: Throwable) {
            logError(t)
            null
        }
    }
}
