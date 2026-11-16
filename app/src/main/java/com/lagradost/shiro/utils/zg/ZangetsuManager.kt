package com.lagradost.shiro.utils.zg

import DataStore.getKey
import DataStore.setKey
import android.content.Context
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.shiro.utils.mvvm.logError
import java.io.File

/**
 * Zangetsu source support. A Zangetsu repository is a manifest (`index.json`) listing JavaScript provider
 * files; the user adds a manifest URL, installs the sources they want, and those scripts run on
 * [ZangetsuEngine]. Nothing is bundled: without a repo and installed sources the feature stays empty.
 */
object ZangetsuManager {
    private const val FOLDER = "Zangetsu"
    private const val REPOS_KEY = "zg_repositories"
    private const val INSTALLED_KEY = "zg_installed_sources"
    private const val DISABLED_KEY = "zg_disabled_sources"

    private val mapper: JsonMapper = JsonMapper.builder()
        .addModule(KotlinModule.Builder().build())
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .build()

    data class ZgRepo(
        @JsonProperty("url") val url: String,
        @JsonProperty("name") val name: String? = null,
    )

    data class ZgManifestSource(
        @JsonProperty("id") val id: String,
        @JsonProperty("name") val name: String,
        @JsonProperty("version") val version: String = "1",
        @JsonProperty("type") val type: String = "anime",
        @JsonProperty("lang") val lang: String = "en",
        @JsonProperty("file") val file: String,
        @JsonProperty("logo") val logo: String? = null,
        @JsonProperty("nsfw") val nsfw: Boolean = false,
    )

    data class ZgManifest(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("description") val description: String? = null,
        @JsonProperty("sources") val sources: List<ZgManifestSource> = emptyList(),
    )

    data class ZgInstalled(
        @JsonProperty("id") val id: String,
        @JsonProperty("name") val name: String,
        @JsonProperty("version") val version: String,
        @JsonProperty("type") val type: String,
        @JsonProperty("lang") val lang: String,
        @JsonProperty("repoUrl") val repoUrl: String?,
        @JsonProperty("filePath") val filePath: String,
        @JsonProperty("fileUrl") val fileUrl: String?,
        @JsonProperty("nsfw") val nsfw: Boolean = false,
    )

    // ---------- repos ----------

    fun getRepos(context: Context): List<ZgRepo> =
        context.getKey<Array<ZgRepo>>(REPOS_KEY)?.toList() ?: emptyList()

    fun addRepo(context: Context, repo: ZgRepo) {
        val cur = getRepos(context)
        if (cur.none { it.url == repo.url }) context.setKey(REPOS_KEY, cur + repo)
    }

    fun removeRepo(context: Context, url: String) =
        context.setKey(REPOS_KEY, getRepos(context).filter { it.url != url })

    fun fetchManifest(url: String): ZgManifest? = try {
        val res = khttp.get(url, timeout = 30.0)
        if (res.statusCode != 200) null else mapper.readValue<ZgManifest>(res.text)
    } catch (t: Throwable) {
        logError(t)
        null
    }

    private fun resolve(manifestUrl: String, file: String): String =
        if (file.startsWith("http")) file else manifestUrl.substringBeforeLast('/') + "/" + file.removePrefix("/")

    // ---------- install ----------

    fun getInstalled(context: Context): List<ZgInstalled> =
        context.getKey<Array<ZgInstalled>>(INSTALLED_KEY)?.toList() ?: emptyList()

    private fun save(context: Context, list: List<ZgInstalled>) = context.setKey(INSTALLED_KEY, list)

    fun install(context: Context, repoUrl: String, src: ZgManifestSource): Pair<Boolean, String> {
        val had = getInstalled(context).any { it.id == src.id }
        val r = installRaw(context, repoUrl, src)
        com.lagradost.shiro.utils.repo.NekomoRepo.log(context, r.first, if (had) "update" else "install", src.name, if (r.first) "v${src.version}" else r.second)
        if (r.first) com.lagradost.shiro.utils.repo.ExtensionUpdater.dropPending(context, "zg", src.id)
        return r
    }

    private fun installRaw(context: Context, repoUrl: String, src: ZgManifestSource): Pair<Boolean, String> = try {
        val fileUrl = resolve(repoUrl, src.file)
        val res = khttp.get(fileUrl, timeout = 45.0)
        if (res.statusCode != 200 || res.text.isBlank()) {
            false to "Download failed (${res.statusCode})"
        } else {
            val dir = File(context.filesDir, FOLDER).apply { mkdirs() }
            val safe = src.id.replace(Regex("[^a-zA-Z0-9_.-]"), "_")
            val file = File(dir, "$safe.js")
            file.writeText(res.text)
            val inst = ZgInstalled(
                src.id, src.name, src.version, src.type, src.lang, repoUrl, file.absolutePath, fileUrl, src.nsfw
            )
            save(context, getInstalled(context).filter { it.id != src.id } + inst)
            ZangetsuEngine.forget(src.id)
            true to "Installed ${src.name}"
        }
    } catch (t: Throwable) {
        logError(t)
        false to "Error: ${t.message}"
    }

    fun uninstall(context: Context, id: String) {
        val name = getInstalled(context).firstOrNull { it.id == id }?.name ?: id
        uninstallRaw(context, id)
        com.lagradost.shiro.utils.repo.NekomoRepo.log(context, true, "remove", name, "Uninstalled")
        com.lagradost.shiro.utils.repo.ExtensionUpdater.dropPending(context, "zg", id)
    }

    private fun uninstallRaw(context: Context, id: String) {
        getInstalled(context).firstOrNull { it.id == id }?.let { File(it.filePath).delete() }
        save(context, getInstalled(context).filter { it.id != id })
        ZangetsuEngine.forget(id)
    }

    /** Re-downloads installed sources whose manifest version changed. Returns how many were updated. */
    fun updateAll(context: Context): Int {
        var n = 0
        for (inst in getInstalled(context)) {
            val repo = inst.repoUrl ?: continue
            val latest = fetchManifest(repo)?.sources?.firstOrNull { it.id == inst.id } ?: continue
            if (latest.version != inst.version && install(context, repo, latest).first) n++
        }
        return n
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

    fun isEnabled(context: Context, id: String) = id !in disabledSet(context)

    fun setEnabled(context: Context, id: String, enabled: Boolean) {
        disabledSet(context)
        if (enabled) disabled.remove(id) else disabled.add(id)
        context.setKey(DISABLED_KEY, disabled.toList())
    }

    fun visible(context: Context, src: ZgInstalled) =
        (com.lagradost.shiro.utils.ContentPrefs.nsfw(context) || !src.nsfw) &&
                (com.lagradost.shiro.utils.ContentPrefs.nonAnime(context) || src.type != "movie")

    fun enabledSources(context: Context): List<ZgInstalled> =
        getInstalled(context).filter { isEnabled(context, it.id) && visible(context, it) }

    // ---------- calling providers ----------

    fun scriptOf(inst: ZgInstalled): String? = try {
        File(inst.filePath).takeIf { it.exists() }?.readText()
    } catch (t: Throwable) {
        logError(t); null
    }

    /** Calls a provider method with JSON args; returns the JSON result string or null on failure. */
    fun call(inst: ZgInstalled, method: String, argsJson: String, timeoutMs: Long = 45_000): String? {
        val script = scriptOf(inst) ?: return null
        return try {
            ZangetsuEngine.call(inst.id, script, inst.version.hashCode(), method, argsJson, timeoutMs)
        } catch (t: Throwable) {
            logError(Exception("Zangetsu ${inst.id}.$method failed", t))
            null
        }
    }
}
