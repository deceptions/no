package com.lagradost.shiro.utils.cs3

import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.LiveStreamLoadResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink as CsExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.shiro.utils.ExtractorLink
import com.lagradost.shiro.utils.Qualities
import com.lagradost.shiro.utils.NekomoApi
import com.lagradost.shiro.utils.mvvm.logError
import com.lagradost.shiro.utils.subs.SubtitleProviders
import com.lagradost.shiro.utils.subs.SubtitleResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.charset.Charset

/**
 * Bridges CloudStream MainAPI providers into the app's existing data model so
 * loaded extensions show up in search/results/player without rewriting the UI.
 *
 * Routing: slugs and source tokens look like "cs3-hex(apiName)-hex(originalUrl)". NekomoApi/LiveApi/Vidstream check isCs3Slug
 * and forward here; everything else flows through the legacy paths untouched.
 *
 * CloudStream calls are suspend functions; they're wrapped in runBlocking since
 * every call site here is already on a worker thread.
 */
object CsBridge {
    private const val SLUG_PREFIX = "cs3-"

    private const val SEARCH_TIMEOUT_MS = 15_000L
    private const val LOAD_TIMEOUT_MS = 20_000L
    private const val LINKS_TIMEOUT_MS = 40_000L

    fun isCs3Slug(slug: String) = slug.startsWith(SLUG_PREFIX)

    fun makeSlug(apiName: String, url: String) =
        "$SLUG_PREFIX${hex(apiName)}-${hex(url)}"

    /** "cs3-hex(apiName)-hex(url)" -> (apiName, original url/data); null if not a cs3 slug.
     *  Hex + '-' only, so it is safe in file names, prefs keys and "|"-split tokens. */
    fun parseSlug(slug: String): Pair<String, String>? {
        if (!isCs3Slug(slug)) return null
        val parts = slug.split("-", limit = 3)
        if (parts.size != 3) return null
        val api = unhex(parts[1]) ?: return null
        val url = unhex(parts[2]) ?: return null
        return api to url
    }

    private fun hex(s: String) =
        s.toByteArray(Charset.forName("UTF-8")).joinToString("") { "%02x".format(it) }

    private fun unhex(s: String): String? = try {
        s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            .toString(Charset.forName("UTF-8"))
    } catch (t: Throwable) {
        null
    }

    // ---------- search ----------

    /** Queries every loaded provider in parallel; returns app-format results. */
    fun search(query: String): List<NekomoApi.Companion.Data> {
        val apis = CsPluginManager.providers()
        if (apis.isEmpty()) return emptyList()
        return try {
            runBlocking {
                apis.map { api ->
                    async(Dispatchers.IO) {
                        api to try {
                            withTimeout(SEARCH_TIMEOUT_MS) { searchProvider(api, query) }.also {
                                com.lagradost.shiro.utils.SourceHealth.ok("cs:${api.name}")
                            }
                        } catch (t: Throwable) {
                            com.lagradost.shiro.utils.SourceHealth.fail("cs:${api.name}")
                            logError(t); null
                        }
                    }
                }.awaitAll()
            }.flatMap { (api, results) ->
                val nsfwOk = com.lagradost.shiro.utils.ContentPrefs.nsfw()
                val nonAnimeOk = com.lagradost.shiro.utils.ContentPrefs.nonAnime()
                results.orEmpty().filter {
                    val t = it.type?.name
                    (nsfwOk || t != "NSFW") && (nonAnimeOk || t == null || t == "Anime" || t == "AnimeMovie" || t == "OVA")
                }.map { it.toAppData(api) }
            }
        } catch (t: Throwable) {
            logError(t)
            emptyList()
        }
    }

    /**
     * Providers implement exactly one of search(query, page) or the legacy search(query); the other
     * is a stub that throws NotImplementedError, so try the paged one first and fall back.
     */
    private suspend fun searchProvider(api: MainAPI, query: String): List<com.lagradost.cloudstream3.SearchResponse>? =
        try {
            api.search(query, 1)?.items
        } catch (e: NotImplementedError) {
            api.search(query)
        }

    data class HomeSectionRef(val apiName: String, val index: Int, val title: String)

