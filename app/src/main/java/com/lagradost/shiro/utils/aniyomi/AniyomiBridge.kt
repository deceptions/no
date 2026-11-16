package com.lagradost.shiro.utils.aniyomi

import com.lagradost.shiro.AcraApplication
import com.lagradost.shiro.utils.ExtractorLink
import com.lagradost.shiro.utils.Qualities
import com.lagradost.shiro.utils.NekomoApi
import com.lagradost.shiro.utils.mvvm.logError
import com.lagradost.shiro.utils.subs.SubtitleProviders
import com.lagradost.shiro.utils.subs.SubtitleResult
import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SAnimeImpl
import eu.kanade.tachiyomi.animesource.model.SEpisodeImpl
import eu.kanade.tachiyomi.animesource.model.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.charset.Charset
import java.util.concurrent.ConcurrentHashMap

/**
 * Maps Aniyomi extension sources into the app's data model (search, anime page, player). Slugs/tokens are
 * "ay-hex(sourceId)-hex(url)" (hex + '-' only, safe in file names, prefs keys and "|"-split tokens).
 */
object AniyomiBridge {
    private const val PREFIX = "ay-"
    private const val SEARCH_TIMEOUT = 20_000L
    private const val LOAD_TIMEOUT = 30_000L
    private const val LINKS_TIMEOUT = 45_000L

    fun isAySlug(slug: String) = slug.startsWith(PREFIX)

    fun makeSlug(sourceId: Long, url: String) = "$PREFIX${hex(sourceId.toString())}-${hex(url)}"

    fun parseSlug(slug: String): Pair<Long, String>? {
        if (!isAySlug(slug)) return null
        val parts = slug.split("-", limit = 3)
        if (parts.size != 3) return null
        val id = unhex(parts[1])?.toLongOrNull() ?: return null
        return id to (unhex(parts[2]) ?: return null)
    }

    private fun hex(s: String) = s.toByteArray(Charset.forName("UTF-8")).joinToString("") { "%02x".format(it) }
    private fun unhex(s: String): String? = try {
        s.chunked(2).map { it.toInt(16).toByte() }.toByteArray().toString(Charset.forName("UTF-8"))
    } catch (t: Throwable) {
        null
    }

    private fun ctx() = AcraApplication.context

    // Extensions often return an empty title / cover from getAnimeDetails, so what the search showed is remembered.
    private val seen = ConcurrentHashMap<String, Pair<String, String>>()

    private fun remember(slug: String, title: String, thumb: String?) {
        if (title.isBlank()) return
        seen[slug] = title to (thumb ?: "")
        ctx()?.let {
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(it).edit()
                .putString("aycache_$slug", title.replace("\n", " ") + "\n" + (thumb ?: "")).apply()
        }
    }

    private fun recall(slug: String): Pair<String, String>? = seen[slug] ?: ctx()?.let {
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(it).getString("aycache_$slug", null)
            ?.split("\n", limit = 2)?.let { p -> (p[0] to p.getOrElse(1) { "" }).also { r -> seen[slug] = r } }
    }

