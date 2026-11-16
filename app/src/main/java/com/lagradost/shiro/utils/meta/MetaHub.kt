package com.lagradost.shiro.utils.meta

import android.content.Context
import androidx.preference.PreferenceManager
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.shiro.AcraApplication
import com.lagradost.shiro.utils.ContentPrefs
import com.lagradost.shiro.utils.LiveApi
import com.lagradost.shiro.utils.NekomoApi
import com.lagradost.shiro.utils.NekomoApi.Companion.AnimePageNewData
import com.lagradost.shiro.utils.NekomoApi.Companion.AnimePageNewEpisodes
import com.lagradost.shiro.utils.NekomoApi.Companion.AnimePageNewRoot
import com.lagradost.shiro.utils.NekomoApi.Companion.Data
import com.lagradost.shiro.utils.mvvm.logError
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks which metadata APIs are healthy. A provider that answers 429 (rate limit) is skipped until its
 * Retry-After has passed; three failures in a row pause it for two minutes. Callers then use the next provider.
 */
object ApiHealth {
    private val until = ConcurrentHashMap<String, Long>()
    private val fails = ConcurrentHashMap<String, Int>()
    private val latency = ConcurrentHashMap<String, Long>()

    /** Remembers how fast [id] answers (smoothed), so the quickest healthy API is always tried first. */
    fun record(id: String, ms: Long) {
        latency[id] = latency[id]?.let { (it * 6 + ms * 4) / 10 } ?: ms
    }

    /** Lower is better: paused APIs last, then recent failures, then average response time. */
    fun score(id: String): Long = if (blocked(id)) 1_000_000L else (latency[id] ?: 1500L) + (fails[id] ?: 0) * 4000L

    fun fastest(ids: List<String>): List<String> = ids.sortedBy { score(it) }

    fun blocked(id: String) = (until[id] ?: 0L) > System.currentTimeMillis()

    fun rateLimited(id: String, seconds: Int) {
        until[id] = System.currentTimeMillis() + seconds.coerceIn(5, 300) * 1000L
    }

    fun success(id: String) {
        fails[id] = 0
    }

    fun failure(id: String) {
        val n = (fails[id] ?: 0) + 1
        if (n >= 3) {
            until[id] = System.currentTimeMillis() + 120_000L
            fails[id] = 0
        } else fails[id] = n
    }

    fun reset() {
        until.clear(); fails.clear()
    }

    /** For testing the backups: pretend [id] is down for a few minutes. */
    fun simulateOutage(id: String, minutes: Int) {
        until[id] = System.currentTimeMillis() + minutes * 60_000L
    }

    fun status(id: String): String {
        val left = ((until[id] ?: 0L) - System.currentTimeMillis()) / 1000
        return if (left > 0) "Paused for ${if (left >= 90) "${left / 60} min" else "$left s"} (backups are used)"
        else "Available" + (latency[id]?.let { " · ~${it} ms" } ?: "")
    }
}

/**
 * Runs alternatives in priority order but does not wait for a slow one: when the current attempt has not answered after
 * [hedgeMs] the next one starts in parallel, and the first acceptable answer wins. Slow or failed primaries therefore
 * cost at most [hedgeMs] before a backup takes over.
 */
object Race {
    private val pool = java.util.concurrent.Executors.newCachedThreadPool { r -> Thread(r, "nk-race").apply { isDaemon = true } }

    fun <T : Any> first(hedgeMs: Long, tasks: List<() -> T?>, deadlineMs: Long = 30_000, ok: (T) -> Boolean = { true }): T? {
        if (tasks.isEmpty()) return null
        val cs = java.util.concurrent.ExecutorCompletionService<T?>(pool)
        val futures = ArrayList<java.util.concurrent.Future<T?>>()
        var next = 0
        var pending = 0
        val end = System.currentTimeMillis() + deadlineMs
        fun launch() {
            val i = next++
            futures += cs.submit(java.util.concurrent.Callable<T?> { try { tasks[i]() } catch (e: Exception) { null } })
            pending++
        }
        launch()
        try {
            while (pending > 0 && System.currentTimeMillis() < end) {
                val hedge = next < tasks.size
                val done = cs.poll(if (hedge) hedgeMs else (end - System.currentTimeMillis()).coerceAtLeast(1), java.util.concurrent.TimeUnit.MILLISECONDS)
                if (done == null) {
                    if (hedge) launch() else break
                    continue
                }
                pending--
                val v = try { done.get() } catch (e: Exception) { null }
                if (v != null && ok(v)) return v
                if (next < tasks.size) launch()
            }
            return null
        } finally {
            futures.forEach { it.cancel(true) }
        }
    }
}

