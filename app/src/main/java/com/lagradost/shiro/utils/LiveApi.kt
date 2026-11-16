package com.lagradost.shiro.utils

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.lagradost.shiro.utils.cs3.CsBridge
import com.lagradost.shiro.utils.mvvm.logError
import kotlin.random.Random as KotlinRandom

/**
 * Metadata catalogue backed by AniList GraphQL (graphql.anilist.co): trending, recents, search, details.
 *
 * Nekomo bundles NO streaming source. Playable content only comes from sources the user installs and
 * enables themselves (CloudStream extensions, Zangetsu sources, Aniyomi extensions); their slugs/tokens
 * ("cs3-..", "zg-..", "ay-..") are routed to the matching bridge.
 *
 * AniList slugs are "al<anilistId>"; a "-dub" suffix is stripped for compatibility with old saved data.
 */
object LiveApi {

    /** Search hides adult titles unless the user switched on "Show 18+" in Settings -> General. */
    private fun adultFilter() = if (com.lagradost.shiro.utils.ContentPrefs.nsfw()) "" else ", isAdult: false"

    private const val ANILIST_GQL = "https://graphql.anilist.co"

    private val mapper = JsonMapper.builder().addModule(KotlinModule.Builder().build())
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false).build()

    private val defaultHeaders get() = mapOf("User-Agent" to NekomoApi.USER_AGENT)

    // region ---------- HTTP helpers ----------

    private fun gql(url: String, query: String, variables: Map<String, Any?>): JsonNode? {
        if (com.lagradost.shiro.utils.meta.ApiHealth.blocked("anilist")) return null
        return try {
            // Plain JDK map: Jackson + kotlin-reflect fails on kotlin.collections.EmptyMap after R8
            val body = mapper.writeValueAsString(
                linkedMapOf<String, Any?>("query" to query, "variables" to LinkedHashMap(variables))
            )
            val headers =
                defaultHeaders
            val t0 = System.currentTimeMillis()
            val res = khttp.post(url, headers = headers, json = body, timeout = 10.0)
            if (res.statusCode == 429) {
                val retry = res.headers.entries.firstOrNull { it.key.equals("Retry-After", true) }?.value?.toIntOrNull() ?: 60
                com.lagradost.shiro.utils.meta.ApiHealth.rateLimited("anilist", retry)
                return null
            }
            if (res.statusCode != 200) {
                com.lagradost.shiro.utils.meta.ApiHealth.failure("anilist")
                return null
            }
            com.lagradost.shiro.utils.meta.ApiHealth.success("anilist")
            com.lagradost.shiro.utils.meta.ApiHealth.record("anilist", System.currentTimeMillis() - t0)
            mapper.readTree(res.text)
        } catch (e: Exception) {
            com.lagradost.shiro.utils.meta.ApiHealth.failure("anilist")
            logError(e)
            null
        }
    }

    private fun anilist(query: String, variables: Map<String, Any?>): JsonNode? =
        gql(ANILIST_GQL, query, variables)?.path("data")

    // endregion

    // region ---------- GraphQL documents ----------

    private const val MEDIA_FIELDS = """
        id idMal siteUrl
        title { romaji english native }
        coverImage { large extraLarge }
        bannerImage
        description(asHtml: false)
        genres format seasonYear status averageScore episodes duration
        trailer { id site }
        season studios(isMain: true) { nodes { name } }
        startDate { year month day }
        nextAiringEpisode { episode }
    """

    private const val TRENDING_QUERY = """
        query {
            Page(page: 1, perPage: 24) {
                media(sort: TRENDING_DESC, type: ANIME, isAdult: false) { $MEDIA_FIELDS }
            }
        }
    """

    private const val RECENTS_QUERY = """
        query {
            Page(page: 1, perPage: 30) {
                airingSchedules(sort: TIME_DESC, notYetAired: true) {
                    episode
                    media { $MEDIA_FIELDS }
                }
            }
        }
    """

    private const val UPCOMING_QUERY = """
        query {
            Page(page: 1, perPage: 40) {
                airingSchedules(sort: TIME_ASC, notYetAired: true) {
                    episode airingAt
                    media { $MEDIA_FIELDS }
                }
            }
        }
    """

    private const val RANDOM_QUERY = """
        query(${"$"}page: Int) {
            Page(page: ${"$"}page, perPage: 50) {
                media(sort: POPULARITY_DESC, type: ANIME, isAdult: false) { $MEDIA_FIELDS }
            }
        }
    """

    private const val MEDIA_BY_ID_QUERY = """
        query(${"$"}id: Int) { Media(id: ${"$"}id, type: ANIME) { $MEDIA_FIELDS } }
    """

    private const val MEDIA_BY_MAL_QUERY = """
        query(${"$"}id: Int) { Media(idMal: ${"$"}id, type: ANIME) { $MEDIA_FIELDS } }
    """

    private fun searchQuery() = """
        query(${"$"}q: String, ${"$"}genres: [String]) {
            Page(page: 1, perPage: 40) {
                media(search: ${"$"}q, type: ANIME, genre_in: ${"$"}genres${adultFilter()}) { $MEDIA_FIELDS }
            }
        }
    """

    private const val GENRES_QUERY = "query { GenreCollection }"

    // endregion

    // region ---------- mapping helpers ----------

    private fun JsonNode?.txt(vararg path: String): String {
        var node = this ?: return ""
        for (p in path) node = node.path(p)
        return if (node.isMissingNode || node.isNull) "" else node.asText("")
    }

    private fun normTitle(s: String?): String =
        (s ?: "").lowercase().replace(Regex("[^a-z0-9]"), "")

    private fun prettyStatus(s: String) = when (s) {
        "FINISHED" -> "Finished airing"
        "RELEASING" -> "Currently airing"
        "NOT_YET_RELEASED" -> "Not yet released"
        "CANCELLED" -> "Cancelled"
        "HIATUS" -> "On hiatus"
        else -> s
    }

    private fun prettyFormat(s: String) = when (s) {
        "TV_SHORT" -> "TV short"
        "MOVIE", "SPECIAL", "MUSIC" -> s.lowercase().replaceFirstChar { it.uppercase() }
        else -> s
    }

    private fun mediaSlug(media: JsonNode): String = "al${media.path("id").asInt()}"

    private fun mediaToData(media: JsonNode, slugOverride: String? = null): NekomoApi.Companion.Data {
        val mal = media.path("idMal").takeIf { !it.isNull }?.asInt()
        val al = media.path("id").asInt()
        val start = media.path("startDate")
        val aired = if (start.path("year").isMissingNode || start.path("year").isNull) ""
        else "${start.path("year").asInt()}-${start.path("month").asInt()}-${start.path("day").asInt()}"
        return NekomoApi.Companion.Data(
            id = al.toString(),
            slug = slugOverride ?: mediaSlug(media),
            title = media.txt("title", "romaji").ifEmpty { media.txt("title", "english") },
            title_english = media.txt("title", "english").ifEmpty { null },
            native_title = media.txt("title", "native"),
            poster = media.txt("coverImage", "extraLarge").ifEmpty { media.txt("coverImage", "large") },
            banner = media.txt("bannerImage"),
            ids = mapper.writeValueAsString(
                mapOf("mal" to mal?.toString(), "anilist" to al.toString())
            ),
            type = "anime",
            format = prettyFormat(media.txt("format")),
            episodes = media.txt("episodes"),
            episode_duration = media.txt("duration"),
            synopsis = media.txt("description")
                .replace(Regex("<br\\s*/?>"), "\n")
                .replace(Regex("<[^>]*>"), ""),
            language = "sub",
            synonyms = "",
            season = media.txt("season").lowercase().replaceFirstChar { it.uppercase() },
            release_year = media.txt("seasonYear").ifEmpty { null },
            score = media.txt("averageScore"),
            rating = "",
            studios = media.path("studios").path("nodes").mapNotNull { it.path("name").asText(null) }.joinToString(", "),
            genres = media.path("genres").mapNotNull { it.asText(null) }.joinToString(","),
            aired = aired,
            status = prettyStatus(media.txt("status")),
            trailer = media.path("trailer").let { t ->
                val id = t.path("id").asText("")
                val site = t.path("site").asText("").lowercase()
                if (id.isNotBlank() && (site == "youtube" || site == "dailymotion")) "$site:$id" else ""
            },
            total_views = "0",
            created_at = "",
            updated_at = ""
        )
    }

    // endregion

    // region ---------- catalog ----------

    fun getTrending(): NekomoApi.Companion.ShowHolder? {
        val data = anilist(TRENDING_QUERY, emptyMap())
        val list = (if (data != null) data.path("Page").path("media").map { mediaToData(it) }
        else com.lagradost.shiro.utils.meta.MetaHub.browse("sort=-userCount")) // AniList down: Kitsu
            .filter { !com.lagradost.shiro.utils.home.HomeFilters.isBlocked(it.title, it.title_english, it.native_title) }
        if (list.isEmpty()) return null
        return NekomoApi.Companion.ShowHolder("success", "", list)
    }

    fun getRandom(): NekomoApi.Companion.Random? {
        val page = KotlinRandom.nextInt(1, 8)
        val data = anilist(RANDOM_QUERY, mapOf("page" to page))
        if (data == null) {
            val d = com.lagradost.shiro.utils.meta.MetaHub.browse("sort=-userCount")
                .filter { it.banner.isNotBlank() || it.poster.isNotBlank() }.takeIf { it.isNotEmpty() } ?: return null
            return NekomoApi.Companion.Random("success", "", d[KotlinRandom.nextInt(0, d.size)])
        }
        val media = data.path("Page").path("media")
        if (media.size() == 0) return null
        return NekomoApi.Companion.Random(
            "success", "", mediaToData(media[KotlinRandom.nextInt(0, media.size())])
        )
    }

    fun getRecents(): List<NekomoApi.Companion.AnimeHolder>? {
        val data = anilist(RECENTS_QUERY, emptyMap()) ?: return recentsFromBackup()
        val holders = mutableListOf<NekomoApi.Companion.AnimeHolder>()
        val seen = HashSet<String>()
        data.path("Page").path("airingSchedules").forEach { sched ->
            val media = sched.path("media")
            val ep = sched.path("episode").asInt()
            val slug = mediaSlug(media)
            if (ep <= 0 || !seen.add("$slug-$ep")) return@forEach
            val d = mediaToData(media)
            if (com.lagradost.shiro.utils.home.HomeFilters.isBlocked(d.title, d.title_english, d.native_title)) return@forEach
            holders.add(
                NekomoApi.Companion.AnimeHolder(
                    anime = NekomoApi.Companion.Anime(
                        title = d.title,
                        slug = slug,
                        poster = d.poster,
                        synopsis = d.synopsis,
                        format = d.format
                    ),
                    episode = NekomoApi.Companion.Episode(
                        id = "$slug-$ep",
                        slug = slug,
                        title = "",
                        episode = ep.toString(),
                        image = d.poster,
                        insight = null,
                        sources = "[{\"slug\":\"gogostream\",\"source\":\"al${media.path("id").asInt()}|$ep|sub\"}]",
                        ext = null,
                        views = "0",
                        created_at = "",
                        updated_at = ""
                    )
                )
            )
        }
        return holders
    }

    /**
     * Upcoming episode airings, soonest first, one card per title. The air time lands in
     * `english` so adapters that show the subtitle render "Ep 8 · Fri 12 Dec" under the name.
     */
    fun getUpcoming(): List<NekomoApi.CommonAnimePageData>? {
        val data = anilist(UPCOMING_QUERY, emptyMap()) ?: return null
        val out = LinkedHashMap<String, NekomoApi.CommonAnimePageData>()
        val now = System.currentTimeMillis() / 1000L
        data.path("Page").path("airingSchedules").forEach { sched ->
            val media = sched.path("media")
            val ep = sched.path("episode").asInt()
            val at = sched.path("airingAt").asLong()
            val slug = mediaSlug(media)
            if (ep <= 0 || at <= now || slug.isBlank() || out.containsKey(slug)) return@forEach
            val d = mediaToData(media)
            if (com.lagradost.shiro.utils.home.HomeFilters.isBlocked(d.title, d.title_english, d.native_title)) return@forEach
            val whenStr = java.text.SimpleDateFormat("EEE d MMM", java.util.Locale.getDefault())
                .format(java.util.Date(at * 1000))
            out[slug] = NekomoApi.CommonAnimePageData(d.title, d.poster, slug, "Ep $ep · $whenStr")
        }
        return out.values.toList().takeIf { it.isNotEmpty() }
    }

    /** "Recently updated" while AniList is unavailable: what Kitsu says is airing now. */
    private fun recentsFromBackup(): List<NekomoApi.Companion.AnimeHolder>? =
        com.lagradost.shiro.utils.meta.MetaHub.browse("filter%5Bstatus%5D=current&sort=-userCount")
            .filter { !com.lagradost.shiro.utils.home.HomeFilters.isBlocked(it.title, it.title_english, it.native_title) }
            .map { d ->
                NekomoApi.Companion.AnimeHolder(
                    anime = NekomoApi.Companion.Anime(title = d.title, slug = d.slug, poster = d.poster, synopsis = d.synopsis, format = d.format),
                    episode = NekomoApi.Companion.Episode(
                        id = "${d.slug}-1", slug = d.slug, title = "", episode = "1", image = d.poster, insight = null,
                        sources = "[]", ext = null, views = "0", created_at = "", updated_at = ""
                    )
                )
            }.takeIf { it.isNotEmpty() }

    /** Title fields shared by every AniList list query (used by the home sections). */
    val homeMediaFields: String get() = MEDIA_FIELDS

    /** Runs an AniList `Page { <listPath> { ... } }` query and maps the media (titles on the home blocklist are dropped). */
    fun listQuery(query: String, vars: Map<String, Any?> = emptyMap(), listPath: String = "media"): List<NekomoApi.Companion.Data>? {
        val data = anilist(query, vars) ?: return null
        return data.path("Page").path(listPath).map { mediaToData(it) }
            .filter { !com.lagradost.shiro.utils.home.HomeFilters.isBlocked(it.title, it.title_english, it.native_title) }
    }

    /** AniList "recommendations" for a title the user already likes (seeded from favorites / offline library). */
    fun recommendationsFor(alId: Int): List<NekomoApi.Companion.Data> {
        val q = "query(${"$"}id: Int) { Media(id: ${"$"}id, type: ANIME) { recommendations(sort: RATING_DESC, perPage: 12) { nodes { mediaRecommendation { $MEDIA_FIELDS } } } } }"
        val nodes = anilist(q, mapOf("id" to alId))?.path("Media")?.path("recommendations")?.path("nodes") ?: return emptyList()
        return nodes.mapNotNull { it.path("mediaRecommendation").takeIf { m -> !m.isMissingNode && !m.isNull } }
            .map { mediaToData(it) }
            .filter { !com.lagradost.shiro.utils.home.HomeFilters.isBlocked(it.title, it.title_english, it.native_title) }
    }

    fun getGenres(): List<String>? {
        val data = anilist(GENRES_QUERY, emptyMap()) ?: return null
        // The adult genre only shows when 18+ content is enabled (Settings > General)
        return data.path("GenreCollection").mapNotNull { it.asText(null) }.filter { com.lagradost.shiro.utils.ContentPrefs.nsfw() || !it.equals("Hentai", true) }
    }

    // endregion

    // region ---------- search ----------

    fun search(query: String, genres: List<String>?): List<NekomoApi.Companion.Data>? {
        val data = anilist(searchQuery(), mapOf("q" to query.takeIf { it.isNotBlank() }, "genres" to (genres?.takeIf { it.isNotEmpty() })))
            ?: return null
        return data.path("Page").path("media").map { mediaToData(it) }
    }

    // endregion

    // region ---------- anime page ----------

    private fun anilistMediaById(id: Int): JsonNode? =
        anilist(MEDIA_BY_ID_QUERY, mapOf("id" to id))?.path("Media")

    private fun anilistMediaByMal(id: Int): JsonNode? =
        anilist(MEDIA_BY_MAL_QUERY, mapOf("id" to id))?.path("Media")


    /**
     * Metadata-only page for an AniList title. Streams come from sources the user installs, so the episode
     * list is empty here; the page's "Choose source" button finds the title in an installed source.
     */
    fun getAnimePage(slug: String, withEpisodes: Boolean = true, enrich: Boolean = true): NekomoApi.Companion.AnimePageNewRoot? {
        val bare = slug.removeSuffix("-dub")

        if (Regex("""^mal\d+$""").matches(bare)) return getAnimePageByMal(bare.removePrefix("mal"))
        if (com.lagradost.shiro.utils.meta.MetaHub.isMetaSlug(bare)) return com.lagradost.shiro.utils.meta.MetaHub.getPage(bare)
        // Slugs from user-installed sources are self-contained tokens
        // Extension pages keep their own episodes; the built-in catalogue fills in whatever metadata they lack
        val extPage = when {
            CsBridge.isCs3Slug(bare) -> CsBridge.getAnimePage(bare)
            com.lagradost.shiro.utils.zg.ZangetsuBridge.isZgSlug(bare) -> com.lagradost.shiro.utils.zg.ZangetsuBridge.getAnimePage(bare)
            com.lagradost.shiro.utils.aniyomi.AniyomiBridge.isAySlug(bare) -> com.lagradost.shiro.utils.aniyomi.AniyomiBridge.getAnimePage(bare)
            else -> null
        }
        if (isExtensionSlug(bare)) {
            return if (extPage != null && enrich) com.lagradost.shiro.utils.meta.MetaEnricher.enrich(extPage, bare) else extPage
        }

        return try {
            val alId = bare.removePrefix("al").toIntOrNull() ?: return null
            // AniList down or rate limited: show the last good copy of this page
            val media = anilistMediaById(alId)?.takeIf { !it.isMissingNode && !it.isNull }
                ?: return com.lagradost.shiro.utils.meta.MetaCache.get(slug)
            val d = mediaToData(media, slugOverride = slug)
            val total = media.path("episodes").takeIf { !it.isNull && !it.isMissingNode }?.asInt()
                ?: media.path("nextAiringEpisode").path("episode").asInt(0).let { if (it > 1) it - 1 else null }
            val episodes = if (!withEpisodes) emptyList() else com.lagradost.shiro.utils.meta.MetaHub.episodesForMal(
                slug, media.path("idMal").takeIf { !it.isNull && !it.isMissingNode }?.asText(), total
            )
            NekomoApi.Companion.AnimePageNewRoot(
                "success", "",
                NekomoApi.Companion.AnimePageNewData(anime = dataToAnimePage(d, slug), episodes = episodes)
            ).also { com.lagradost.shiro.utils.meta.MetaCache.put(slug, it) }
        } catch (e: Exception) {
            logError(e)
            null
        }
    }

    private fun anilistSearchMedia(titles: List<String>): JsonNode? {
        for (t in titles.filter { it.isNotBlank() }) {
            val data = anilist(
                """
                query(${"$"}q: String) {
                    Page(perPage: 5) { media(search: ${"$"}q, type: ANIME) { $MEDIA_FIELDS } }
                }
                """,
                mapOf("q" to t)
            ) ?: continue
            val list = data.path("Page").path("media")
            val norm = normTitle(t)
            val match = list.firstOrNull {
                normTitle(it.txt("title", "romaji")) == norm ||
                        normTitle(it.txt("title", "english")) == norm
            } ?: list.firstOrNull()
            if (match != null) return match
        }
        return null
    }

    fun getAnimePageByMal(malId: String): NekomoApi.Companion.AnimePageNewRoot? {
        val media = malId.toIntOrNull()?.let { anilistMediaByMal(it) }?.takeIf { !it.isMissingNode && !it.isNull }
            ?: return com.lagradost.shiro.utils.meta.MetaHub.getPage("mal$malId")
        val alId = media.path("id").asInt()
        return getAnimePage("al$alId")
    }

    internal fun dataToAnimePage(d: NekomoApi.Companion.Data, slug: String): NekomoApi.Companion.AnimePageNew {
        return NekomoApi.Companion.AnimePageNew(
            id = d.id, slug = slug, title = d.title, title_english = d.title_english,
            native_title = d.native_title, poster = d.poster, banner = d.banner,
            ids = d.ids, type = d.type, format = d.format, episodes = d.episodes,
            episode_duration = d.episode_duration, synopsis = d.synopsis,
            language = d.language, synonyms = d.synonyms, season = d.season,
            release_year = d.release_year, score = d.score, rating = d.rating,
            studios = d.studios, genres = d.genres, aired = d.aired, status = d.status,
            trailer = d.trailer, total_views = d.total_views,
            created_at = d.created_at, updated_at = d.updated_at
        )
    }

    // endregion

    // region ---------- stream resolution ----------

    /**
     * Nekomo ships no streaming source of its own. Episode tokens only exist for content that comes from
     * sources the user installed themselves (CloudStream extensions, Zangetsu sources, Aniyomi extensions),
     * so anything else simply resolves to nothing.
     */
    fun resolveStreams(token: String, isCasting: Boolean, callback: (ExtractorLink) -> Unit): Boolean {
        if (CsBridge.isCs3Slug(token)) return CsBridge.resolveStreams(token, isCasting, callback)
        if (com.lagradost.shiro.utils.zg.ZangetsuBridge.isZgSlug(token)) return com.lagradost.shiro.utils.zg.ZangetsuBridge.resolveStreams(token, callback)
        if (com.lagradost.shiro.utils.aniyomi.AniyomiBridge.isAySlug(token)) return com.lagradost.shiro.utils.aniyomi.AniyomiBridge.resolveStreams(token, callback)
        return false
    }

    // endregion
}