    /** Home-page rows exposed by the user's installed providers (CloudStream "mainPage"). */
    fun homeSections(): List<HomeSectionRef> = try {
        CsPluginManager.providers().flatMap { api ->
            try {
                if (!api.hasMainPage) emptyList()
                else api.mainPage.mapIndexed { i, m -> HomeSectionRef(api.name, i, "${api.name} · ${m.name}") }
            } catch (t: Throwable) {
                emptyList()
            }
        }
    } catch (t: Throwable) {
        emptyList()
    }

    fun homeItems(apiName: String, index: Int): List<NekomoApi.CommonAnimePageData>? {
        val api = CsPluginManager.findProvider(apiName) ?: return null
        return try {
            val page = api.mainPage.getOrNull(index) ?: return null
            val res = runBlocking {
                withTimeout(SEARCH_TIMEOUT_MS) {
                    api.getMainPage(1, com.lagradost.cloudstream3.MainPageRequest(page.name, page.data, page.horizontalImages))
                }
            }
            res?.items?.flatMap { it.list }?.map {
                NekomoApi.CommonAnimePageData(it.name, it.posterUrl ?: "", makeSlug(api.name, it.url), api.name)
            }
        } catch (t: Throwable) {
            logError(t)
            null
        }
    }

    data class ProviderHit(val title: String, val slug: String)

    /** Searches a single provider by name (blocking); used by the per-anime source chooser. */
    fun searchByProvider(apiName: String, query: String): List<ProviderHit> {
        val api = CsPluginManager.findProvider(apiName) ?: return emptyList()
        return try {
            runBlocking { withTimeout(SEARCH_TIMEOUT_MS) { searchProvider(api, query) } }
                .orEmpty().map { ProviderHit(it.name, makeSlug(api.name, it.url)) }
        } catch (t: Throwable) {
            logError(t)
            emptyList()
        }
    }

    private fun com.lagradost.cloudstream3.SearchResponse.toAppData(api: MainAPI) =
        NekomoApi.Companion.Data(
            id = "",
            slug = makeSlug(api.name, url),
            title = name,
            title_english = api.name,
            native_title = "",
            poster = posterUrl ?: "",
            banner = "",
            ids = "{}",
            type = type?.name?.lowercase() ?: "",
            format = "",
            episodes = "",
            episode_duration = "",
            synopsis = "",
            language = "",
            synonyms = "",
            season = "",
            release_year = null,
            score = "",
            rating = "",
            studios = "",
            genres = "",
            aired = "",
            status = "",
            trailer = "",
            total_views = "0",
            created_at = "",
            updated_at = ""
        )

    // ---------- details / episodes ----------

    fun getAnimePage(slug: String): NekomoApi.Companion.AnimePageNewRoot? {
        val (apiName, url) = parseSlug(slug) ?: return null
        val api = CsPluginManager.findProvider(apiName) ?: return null
        val res = try {
            runBlocking { withTimeout(LOAD_TIMEOUT_MS) { api.load(url) } }
        } catch (t: Throwable) {
            logError(t)
            return null
        } ?: return null

        val episodes = res.toEpisodes(apiName)
        val anime = NekomoApi.Companion.AnimePageNew(
            id = "",
            slug = slug,
            title = res.name,
            title_english = apiName,
            native_title = "",
            poster = res.posterUrl ?: "",
            banner = res.backgroundPosterUrl ?: res.posterUrl ?: "",
            ids = "{}",
            type = res.type.name.lowercase(),
            format = "",
            episodes = episodes.size.toString(),
            episode_duration = res.duration?.toString() ?: "",
            synopsis = res.plot ?: "",
            language = "",
            synonyms = (res as? AnimeLoadResponse)?.synonyms?.joinToString() ?: "",
            season = "",
            release_year = res.year?.toString(),
            score = res.score?.toInt(100)?.toString() ?: "",
            rating = "",
            studios = "",
            genres = res.tags?.joinToString(",") ?: "",
            aired = "",
            status = when (res) {
                is TvSeriesLoadResponse -> res.showStatus?.name ?: ""
                is AnimeLoadResponse -> res.showStatus?.name ?: ""
                else -> ""
            },
            trailer = "",
            total_views = "0",
            created_at = "",
            updated_at = ""
        )
        return NekomoApi.Companion.AnimePageNewRoot(
            status = "success",
            message = "",
            data = NekomoApi.Companion.AnimePageNewData(anime = anime, episodes = episodes)
        )
    }