/** Keeps the last good copy of every details page so a title still opens when its API is down. */
object MetaCache {
    private val mapper = JsonMapper.builder().addModule(KotlinModule.Builder().build())
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false).build()

    private fun dir(): File? = AcraApplication.context?.cacheDir?.let { File(it, "meta").apply { mkdirs() } }
    private fun file(slug: String) = dir()?.let { File(it, slug.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".json") }

    fun put(slug: String, page: AnimePageNewRoot) {
        try {
            file(slug)?.writeText(mapper.writeValueAsString(page))
            dir()?.listFiles()?.sortedByDescending { it.lastModified() }?.drop(300)?.forEach { it.delete() }
        } catch (e: Exception) {
            // cache only
        }
    }

    fun get(slug: String, maxAgeMs: Long = Long.MAX_VALUE): AnimePageNewRoot? = try {
        file(slug)?.takeIf { it.exists() && System.currentTimeMillis() - it.lastModified() < maxAgeMs }
            ?.let { mapper.readValue<AnimePageNewRoot>(it.readText()) }
    } catch (e: Exception) {
        null
    }
}

/**
 * Metadata for titles. AniList is the primary catalogue (see [LiveApi]); this hub adds:
 *  - fallbacks that take over when AniList fails or rate-limits (Jikan/MyAnimeList, then Kitsu),
 *  - episode lists for anime (Jikan, then Kitsu) so every title has episodes to track,
 *  - non-anime titles: TVMaze (TV, no key) and TMDB (movies + TV, with the user's own free key).
 *
 * Slugs: al<id> AniList, mal<id> MyAnimeList id, kt<id> Kitsu, tvm<id> TVMaze, tmm<id> TMDB movie, tmt<id> TMDB TV.
 */
