package com.lagradost.shiro.utils.aniyomi

import DataStore.getKey
import DataStore.setKey
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.shiro.utils.mvvm.logError
import dalvik.system.DexClassLoader
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.AnimeSourceFactory
import java.io.File

/**
 * Aniyomi anime extension support. Extensions are APKs listed in a repository index (index.min.json);
 * the user adds a repository, installs the extensions they want, and Nekomo loads them with a
 * DexClassLoader against the vendored extension API (Apache-2.0, from the Aniyomi project).
 * Nothing is bundled and nothing is installed by default.
 */
object AniyomiManager {
    private const val FOLDER = "Aniyomi"
    private const val REPOS_KEY = "ay_repositories"
    private const val INSTALLED_KEY = "ay_installed_extensions"
    private const val DISABLED_KEY = "ay_disabled_extensions"

    // Supported extensions-lib range (versionName "14.x" .. "17.x")
    private const val LIB_MIN = 12.0
    private const val LIB_MAX = 17.0
    private const val FEATURE = "tachiyomi.animeextension"
    private const val META_CLASS = "tachiyomi.animeextension.class"

    private val mapper: JsonMapper = JsonMapper.builder()
        .addModule(KotlinModule.Builder().build())
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .build()

    data class AyRepo(
        @JsonProperty("url") val url: String,
        @JsonProperty("name") val name: String? = null,
    )

    data class AyIndexSource(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("lang") val lang: String? = null,
        @JsonProperty("id") val id: String? = null,
    )

    data class AyIndexEntry(
        @JsonProperty("name") val name: String,
        @JsonProperty("pkg") val pkg: String,
        @JsonProperty("apk") val apk: String,
        @JsonProperty("lang") val lang: String = "all",
        @JsonProperty("code") val code: Long = 0,
        @JsonProperty("version") val version: String = "",
        @JsonProperty("nsfw") val nsfw: Int = 0,
        @JsonProperty("sources") val sources: List<AyIndexSource> = emptyList(),
    )

    data class AyInstalled(
        @JsonProperty("pkg") val pkg: String,
        @JsonProperty("name") val name: String,
        @JsonProperty("version") val version: String,
        @JsonProperty("lang") val lang: String,
        @JsonProperty("nsfw") val nsfw: Boolean,
        @JsonProperty("filePath") val filePath: String,
        @JsonProperty("repoUrl") val repoUrl: String?,
    )

    // ---------- repos ----------

    fun getRepos(context: Context): List<AyRepo> =
        context.getKey<Array<AyRepo>>(REPOS_KEY)?.toList() ?: emptyList()

    fun addRepo(context: Context, repo: AyRepo) {
        val cur = getRepos(context)
        if (cur.none { it.url == repo.url }) context.setKey(REPOS_KEY, cur + repo)
    }

    fun removeRepo(context: Context, url: String) =
        context.setKey(REPOS_KEY, getRepos(context).filter { it.url != url })

    /** Normalises a pasted URL: a repo folder or .../index.json is mapped to .../index.min.json. */
    fun normaliseRepoUrl(input: String): String {
        val u = input.trim()
        return when {
            u.endsWith(".json") -> u
            else -> u.trimEnd('/') + "/index.min.json"
        }
    }

    fun fetchIndex(repoUrl: String): List<AyIndexEntry>? = try {
        val res = khttp.get(repoUrl, timeout = 30.0)
        if (res.statusCode != 200) null else mapper.readValue<List<AyIndexEntry>>(res.text)
    } catch (t: Throwable) {
        logError(t)
        null
    }

    // ---------- install ----------

    fun getInstalled(context: Context): List<AyInstalled> =
        context.getKey<Array<AyInstalled>>(INSTALLED_KEY)?.toList() ?: emptyList()

    private fun save(context: Context, list: List<AyInstalled>) = context.setKey(INSTALLED_KEY, list)

    fun install(context: Context, repoUrl: String, entry: AyIndexEntry): Pair<Boolean, String> = try {
        val apkUrl = repoUrl.substringBeforeLast('/') + "/apk/" + entry.apk
        val res = khttp.get(apkUrl, timeout = 90.0)
        if (res.statusCode != 200 || res.content.size < 1024) {
            false to "Download failed (${res.statusCode})"
        } else {
            val dir = File(context.filesDir, FOLDER).apply { mkdirs() }
            val file = File(dir, "${entry.pkg}.apk")
            if (file.exists()) {
                unload(entry.pkg)
                file.setWritable(true)
                file.delete()
            }
            file.writeBytes(res.content)
            // Validate right away so a bad/unsupported extension is reported instead of silently kept
            val loaded = loadFile(context, file)
            if (loaded.isFailure) {
                file.delete()
                false to "Could not load: ${loaded.exceptionOrNull()?.message}"
            } else {
                val ext = loaded.getOrThrow()
                register(ext)
                save(
                    context,
                    getInstalled(context).filter { it.pkg != entry.pkg } +
                            AyInstalled(entry.pkg, entry.name, entry.version, entry.lang, entry.nsfw == 1, file.absolutePath, repoUrl)
                )
                true to "Installed ${entry.name} (${ext.sources.size} source(s))"
            }
        }
    } catch (t: Throwable) {
        logError(t)
        false to "Error: ${t.message}"
    }