    /** Flattens a LoadResponse into app episode entries with cs3 token sources. */
    private fun LoadResponse.toEpisodes(
        apiName: String
    ): List<NekomoApi.Companion.AnimePageNewEpisodes> {
        fun epEntry(index: Int, data: String, epNum: Int?, name: String?, dub: String?, poster: String?, season: Int?) =
            NekomoApi.Companion.AnimePageNewEpisodes(
                id = "$apiName-$index",
                slug = "$apiName-$index",
                title = (if (season != null && season > 1) "S$season " else "") +
                        (name ?: "Episode ${epNum ?: index + 1}") +
                        (if (dub != null) " ($dub)" else ""),
                episode = (epNum ?: index + 1).toString(),
                image = poster,
                insight = null,
                sources = "[{\"slug\":\"gogostream\",\"source\":\"${
                    makeSlug(apiName, data)
                }\"}]",
                ext = null,
                views = "0",
                created_at = "",
                updated_at = ""
            )

        return when (this) {
            is TvSeriesLoadResponse -> {
                episodes.mapIndexedNotNull { i, ep ->
                    if (ep.data.isBlank()) null else
                        epEntry(i, ep.data, ep.episode, ep.name, null, ep.posterUrl, ep.season)
                }
            }
            is AnimeLoadResponse -> {
                // episodes: Map<DubStatus, List<Episode>> — sub first, then dubbed
                val ordered = this.episodes.entries.sortedBy {
                    when (it.key) {
                        DubStatus.Subbed, DubStatus.None -> 0
                        DubStatus.Dubbed -> 1
                        else -> 2
                    }
                }
                var i = 0
                ordered.flatMap { (status, eps) ->
                    val dubLabel = if (status == DubStatus.Dubbed) "Dub" else null
                    eps.mapNotNull { ep ->
                        if (ep.data.isBlank()) null else epEntry(
                            i++, ep.data, ep.episode, ep.name, dubLabel, ep.posterUrl, ep.season
                        )
                    }
                }
            }
            is MovieLoadResponse -> listOf(
                epEntry(0, dataUrl, null, name, null, posterUrl, null)
            )
            is LiveStreamLoadResponse -> listOf(
                epEntry(0, dataUrl, null, name, null, posterUrl, null)
            )
            // TorrentLoadResponse carries magnets the player can't handle
            else -> emptyList()
        }
    }

    // ---------- streams ----------

    /**
     * Resolves a cs3 episode token through loadLinks and emits app ExtractorLinks.
     * Returns true if the token was ours (regardless of whether links emitted).
     */
    fun resolveStreams(
        token: String,
        isCasting: Boolean,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val (apiName, data) = parseSlug(token) ?: return false
        val api = CsPluginManager.findProvider(apiName) ?: return false
        return try {
            SubtitleProviders.extensionSubtitles.clear()
            runBlocking {
                withTimeout(LINKS_TIMEOUT_MS) {
                    api.loadLinks(data, isCasting, { sub ->
                        SubtitleProviders.extensionSubtitles.add(
                            SubtitleResult("Extension", sub.lang, sub.lang, sub.url, sub.headers)
                        )
                    }, { link ->
                        callback(link.toAppLink(apiName))
                    })
                }
            }
            true
        } catch (t: Throwable) {
            logError(t)
            true // consumed the token even on failure so it doesn't fall through
        }
    }

    private fun CsExtractorLink.toAppLink(apiName: String): ExtractorLink {
        val q = when {
            quality >= 2000 -> Qualities.UHD
            quality >= 1000 -> Qualities.FullHd
            quality >= 700 -> Qualities.HD
            quality >= 400 -> Qualities.SD
            else -> Qualities.Unknown
        }
        return ExtractorLink(
            name = "$apiName • $source",
            url = url,
            referer = referer,
            quality = q.value,
            isM3u8 = type == ExtractorLinkType.M3U8,
            isDash = type == ExtractorLinkType.DASH,
            headers = headers.ifEmpty { null }
        )
    }
}