object MetaHub {
    private val mapper = JsonMapper.builder().addModule(KotlinModule.Builder().build())
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false).build()
    private val slugRegex = Regex("""^(mal|kt|tvm|tmm|tmt)(\d+)$""")
    private val jikanLock = Any()
    private var jikanLast = 0L

    fun isMetaSlug(slug: String) = slugRegex.matches(slug)

    // ---------------------------------------------------------------------------------------------------------
    // HTTP
    // ---------------------------------------------------------------------------------------------------------

    private fun header(res: khttp.Response, name: String) =
        res.headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    private fun getJson(provider: String, url: String, headers: Map<String, String> = emptyMap(), timeout: Double = 8.0): JsonNode? {
        if (ApiHealth.blocked(provider)) return null
        // Backups / media APIs can be switched off in the Extensions manager
        AcraApplication.context?.let { c ->
            if (!PreferenceManager.getDefaultSharedPreferences(c).getBoolean("api_enabled_$provider", true)) return null
        }
        return try {
            if (provider == "jikan") synchronized(jikanLock) {
                val wait = 450 - (System.currentTimeMillis() - jikanLast)
                if (wait > 0) Thread.sleep(wait)
                jikanLast = System.currentTimeMillis()
            }
            val t0 = System.currentTimeMillis()
            val res = khttp.get(url, headers = mapOf("User-Agent" to NekomoApi.USER_AGENT) + headers, timeout = timeout)
            when (res.statusCode) {
                in 200..299 -> mapper.readTree(res.text).also { ApiHealth.success(provider); ApiHealth.record(provider, System.currentTimeMillis() - t0) }
                404 -> null
                429 -> {
                    ApiHealth.rateLimited(provider, header(res, "Retry-After")?.toIntOrNull() ?: 60)
                    null
                }
                else -> {
                    ApiHealth.failure(provider)
                    null
                }
            }
        } catch (e: Exception) {
            ApiHealth.failure(provider)
            null
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun JsonNode?.txt(vararg path: String): String {
        var n = this ?: return ""
        for (p in path) n = n.path(p)
        return if (n.isMissingNode || n.isNull) "" else n.asText("")
    }

    private fun https(u: String) = u.replaceFirst("http://", "https://")
    private fun stripHtml(s: String) = s.replace(Regex("<br\\s*/?>"), "\n").replace(Regex("<[^>]*>"), "").trim()

    private fun data(
        slug: String, title: String, english: String? = null, native: String = "", poster: String = "", banner: String = "",
        malId: String? = null, type: String = "anime", format: String = "", episodes: String = "", duration: String = "",
        synopsis: String = "", season: String = "", year: String? = null, score: String = "", studios: String = "",
        genres: List<String> = emptyList(), aired: String = "", status: String = "", language: String = "sub", trailer: String = "",
    ) = Data(
        id = slug, slug = slug, title = title, title_english = english?.takeIf { it.isNotBlank() }, native_title = native,
        poster = poster, banner = banner, ids = mapper.writeValueAsString(mapOf("mal" to malId)), type = type,
        format = format, episodes = episodes, episode_duration = duration, synopsis = synopsis, language = language,
        synonyms = "", season = season, release_year = year?.takeIf { it.isNotBlank() }, score = score, rating = "",
        studios = studios, genres = genres.joinToString(","), aired = aired, status = status, trailer = trailer,
        total_views = "0", created_at = "", updated_at = ""
    )

    private fun root(d: Data, episodes: List<AnimePageNewEpisodes>) =
        AnimePageNewRoot("success", "", AnimePageNewData(LiveApi.dataToAnimePage(d, d.slug), episodes))

    /** One tracker episode. [ext] = "meta" marks it as metadata only (no stream behind it). */
    fun episode(slug: String, n: Int, title: String?, image: String? = null, summary: String? = null) = AnimePageNewEpisodes(
        id = "meta-$slug-$n", slug = "meta-$slug-$n", title = title?.takeIf { it.isNotBlank() } ?: "Episode $n",
        episode = n.toString(), image = image ?: "", insight = summary ?: "", sources = "", ext = "meta",
        views = "0", created_at = "", updated_at = ""
    )

    private fun placeholders(slug: String, from: Int, to: Int) =
        (from..to.coerceAtMost(2000)).map { episode(slug, it, null) }

    // ---------------------------------------------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------------------------------------------

    /** Details page for any non-AniList slug. */
    fun getPage(slug: String): AnimePageNewRoot? {
        val m = slugRegex.find(slug) ?: return null
        val id = m.groupValues[2]
        val page = try {
            when (m.groupValues[1]) {
                "mal" -> {
                    val tasks: List<() -> AnimePageNewRoot?> = ApiHealth.fastest(listOf("jikan", "kitsu")).map { p ->
                        { if (p == "jikan") jikanPage(id, slug) else kitsuByMal(id)?.let { kitsuPage(it, slug) } }
                    }
                    Race.first(3000, tasks)
                }
                "kt" -> kitsuPage(id, slug)
                "tvm" -> tvmazePage(id, slug)
                "tmm" -> tmdbMoviePage(id, slug)
                "tmt" -> tmdbTvPage(id, slug)
                else -> null
            }
        } catch (e: Exception) {
            logError(e)
            null
        }
        if (page != null) MetaCache.put(slug, page)
        return page ?: MetaCache.get(slug)
    }

    /** Anime search when AniList is unavailable: Kitsu first, then Jikan. */
    fun fallbackSearch(query: String): List<Data> {
        val tasks: List<() -> List<Data>?> = ApiHealth.fastest(listOf("kitsu", "jikan")).map { p ->
            { if (p == "kitsu") kitsuSearch(query) else jikanSearch(query) }
        }
        return Race.first(2500, tasks, ok = { it.isNotEmpty() }) ?: emptyList()
    }

    /** Movies and TV shows (only when the user enabled them). */
    fun searchMedia(query: String, ctx: Context? = null): List<Data> {
        if (!ContentPrefs.nonAnime()) return emptyList()
        val tasks: List<() -> List<Data>?> = ApiHealth.fastest(listOf("tmdb", "tvmaze")).map { p ->
            { if (p == "tmdb") tmdbSearch(query, ctx) else tvmazeSearch(query) }
        }
        return Race.first(2500, tasks, ok = { it.isNotEmpty() }) ?: emptyList()
    }

    /**
     * Episodes for an anime, matched by MyAnimeList id: Jikan, then Kitsu. Falls back to numbered placeholders
     * (when the episode count is known) so there is always something to track.
     */
    fun episodesForMal(slug: String, malId: String?, total: Int?): List<AnimePageNewEpisodes> {
        val real = malId?.takeIf { it.isNotBlank() && it != "null" }?.let { mal ->
            val tasks: List<() -> List<AnimePageNewEpisodes>?> = ApiHealth.fastest(listOf("jikan", "kitsu")).map { p ->
                { if (p == "jikan") jikanEpisodes(slug, mal) else kitsuByMal(mal)?.let { kitsuEpisodes(slug, it) }.orEmpty() }
            }
            Race.first(3500, tasks, ok = { it.isNotEmpty() })
        }.orEmpty()
        val count = total?.takeIf { it > 0 } ?: 0
        return if (real.size >= count) real else real + placeholders(slug, real.size + 1, count)
    }

    // ---------------------------------------------------------------------------------------------------------
    // Jikan (MyAnimeList)
    // ---------------------------------------------------------------------------------------------------------

    private fun jikanStatus(s: String) = when (s) {
        "Finished Airing" -> "Finished airing"
        "Currently Airing" -> "Currently airing"
        "Not yet aired" -> "Not yet released"
        else -> s
    }

    private fun jikanData(n: JsonNode, slug: String): Data {
        val mal = n.path("mal_id").asInt().toString()
        val title = n.txt("title").ifEmpty { n.txt("title_english") }
        return data(
            slug = slug, title = title, english = n.txt("title_english"), native = n.txt("title_japanese"),
            poster = n.txt("images", "jpg", "large_image_url"), malId = mal,
            format = n.txt("type"), episodes = n.txt("episodes"),
            duration = Regex("\\d+").find(n.txt("duration"))?.value ?: "",
            synopsis = n.txt("synopsis").replace(Regex("\\s*\\[Written by MAL Rewrite]"), ""),
            season = n.txt("season").replaceFirstChar { it.uppercase() }, year = n.txt("year").ifEmpty { n.txt("aired", "prop", "from", "year") },
            score = n.txt("score"), studios = n.path("studios").joinToString(", ") { it.txt("name") },
            genres = n.path("genres").map { it.txt("name") }, aired = n.txt("aired", "string"), status = jikanStatus(n.txt("status")),
            trailer = n.txt("trailer", "youtube_id").takeIf { it.isNotBlank() }?.let { "youtube:$it" } ?: "",
        )
    }

    private fun jikanPage(mal: String, slug: String): AnimePageNewRoot? {
        val n = getJson("jikan", "https://api.jikan.moe/v4/anime/$mal/full")?.path("data") ?: return null
        if (n.isMissingNode) return null
        val d = jikanData(n, slug)
        return root(d, episodesForMal(slug, mal, d.episodes.toIntOrNull()))
    }

    private fun jikanEpisodes(slug: String, mal: String): List<AnimePageNewEpisodes> {
        val out = ArrayList<AnimePageNewEpisodes>()
        var page = 1
        while (page <= 3) {
            val j = getJson("jikan", "https://api.jikan.moe/v4/anime/$mal/episodes?page=$page") ?: break
            j.path("data").forEach { e ->
                val n = e.path("mal_id").asInt()
                if (n > 0) out.add(episode(slug, n, e.txt("title"), summary = e.txt("aired").take(10)))
            }
            if (!j.path("pagination").path("has_next_page").asBoolean(false)) break
            page++
        }
        return out.sortedBy { it.episode?.toIntOrNull() ?: 0 }
    }

    private fun jikanSearch(query: String): List<Data> {
        val j = getJson("jikan", "https://api.jikan.moe/v4/anime?q=${enc(query)}&limit=15&sfw=${!ContentPrefs.nsfw()}") ?: return emptyList()
        return j.path("data").map { jikanData(it, "mal${it.path("mal_id").asInt()}") }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Kitsu
    // ---------------------------------------------------------------------------------------------------------

    private val kitsuHeaders = mapOf("Accept" to "application/vnd.api+json")
    private fun kitsuUrl(path: String) = "https://kitsu.io/api/edge/$path"

    private fun kitsuStatus(s: String) = when (s) {
        "finished" -> "Finished airing"
        "current" -> "Currently airing"
        "upcoming", "tba", "unreleased" -> "Not yet released"
        else -> s
    }

    private fun seasonOf(date: String): String = when (date.drop(5).take(2).toIntOrNull()) {
        1, 2, 3 -> "Winter"; 4, 5, 6 -> "Spring"; 7, 8, 9 -> "Summer"; 10, 11, 12 -> "Fall"; else -> ""
    }

    private fun kitsuData(n: JsonNode, slug: String, mal: String? = null): Data {
        val a = n.path("attributes")
        val titles = a.path("titles")
        val start = a.txt("startDate")
        return data(
            slug = slug, title = titles.txt("en_jp").ifEmpty { a.txt("canonicalTitle") }, english = titles.txt("en").ifEmpty { titles.txt("en_us") },
            native = titles.txt("ja_jp"), poster = a.txt("posterImage", "large").ifEmpty { a.txt("posterImage", "original") },
            banner = a.txt("coverImage", "large"), malId = mal,
            format = a.txt("subtype").let { if (it.length <= 3) it.uppercase() else it.replaceFirstChar { c -> c.uppercase() } },
            episodes = a.txt("episodeCount"), duration = a.txt("episodeLength"), synopsis = a.txt("synopsis"),
            season = seasonOf(start), year = start.take(4), score = a.txt("averageRating"), aired = start,
            status = kitsuStatus(a.txt("status")),
            trailer = a.txt("youtubeVideoId").takeIf { it.isNotBlank() }?.let { "youtube:$it" } ?: "",
        )
    }

    private fun kitsuByMal(mal: String): String? {
        val j = getJson(
            "kitsu", kitsuUrl("mappings?filter%5BexternalSite%5D=myanimelist%2Fanime&filter%5BexternalId%5D=$mal&include=item&fields%5Banime%5D=canonicalTitle"),
            kitsuHeaders
        ) ?: return null
        return j.path("data").firstOrNull()?.path("relationships")?.path("item")?.path("data")?.path("id")?.asText(null)
    }

    private fun kitsuPage(id: String, slug: String): AnimePageNewRoot? {
        val j = getJson("kitsu", kitsuUrl("anime/$id?include=categories,mappings"), kitsuHeaders) ?: return null
        val n = j.path("data")
        if (n.isMissingNode) return null
        val included = j.path("included")
        val mal = included.firstOrNull { it.txt("attributes", "externalSite") == "myanimelist/anime" }?.txt("attributes", "externalId")
        val base = kitsuData(n, slug, mal)
        val genres = included.filter { it.path("type").asText() == "categories" }.map { it.txt("attributes", "title") }.filter { it.isNotBlank() }.take(6)
        val d = base.copy(genres = genres.joinToString(","))
        return root(d, kitsuEpisodes(slug, id).let { real ->
            val count = d.episodes.toIntOrNull() ?: 0
            if (real.size >= count) real else real + placeholders(slug, real.size + 1, count)
        })
    }

    private fun kitsuEpisodes(slug: String, id: String): List<AnimePageNewEpisodes> {
        val out = ArrayList<AnimePageNewEpisodes>()
        for (page in 0 until 6) {
            val j = getJson("kitsu", kitsuUrl("anime/$id/episodes?page%5Blimit%5D=20&page%5Boffset%5D=${page * 20}&sort=number"), kitsuHeaders) ?: break
            val items = j.path("data")
            items.forEach { e ->
                val a = e.path("attributes")
                val num = a.path("number").asInt(0)
                if (num > 0) out.add(
                    episode(slug, num, a.txt("canonicalTitle").ifEmpty { a.txt("titles", "en_us") }, a.txt("thumbnail", "original").ifEmpty { null },
                        a.txt("synopsis").ifEmpty { a.txt("airdate") })
                )
            }
            if (items.size() < 20) break
        }
        return out.distinctBy { it.episode }
    }

    private fun kitsuSearch(query: String): List<Data> = kitsuList("filter%5Btext%5D=${enc(query)}")

    /** Kitsu lists (popular, airing, ...) as app titles; used when AniList is unavailable. [q] is a raw query string. */
    fun browse(q: String): List<Data> = kitsuList(q)

    private fun kitsuList(q: String): List<Data> {
        val j = getJson(
            "kitsu", kitsuUrl("anime?$q&page%5Blimit%5D=20&include=mappings&fields%5Bmappings%5D=externalSite,externalId"),
            kitsuHeaders
        ) ?: return emptyList()
        val nsfw = ContentPrefs.nsfw()
        val mal = j.path("included").filter { it.txt("attributes", "externalSite") == "myanimelist/anime" }
            .associate { it.path("id").asText() to it.txt("attributes", "externalId") }
        return j.path("data").mapNotNull { n ->
            if (!nsfw && n.txt("attributes", "ageRating") == "R18") return@mapNotNull null
            val malId = n.path("relationships").path("mappings").path("data").map { it.path("id").asText() }.firstNotNullOfOrNull { mal[it] }
            // Prefer the MAL id so the page also works through AniList when it is back
            val slug = if (malId != null) "mal$malId" else "kt${n.path("id").asText()}"
            kitsuData(n, slug, malId)
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // TVMaze (TV shows, no key)
    // ---------------------------------------------------------------------------------------------------------

    private fun tvmazeData(n: JsonNode, slug: String) = data(
        slug = slug, title = n.txt("name"), poster = https(n.txt("image", "original").ifEmpty { n.txt("image", "medium") }),
        type = "tv", format = n.txt("type").ifEmpty { "TV" },
        duration = n.txt("runtime").ifEmpty { n.txt("averageRuntime") }, synopsis = stripHtml(n.txt("summary")),
        year = n.txt("premiered").take(4), score = n.txt("rating", "average"),
        studios = n.txt("network", "name").ifEmpty { n.txt("webChannel", "name") },
        genres = n.path("genres").map { it.asText() }, aired = n.txt("premiered"),
        status = when (n.txt("status")) { "Running" -> "Currently airing"; "Ended" -> "Finished airing"; "To Be Determined" -> "Unknown"; else -> n.txt("status") },
        language = n.txt("language"),
    )

    private fun tvmazePage(id: String, slug: String): AnimePageNewRoot? {
        val n = getJson("tvmaze", "https://api.tvmaze.com/shows/$id?embed=episodes") ?: return null
        val eps = n.path("_embedded").path("episodes").mapIndexed { i, e ->
            val label = "S${e.txt("season")}E${e.txt("number").ifEmpty { "?" }}"
            episode(slug, i + 1, "$label · ${e.txt("name")}", https(e.txt("image", "medium")).ifEmpty { null }, stripHtml(e.txt("summary")).ifEmpty { e.txt("airdate") })
        }
        val d = tvmazeData(n, slug).copy(episodes = eps.size.toString())
        return root(d, eps)
    }

    private fun tvmazeSearch(query: String): List<Data> {
        val j = getJson("tvmaze", "https://api.tvmaze.com/search/shows?q=${enc(query)}") ?: return emptyList()
        return j.take(12).map { tvmazeData(it.path("show"), "tvm${it.path("show").path("id").asInt()}") }
    }

    fun tvmazeAiringToday(): List<NekomoApi.CommonAnimePageData>? {
        val j = getJson("tvmaze", "https://api.tvmaze.com/schedule?country=US") ?: return null
        return j.mapNotNull { it.path("_embedded").path("show").takeIf { s -> !s.isMissingNode } ?: it.path("show").takeIf { s -> !s.isMissingNode } }
            .filter { it.txt("image", "medium").isNotEmpty() }
            .distinctBy { it.path("id").asInt() }.take(25).map {
                NekomoApi.CommonAnimePageData(it.txt("name"), https(it.txt("image", "medium")), "tvm${it.path("id").asInt()}", null)
            }
    }

    // ---------------------------------------------------------------------------------------------------------
    // TMDB (movies + TV, needs the user's own free API key)
    // ---------------------------------------------------------------------------------------------------------

    fun tmdbKey(ctx: Context? = null): String =
        (ctx ?: AcraApplication.context)?.let { PreferenceManager.getDefaultSharedPreferences(it).getString("tmdb_api_key", "") }?.trim().orEmpty()

    private fun tmdb(path: String, ctx: Context? = null, query: String = ""): JsonNode? {
        val key = tmdbKey(ctx).ifEmpty { return null }
        val bearer = key.startsWith("eyJ")
        val sep = if (query.isEmpty()) "" else "&"
        val url = "https://api.themoviedb.org/3/$path?language=en-US$sep$query" + if (bearer) "" else "&api_key=${enc(key)}"
        return getJson("tmdb", url, if (bearer) mapOf("Authorization" to "Bearer $key") else emptyMap())
    }

    private fun tmdbImage(path: String, size: String = "w500") = if (path.isBlank()) "" else "https://image.tmdb.org/t/p/$size$path"

    private fun tmdbMovieData(n: JsonNode, slug: String) = data(
        slug = slug, title = n.txt("title"), english = n.txt("original_title").takeIf { it != n.txt("title") },
        poster = tmdbImage(n.txt("poster_path")), banner = tmdbImage(n.txt("backdrop_path"), "w1280"), type = "movie", format = "Movie",
        episodes = "1", duration = n.txt("runtime"), synopsis = n.txt("overview"), year = n.txt("release_date").take(4),
        score = n.txt("vote_average").toDoubleOrNull()?.let { "%.1f".format(it) } ?: "", studios = n.path("production_companies").take(3).joinToString(", ") { it.txt("name") },
        genres = n.path("genres").map { it.txt("name") }, aired = n.txt("release_date"), status = n.txt("status"), language = n.txt("original_language"),
    )

    private fun tmdbTvData(n: JsonNode, slug: String) = data(
        slug = slug, title = n.txt("name"), poster = tmdbImage(n.txt("poster_path")), banner = tmdbImage(n.txt("backdrop_path"), "w1280"),
        type = "tv", format = "TV", episodes = n.txt("number_of_episodes"), duration = n.path("episode_run_time").firstOrNull()?.asText("") ?: "",
        synopsis = n.txt("overview"), year = n.txt("first_air_date").take(4), score = n.txt("vote_average").toDoubleOrNull()?.let { "%.1f".format(it) } ?: "",
        studios = n.path("networks").take(3).joinToString(", ") { it.txt("name") }, genres = n.path("genres").map { it.txt("name") },
        aired = n.txt("first_air_date"), status = when (n.txt("status")) { "Returning Series" -> "Currently airing"; "Ended" -> "Finished airing"; else -> n.txt("status") },
        language = n.txt("original_language"),
    )

    private fun tmdbTrailer(kind: String, id: String): String =
        tmdb("$kind/$id/videos")?.path("results")?.firstOrNull { it.txt("site") == "YouTube" && it.txt("type") == "Trailer" }
            ?.txt("key")?.takeIf { it.isNotBlank() }?.let { "youtube:$it" } ?: ""

    private fun tmdbMoviePage(id: String, slug: String): AnimePageNewRoot? {
        val n = tmdb("movie/$id") ?: return null
        return root(tmdbMovieData(n, slug).copy(trailer = tmdbTrailer("movie", id)), listOf(episode(slug, 1, n.txt("title"), tmdbImage(n.txt("backdrop_path"), "w780").ifEmpty { null }, n.txt("tagline"))))
    }

    private fun tmdbTvPage(id: String, slug: String): AnimePageNewRoot? {
        val n = tmdb("tv/$id") ?: return null
        val eps = ArrayList<AnimePageNewEpisodes>()
        n.path("seasons").filter { it.path("season_number").asInt() > 0 }.take(12).forEach { s ->
            val season = s.path("season_number").asInt()
            tmdb("tv/$id/season/$season")?.path("episodes")?.forEach { e ->
                eps.add(
                    episode(slug, eps.size + 1, "S${season}E${e.txt("episode_number")} · ${e.txt("name")}", tmdbImage(e.txt("still_path"), "w300").ifEmpty { null },
                        e.txt("overview").ifEmpty { e.txt("air_date") })
                )
            }
        }
        return root(tmdbTvData(n, slug).copy(episodes = eps.size.toString(), trailer = tmdbTrailer("tv", id)), eps)
    }

    private fun tmdbSearch(query: String, ctx: Context?): List<Data> {
        val j = tmdb("search/multi", ctx, "query=${enc(query)}&include_adult=${ContentPrefs.nsfw()}") ?: return emptyList()
        return j.path("results").mapNotNull { n ->
            val id = n.path("id").asInt()
            when (n.txt("media_type")) {
                "movie" -> tmdbMovieData(n, "tmm$id")
                "tv" -> tmdbTvData(n, "tmt$id")
                else -> null
            }
        }.take(15)
    }

    fun tmdbList(path: String): List<NekomoApi.CommonAnimePageData>? {
        val j = tmdb(path) ?: return null
        return j.path("results").mapNotNull { n ->
            val id = n.path("id").asInt()
            val type = n.txt("media_type").ifEmpty { if (path.startsWith("tv")) "tv" else "movie" }
            val title = n.txt("title").ifEmpty { n.txt("name") }
            val poster = tmdbImage(n.txt("poster_path"))
            if (title.isEmpty() || poster.isEmpty() || (type != "movie" && type != "tv")) null
            else NekomoApi.CommonAnimePageData(title, poster, (if (type == "movie") "tmm" else "tmt") + id, null)
        }
    }
}