    fun uninstall(context: Context, pkg: String) {
        unload(pkg)
        getInstalled(context).firstOrNull { it.pkg == pkg }?.let {
            File(it.filePath).apply { setWritable(true); delete() }
        }
        save(context, getInstalled(context).filter { it.pkg != pkg })
    }

    // ---------- enable / disable ----------

    private val disabled = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private var disabledLoaded = false

    private fun disabledSet(context: Context): Set<String> {
        if (!disabledLoaded) {
            disabled.addAll(context.getKey<Array<String>>(DISABLED_KEY)?.toList() ?: emptyList())
            disabledLoaded = true
        }
        return disabled
    }

    fun isEnabled(context: Context, pkg: String) = pkg !in disabledSet(context)

    fun setEnabled(context: Context, pkg: String, enabled: Boolean) {
        disabledSet(context)
        if (enabled) disabled.remove(pkg) else disabled.add(pkg)
        context.setKey(DISABLED_KEY, disabled.toList())
    }

    // ---------- loading ----------

    data class LoadedExtension(val pkg: String, val sources: List<AnimeSource>)

    private val loaded = LinkedHashMap<String, LoadedExtension>()
    private var allLoaded = false

    @Synchronized
    private fun register(ext: LoadedExtension) {
        loaded[ext.pkg] = ext
    }

    @Synchronized
    private fun unload(pkg: String) {
        loaded.remove(pkg)
    }

    /** Loads every installed extension once (call from a worker thread). */
    @Synchronized
    fun ensureLoaded(context: Context) {
        if (allLoaded) return
        allLoaded = true
        for (inst in getInstalled(context)) {
            if (loaded.containsKey(inst.pkg)) continue
            loadFile(context, File(inst.filePath)).fold(
                onSuccess = { loaded[it.pkg] = it },
                onFailure = { logError(Exception("Aniyomi extension ${inst.pkg} failed to load", it)) }
            )
        }
    }

    /** All sources of the enabled extensions. */
    @Synchronized
    fun sources(context: Context): List<AnimeSource> {
        ensureLoaded(context)
        val nsfwOk = com.lagradost.shiro.utils.ContentPrefs.nsfw(context)
        val nsfwPkgs = if (nsfwOk) emptySet() else getInstalled(context).filter { it.nsfw }.map { it.pkg }.toSet()
        return loaded.values.filter { isEnabled(context, it.pkg) && it.pkg !in nsfwPkgs }.flatMap { it.sources }
    }

    fun sourceById(context: Context, id: Long): AnimeSource? = sources(context).firstOrNull { it.id == id }

    @Suppress("DEPRECATION")
    private fun loadFile(context: Context, apkFile: File): Result<LoadedExtension> = runCatching {
        AniyomiRuntime.ensureRegistered(context)
        val pm = context.packageManager
        val info = pm.getPackageArchiveInfo(
            apkFile.absolutePath, PackageManager.GET_META_DATA or PackageManager.GET_CONFIGURATIONS
        ) ?: error("Not a valid APK")
        require(info.reqFeatures?.any { it.name == FEATURE } == true) { "Not an Aniyomi anime extension" }
        val app = info.applicationInfo ?: error("Missing application info")
        app.sourceDir = apkFile.absolutePath
        app.publicSourceDir = apkFile.absolutePath
        val versionName = info.versionName ?: error("Missing versionName")
        val lib = versionName.substringBeforeLast('.').toDoubleOrNull() ?: error("Bad version \"$versionName\"")
        require(lib in LIB_MIN..LIB_MAX) { "Unsupported extensions-lib version $lib" }
        val classList = app.metaData?.getString(META_CLASS).orEmpty().trim()
        require(classList.isNotBlank()) { "No source class declared" }

        val optimized = File(context.codeCacheDir, "aniyomi-dex").apply { mkdirs() }
        apkFile.setReadOnly() // Android refuses writable dex files
        val loader = DexClassLoader(apkFile.absolutePath, optimized.absolutePath, null, context.classLoader)
        val sources = classList.split(";").map { it.trim() }.filter { it.isNotBlank() }.flatMap { raw ->
            val name = if (raw.startsWith(".")) app.packageName + raw else raw
            when (val inst = loader.loadClass(name).getDeclaredConstructor().newInstance()) {
                is AnimeSource -> listOf(inst)
                is AnimeSourceFactory -> inst.createSources()
                else -> emptyList()
            }
        }
        require(sources.isNotEmpty()) { "Extension produced no sources" }
        LoadedExtension(app.packageName, sources)
    }
}
