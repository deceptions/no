package com.lagradost.shiro.utils.mihon

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
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory
import java.io.File

/**
 * Mihon manga extension support. Extensions are APKs listed in a repository index (index.min.json, or the newer gzip'd protobuf index.pb);
 * the user adds a repository, installs the extensions they want, and Nekomo loads them with a
 * DexClassLoader against the vendored extension API (Apache-2.0, from the Aniyomi project).
 * Nothing is bundled and nothing is installed by default.
 */
object MihonManager {
    private const val FOLDER = "Mihon"
    private const val REPOS_KEY = "mh_repositories"
    private const val INSTALLED_KEY = "mh_installed_extensions"
    private const val DISABLED_KEY = "mh_disabled_extensions"

    // Supported extensions-lib range (versionName "1.x" .. "2.x")
    private const val LIB_MIN = 1.0
    private const val LIB_MAX = 2.0
    private const val FEATURE = "tachiyomi.extension"
    private const val META_CLASS = "tachiyomi.extension.class"

    private val mapper: JsonMapper = JsonMapper.builder()
        .addModule(KotlinModule.Builder().build())
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .build()

    data class MhRepo(
        @JsonProperty("url") val url: String,
        @JsonProperty("name") val name: String? = null,
    )

    data class MhIndexSource(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("lang") val lang: String? = null,
        @JsonProperty("id") val id: String? = null,
    )

    data class MhIndexEntry(
        @JsonProperty("name") val name: String,
        @JsonProperty("pkg") val pkg: String,
        @JsonProperty("apk") val apk: String,
        @JsonProperty("lang") val lang: String = "all",
        @JsonProperty("code") val code: Long = 0,
        @JsonProperty("version") val version: String = "",
        @JsonProperty("nsfw") val nsfw: Int = 0,
        @JsonProperty("sources") val sources: List<MhIndexSource> = emptyList(),
        @JsonProperty("apkUrl") val apkUrl: String? = null,
    )

    data class MhInstalled(
        @JsonProperty("pkg") val pkg: String,
        @JsonProperty("name") val name: String,
        @JsonProperty("version") val version: String,
        @JsonProperty("lang") val lang: String,
        @JsonProperty("nsfw") val nsfw: Boolean,
        @JsonProperty("filePath") val filePath: String,
        @JsonProperty("repoUrl") val repoUrl: String?,
    )

    // ---------- repos ----------

    fun getRepos(context: Context): List<MhRepo> =
        context.getKey<Array<MhRepo>>(REPOS_KEY)?.toList() ?: emptyList()

    fun addRepo(context: Context, repo: MhRepo) {
        val cur = getRepos(context)
        if (cur.none { it.url == repo.url }) context.setKey(REPOS_KEY, cur + repo)
    }

    fun removeRepo(context: Context, url: String) =
        context.setKey(REPOS_KEY, getRepos(context).filter { it.url != url })

    /** Normalises a pasted URL: a repo folder or .../index.json is mapped to .../index.min.json. */
    fun normaliseRepoUrl(input: String): String {
        val u = input.trim()
        return when {
            u.endsWith(".json") || u.endsWith(".pb") -> u
            else -> u.trimEnd('/') + "/index.min.json"
        }
    }

    fun fetchIndex(repoUrl: String): List<MhIndexEntry>? {
        val first = fetchIndexOnce(repoUrl)
        // Some repos replaced index.min.json with an "update your app" stub and moved the real list to index.pb
        val bare = repoUrl.substringBefore('?')
        if (bare.endsWith("index.min.json") && (first == null || isStub(first))) {
            fetchIndexOnce(bare.removeSuffix("index.min.json") + "index.pb")?.takeIf { it.isNotEmpty() && !isStub(it) }?.let { return it }
        }
        return first
    }

    private fun isStub(list: List<MhIndexEntry>) =
        list.size <= 5 && list.isNotEmpty() && list.all { it.name.startsWith("Outdated", true) || it.name.startsWith("Update to", true) }

    private fun fetchIndexOnce(repoUrl: String): List<MhIndexEntry>? = try {
        val res = khttp.get(repoUrl, timeout = 30.0)
        when {
            res.statusCode != 200 -> null
            repoUrl.substringBefore('?').endsWith(".pb") -> parseProtoIndex(res.content)
            else -> mapper.readValue<List<MhIndexEntry>>(res.text)
        }
    } catch (t: Throwable) {
        logError(t)
        null
    }

    // ---------- protobuf index (index.pb: gzip'd protobuf with full APK urls) ----------

    private class PbField(val number: Int, val varint: Long, val bytes: ByteArray?)

    private fun pbParse(b: ByteArray): List<PbField> {
        var i = 0
        fun varint(): Long {
            var r = 0L
            var shift = 0
            while (true) {
                val x = b[i++].toInt()
                r = r or ((x and 0x7f).toLong() shl shift)
                shift += 7
                if (x and 0x80 == 0) return r
            }
        }
        val out = ArrayList<PbField>()
        while (i < b.size) {
            val key = varint()
            val number = (key shr 3).toInt()
            when ((key and 7).toInt()) {
                0 -> out.add(PbField(number, varint(), null))
                1 -> { out.add(PbField(number, 0, b.copyOfRange(i, i + 8))); i += 8 }
                2 -> { val l = varint().toInt(); out.add(PbField(number, 0, b.copyOfRange(i, i + l))); i += l }
                5 -> { out.add(PbField(number, 0, b.copyOfRange(i, i + 4))); i += 4 }
                else -> error("Unsupported protobuf wire type")
            }
        }
        return out
    }

    private fun PbField.str() = bytes?.toString(Charsets.UTF_8).orEmpty()