    /** Last resort title from the extension's URL ("/anime/one-piece-123" -> "One Piece 123"). */
    private fun titleFromUrl(url: String): String? =
        url.trim('/').substringAfterLast('/').substringBefore('?').replace(Regex("[-_]+"), " ").trim()
            .takeIf { it.length > 2 }?.split(' ')?.joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }

    private fun sources(): List<AnimeCatalogueSource> {
        val c = ctx() ?: return emptyList()
        return AniyomiManager.sources(c).filterIsInstance<AnimeCatalogueSource>()
    }

    private fun sourceLabel(s: AnimeSource) = if (s.lang.isBlank() || s.lang == "all") s.name else "${s.name} (${s.lang})"

    // ---------- search ----------

    data class Hit(val title: String, val slug: String)

    private fun toData(src: AnimeSource, a: SAnime) = NekomoApi.Companion.Data(
        id = "", slug = makeSlug(src.id, a.url).also { remember(it, a.title, a.thumbnail_url) }, title = a.title, title_english = sourceLabel(src),
        native_title = "", poster = a.thumbnail_url ?: "", banner = "", ids = "{}", type = "anime", format = "",
        episodes = "", episode_duration = "", synopsis = a.description ?: "", language = src.lang, synonyms = "",
        season = "", release_year = null, score = "", rating = "", studios = "", genres = a.genre ?: "",
        aired = "", status = "", trailer = "", total_views = "0", created_at = "", updated_at = ""
    )

    private suspend fun searchSource(src: AnimeCatalogueSource, query: String): List<SAnime> =
        withTimeout(SEARCH_TIMEOUT) { src.getSearchAnime(1, query, src.getFilterList()).animes }

    /** Queries every enabled Aniyomi source in parallel. */
    fun search(query: String): List<NekomoApi.Companion.Data> {
        val list = sources()
        if (list.isEmpty()) return emptyList()
        return try {
            runBlocking {
                list.map { src ->
                    async(Dispatchers.IO) {
                        src to try {
                            searchSource(src, query).also { com.lagradost.shiro.utils.SourceHealth.ok("ay:${src.id}") }
                        } catch (t: Throwable) {
                            com.lagradost.shiro.utils.SourceHealth.fail("ay:${src.id}")
                            logError(t); emptyList()
                        }
                    }
                }.awaitAll()
            }.flatMap { (src, res) -> res.map { toData(src, it) } }
        } catch (t: Throwable) {
            logError(t)
            emptyList()
        }
    }

    fun searchBySource(sourceId: Long, query: String): List<Hit> {
        val src = sources().firstOrNull { it.id == sourceId } ?: return emptyList()
        return try {
            runBlocking { searchSource(src, query) }.map { Hit(it.title, makeSlug(src.id, it.url).also { slug -> remember(slug, it.title, it.thumbnail_url) }) }
        } catch (t: Throwable) {
            logError(t)
            emptyList()
        }
    }

    /** (label, source id) of every enabled source, for the per-anime source chooser. */
    fun sourceChoices(): List<Pair<String, Long>> = sources().map { sourceLabel(it) to it.id }

    // ---------- details / episodes ----------

    fun getAnimePage(slug: String): NekomoApi.Companion.AnimePageNewRoot? {
        val (sourceId, url) = parseSlug(slug) ?: return null
        val src = ctx()?.let { AniyomiManager.sourceById(it, sourceId) } ?: return null
        return try {
            runBlocking {
                withTimeout(LOAD_TIMEOUT) {
                    val stub = SAnimeImpl().apply { this.url = url }
                    val d = try {
                        src.getAnimeDetails(stub)
                    } catch (t: Throwable) {
                        logError(t); stub
                    }
                    val eps = src.getEpisodeList(stub)
                    // Sources list newest first; the app expects ascending order
                    val ordered = eps.sortedWith(compareBy({ if (it.episode_number > 0) it.episode_number else Float.MAX_VALUE }))
                        .let { sorted -> if (eps.all { it.episode_number <= 0 }) eps.reversed() else sorted }
                    val epModels = ordered.mapIndexed { i, e ->
                        val n = if (e.episode_number > 0) e.episode_number else (i + 1).toFloat()
                        val label = if (n % 1f == 0f) n.toInt().toString() else n.toString()
                        NekomoApi.Companion.AnimePageNewEpisodes(
                            id = "$sourceId-$i", slug = "$sourceId-$i",
                            title = e.name.ifBlank { "Episode $label" }, episode = label,
                            image = e.preview_url, insight = null,
                            sources = "[{\"slug\":\"gogostream\",\"source\":\"${makeSlug(sourceId, e.url)}\"}]",
                            ext = null, views = "0", created_at = "", updated_at = ""
                        )
                    }
                    val known = recall(slug)
                    val poster = d.thumbnail_url?.takeIf { it.isNotBlank() } ?: known?.second.orEmpty()
                    val title = d.title.takeIf { it.isNotBlank() } ?: known?.first ?: titleFromUrl(url) ?: "Unknown"
                    val anime = NekomoApi.Companion.AnimePageNew(
                        id = "", slug = slug, title = title, title_english = sourceLabel(src),
                        native_title = "", poster = poster, banner = d.background_url ?: poster, ids = "{}",
                        type = "anime", format = "", episodes = epModels.size.toString(), episode_duration = "",
                        synopsis = d.description ?: "", language = src.lang, synonyms = "", season = "",
                        release_year = null, score = "", rating = "", studios = d.author ?: "",
                        genres = d.genre ?: "", aired = "",
                        status = when (d.status) { 1 -> "Ongoing"; 2 -> "Completed"; 3 -> "Licensed"; else -> "" },
                        trailer = "", total_views = "0", created_at = "", updated_at = ""
                    )
                    NekomoApi.Companion.AnimePageNewRoot(
                        "success", "", NekomoApi.Companion.AnimePageNewData(anime = anime, episodes = epModels)
                    )
                }
            }
        } catch (t: Throwable) {
            logError(t)
            null
        }
    }

    // ---------- streams ----------

    private fun qualityOf(v: Video): Qualities {
        val r = v.resolution ?: Regex("(\\d{3,4})p", RegexOption.IGNORE_CASE).find(v.videoTitle)?.groupValues?.get(1)?.toIntOrNull()
        return when {
            r == null -> Qualities.Unknown
            r >= 2000 -> Qualities.UHD
            r >= 1000 -> Qualities.FullHd
            r >= 700 -> Qualities.HD
            else -> Qualities.SD
        }
    }

    fun resolveStreams(token: String, callback: (ExtractorLink) -> Unit): Boolean {
        val (sourceId, epUrl) = parseSlug(token) ?: return false
        val src = ctx()?.let { AniyomiManager.sourceById(it, sourceId) } ?: return true
        try {
            SubtitleProviders.extensionSubtitles.clear()
            val videos: List<Video> = runBlocking {
                withTimeout(LINKS_TIMEOUT) {
                    val ep = SEpisodeImpl().apply { url = epUrl; name = "" }
                    // Legacy per-episode API first; sources on the newer API expose hosters instead
                    val legacy = runCatching { src.getVideoList(ep) }
                        .onFailure { android.util.Log.w("AniyomiBridge", "getVideoList(episode) failed: ${it.message}") }
                        .getOrElse { emptyList() }
                    legacy.ifEmpty {
                        runCatching {
                            src.getHosterList(ep).flatMap { h ->
                                runCatching { src.getVideoList(h) }
                                    .onFailure { logError(Exception("Aniyomi getVideoList(hoster) failed", it)) }
                                    .getOrElse { emptyList() }
                            }
                        }.onFailure { logError(Exception("Aniyomi getHosterList failed", it)) }.getOrElse { emptyList() }
                    }
                }
            }
            videos.forEach { v ->
                val url = v.videoUrl.takeIf { it.isNotBlank() && it != "null" } ?: v.url
                if (url.isBlank()) return@forEach
                val headers = HashMap<String, String>()
                v.headers?.let { h -> for (i in 0 until h.size) headers[h.name(i)] = h.value(i) }
                callback(
                    ExtractorLink(
                        "${sourceLabel(src)} • ${v.videoTitle.ifBlank { "auto" }}", url, headers["Referer"] ?: "",
                        qualityOf(v).value, url.contains(".m3u8"), url.contains(".mpd"), headers.ifEmpty { null }
                    )
                )
                v.subtitleTracks.forEach { t ->
                    SubtitleProviders.extensionSubtitles.add(
                        SubtitleResult("Extension", t.lang, t.lang, t.url, headers)
                    )
                }
            }
        } catch (t: Throwable) {
            logError(t)
        }
        return true
    }
}