    /** Field 101 holds the extension list; each extension: 1 name, 2 pkg, 3 {1 apk url}, 5 code, 6 version, 7 rating (3 = 18+), 8 sources. */
    private fun parseProtoIndex(raw: ByteArray): List<MhIndexEntry> {
        val data = if (raw.size > 2 && raw[0] == 0x1f.toByte() && raw[1] == 0x8b.toByte())
            java.util.zip.GZIPInputStream(raw.inputStream()).use { it.readBytes() } else raw
        return pbParse(data).filter { it.number == 101 }.flatMap { pbParse(it.bytes ?: ByteArray(0)) }
            .filter { it.number == 1 }.mapNotNull { wrapper ->
                val f = pbParse(wrapper.bytes ?: return@mapNotNull null)
                val name = f.firstOrNull { it.number == 1 }?.str() ?: return@mapNotNull null
                val pkg = f.firstOrNull { it.number == 2 }?.str() ?: return@mapNotNull null
                val apkUrl = f.firstOrNull { it.number == 3 }?.bytes?.let { pbParse(it).firstOrNull { x -> x.number == 1 }?.str() }
                    ?.takeIf { it.startsWith("https://") } ?: return@mapNotNull null
                val sources = f.filter { it.number == 8 }.map { sf ->
                    val s = pbParse(sf.bytes ?: ByteArray(0))
                    MhIndexSource(
                        name = s.firstOrNull { it.number == 2 }?.str(),
                        lang = s.firstOrNull { it.number == 3 }?.str(),
                        id = s.firstOrNull { it.number == 1 }?.varint?.toULong()?.toString()
                    )
                }
                MhIndexEntry(
                    name = name,
                    pkg = pkg,
                    apk = apkUrl.substringAfterLast('/'),
                    lang = sources.mapNotNull { it.lang }.distinct().singleOrNull() ?: "all",
                    code = f.firstOrNull { it.number == 5 }?.varint ?: 0,
                    version = f.firstOrNull { it.number == 6 }?.str().orEmpty(),
                    nsfw = if (f.firstOrNull { it.number == 7 }?.varint == 3L) 1 else 0,
                    sources = sources,
                    apkUrl = apkUrl
                )
            }
    }

    // ---------- install ----------

    fun getInstalled(context: Context): List<MhInstalled> =
        context.getKey<Array<MhInstalled>>(INSTALLED_KEY)?.toList() ?: emptyList()

    private fun save(context: Context, list: List<MhInstalled>) = context.setKey(INSTALLED_KEY, list)

    fun install(context: Context, repoUrl: String, entry: MhIndexEntry): Pair<Boolean, String> = try {
        val apkUrl = entry.apkUrl ?: (repoUrl.substringBeforeLast('/') + "/apk/" + entry.apk)
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
                            MhInstalled(entry.pkg, entry.name, entry.version, entry.lang, entry.nsfw == 1, file.absolutePath, repoUrl)
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

    data class LoadedExtension(val pkg: String, val sources: List<Source>)

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
                onFailure = { logError(Exception("Mihon extension ${inst.pkg} failed to load", it)) }
            )
        }
    }

    /** All sources of the enabled extensions. */
    @Synchronized
    fun sources(context: Context): List<Source> {
        ensureLoaded(context)
        val nsfwOk = com.lagradost.shiro.utils.ContentPrefs.nsfw(context)
        val nsfwPkgs = if (nsfwOk) emptySet() else getInstalled(context).filter { it.nsfw }.map { it.pkg }.toSet()
        return loaded.values.filter { isEnabled(context, it.pkg) && it.pkg !in nsfwPkgs }.flatMap { it.sources }
    }

    fun sourceById(context: Context, id: Long): Source? = sources(context).firstOrNull { it.id == id }

    @Suppress("DEPRECATION")
    private fun loadFile(context: Context, apkFile: File): Result<LoadedExtension> = runCatching {
        com.lagradost.shiro.utils.aniyomi.AniyomiRuntime.ensureRegistered(context)
        val pm = context.packageManager
        val info = pm.getPackageArchiveInfo(
            apkFile.absolutePath, PackageManager.GET_META_DATA or PackageManager.GET_CONFIGURATIONS
        ) ?: error("Not a valid APK")
        require(info.reqFeatures?.any { it.name == FEATURE } == true) { "Not a Mihon manga extension" }
        val app = info.applicationInfo ?: error("Missing application info")
        app.sourceDir = apkFile.absolutePath
        app.publicSourceDir = apkFile.absolutePath
        val versionName = info.versionName ?: error("Missing versionName")
        val lib = versionName.substringBeforeLast('.').toDoubleOrNull() ?: error("Bad version \"$versionName\"")
        require(lib in LIB_MIN..LIB_MAX) { "Unsupported extensions-lib version $lib" }
        val classList = app.metaData?.getString(META_CLASS).orEmpty().trim()
        require(classList.isNotBlank()) { "No source class declared" }

        val optimized = File(context.codeCacheDir, "mihon-dex").apply { mkdirs() }
        apkFile.setReadOnly() // Android refuses writable dex files
        val loader = DexClassLoader(apkFile.absolutePath, optimized.absolutePath, null, context.classLoader)
        val sources = classList.split(";").map { it.trim() }.filter { it.isNotBlank() }.flatMap { raw ->
            val name = if (raw.startsWith(".")) app.packageName + raw else raw
            when (val inst = loader.loadClass(name).getDeclaredConstructor().newInstance()) {
                is Source -> listOf(inst)
                is SourceFactory -> inst.createSources()
                else -> emptyList()
            }
        }
        require(sources.isNotEmpty()) { "Extension produced no sources" }
        LoadedExtension(app.packageName, sources)
    }
}
