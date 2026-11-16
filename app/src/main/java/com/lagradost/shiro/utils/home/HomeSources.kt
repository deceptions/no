package com.lagradost.shiro.utils.home

import android.content.Context
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.lagradost.shiro.utils.LiveApi
import com.lagradost.shiro.utils.LocalLibrary
import com.lagradost.shiro.utils.NekomoApi
import com.lagradost.shiro.utils.cs3.CsBridge
import com.lagradost.shiro.utils.mvvm.logError

/**
 * What kind of provider a home source is. The home settings screen shows this next to every source so users can
 * always tell an official service from a metadata database from a third-party (user-installed) source.
 */
enum class SourceKind(val label: String, val description: String) {
    OFFICIAL("Official service", "Opens the service's own website or app. Nekomo does not stream from it."),
    METADATA("Metadata provider", "Titles, artwork and recommendations only. No video."),
    THIRD_PARTY("Your sources", "Provided by extensions you installed yourself. Nekomo does not control them."),
    LOCAL("On this device", "Your own history and library.")
}

data class HomeSectionDef(
    val id: String,
    val title: String,
    val sourceId: String,
    val defaultEnabled: Boolean = true,
)

/** A pluggable provider of home sections. Add a new API by implementing this and registering it in [HomeRegistry]. */
interface HomeSource {
    val id: String
    val name: String
    val kind: SourceKind
    val defaultEnabled: Boolean

    /** Attribution / terms users can read; shown in home settings. */
    val attribution: String
    val termsUrl: String

    /** True for providers of movies / TV shows (their rows need the "Show movies and TV shows" switch). */
    val nonAnime: Boolean get() = false

    fun sections(): List<HomeSectionDef>

    /** Blocking; returns null on failure. Called off the main thread. */
    fun fetch(context: Context, section: HomeSectionDef): List<NekomoApi.CommonAnimePageData>?
}

/** Titles that should never show up on the home page (e.g. endless weekly series that flood "recently updated"). */
object HomeFilters {
    private val blocked = listOf("sazae-san", "sazae san", "sazaesan", "サザエさん")

    fun isBlocked(vararg titles: String?): Boolean =
        titles.any { t -> t != null && blocked.any { t.lowercase().contains(it) } }
}

object HomeRegistry {
    val sources: List<HomeSource> by lazy {
        listOf(
            LocalHomeSource, AniListHomeSource, JikanHomeSource, KitsuHomeSource, TvMazeHomeSource, TmdbHomeSource,
            OfficialHomeSource, CloudStreamHomeSource
        )
    }

    fun source(id: String) = sources.firstOrNull { it.id == id }
    fun allSections(): List<HomeSectionDef> = sources.flatMap { runCatching { it.sections() }.getOrDefault(emptyList()) }
}

private fun NekomoApi.Companion.Data.toCard() =
    NekomoApi.CommonAnimePageData(title, poster, slug, title_english)

// ---------------------------------------------------------------------------------------------------------------

object LocalHomeSource : HomeSource {
    override val id = "local"
    override val name = "Your library"
    override val kind = SourceKind.LOCAL
    override val defaultEnabled = true
    override val attribution = "Stored on this device only."
    override val termsUrl = ""

    const val CONTINUE = "continue"
    const val SUBSCRIBED = "subscribed"
    const val FAVORITES = "favorites"

    override fun sections() = listOf(
        HomeSectionDef(CONTINUE, "Continue watching", id),
        HomeSectionDef(SUBSCRIBED, "Subscribed", id),
        HomeSectionDef(FAVORITES, "Favorites", id),
    )

    // These three are rendered natively by the home screen from local data.
    override fun fetch(context: Context, section: HomeSectionDef): List<NekomoApi.CommonAnimePageData>? = emptyList()
}

// ---------------------------------------------------------------------------------------------------------------

object AniListHomeSource : HomeSource {
    override val id = "anilist"
    override val name = "AniList"
    override val kind = SourceKind.METADATA
    override val defaultEnabled = true
    override val attribution = "Anime data from AniList (anilist.co), used under the AniList API terms."
    override val termsUrl = "https://anilist.co/terms"

    val genres = listOf(
        "Action", "Adventure", "Comedy", "Drama", "Fantasy", "Horror", "Mahou Shoujo", "Mecha", "Music", "Mystery",
        "Psychological", "Romance", "Sci-Fi", "Slice of Life", "Sports", "Supernatural", "Thriller"
    )
    private val defaultGenres = setOf("Action", "Romance", "Comedy", "Fantasy")

    override fun sections() = listOf(
        HomeSectionDef("trending", "Trending now", id),
        HomeSectionDef("recent", "Recently updated", id),
        HomeSectionDef("popular", "Popular", id),
        HomeSectionDef("recommended", "Recommended for you", id),
        HomeSectionDef("season", "Airing this season", id, defaultEnabled = false),
        HomeSectionDef("upcoming", "Coming up", id, defaultEnabled = false),
        HomeSectionDef("top_rated", "Top rated", id, defaultEnabled = false),
    ) + genres.map { HomeSectionDef("genre:$it", it, id, defaultEnabled = it in defaultGenres) }

    private fun page(sort: String, extra: String = ""): String =
        "query { Page(page: 1, perPage: 30) { media(sort: $sort, type: ANIME, isAdult: false$extra) { ${LiveApi.homeMediaFields} } } }"

    override fun fetch(context: Context, section: HomeSectionDef): List<NekomoApi.CommonAnimePageData>? {
        if (section.id == "upcoming") return LiveApi.getUpcoming()
        val data: List<NekomoApi.Companion.Data>? = when {
            section.id == "trending" -> LiveApi.getTrending()?.data
            section.id == "recent" -> LiveApi.getRecents()?.map {
                NekomoApi.Companion.Data(
                    id = "", slug = it.anime.slug, title = it.anime.title, title_english = null, native_title = "",
                    poster = it.anime.poster, banner = "", ids = "{}", type = "", format = "", episodes = "",
                    episode_duration = "", synopsis = "", language = "", synonyms = "", season = "", release_year = null,
                    score = "", rating = "", studios = "", genres = "", aired = "", status = "", trailer = "",
                    total_views = "0", created_at = "", updated_at = ""
                )
            }
            section.id == "popular" -> LiveApi.listQuery(page("POPULARITY_DESC"))
            section.id == "top_rated" -> LiveApi.listQuery(page("SCORE_DESC"))
            section.id == "season" -> LiveApi.listQuery(page("POPULARITY_DESC", ", status: RELEASING"))
            section.id == "recommended" -> recommended(context)
            section.id.startsWith("genre:") ->
                LiveApi.listQuery(page("POPULARITY_DESC", ", genre: \"${section.id.removePrefix("genre:")}\""))
            else -> null
        }
        val cards = data?.map { it.toCard() }?.distinctBy { it.slug }
        return if (!cards.isNullOrEmpty()) cards else KitsuHomeSource.fallbackFor(section) ?: JikanHomeSource.fallbackFor(section)
    }

    /** Seeded by what the user already likes (favorites + offline library); falls back to top rated. */
    private fun recommended(context: Context): List<NekomoApi.Companion.Data>? {
        val seeds = (LocalLibrary.all(context).map { it.slug } +
                (NekomoApi.cachedHome?.favorites?.mapNotNull { it?.slug } ?: emptyList()))
            .mapNotNull { Regex("^al(\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull() }
            .distinct().take(3)
        val recs = seeds.flatMap { LiveApi.recommendationsFor(it) }.distinctBy { it.slug }
            .filter { r -> seeds.none { r.slug == "al$it" } }
        return recs.ifEmpty { LiveApi.listQuery(page("SCORE_DESC")) }
    }
}

// ---------------------------------------------------------------------------------------------------------------

/** MyAnimeList data through the community Jikan API (https://jikan.moe). Rate limited, so requests are spaced out. */
object JikanHomeSource : HomeSource {
    override val id = "jikan"
    override val name = "MyAnimeList (Jikan)"
    override val kind = SourceKind.METADATA
    override val defaultEnabled = false
    override val attribution = "Data from MyAnimeList via the unofficial Jikan API (jikan.moe). Cached and rate limited."
    override val termsUrl = "https://docs.api.jikan.moe/"

    private val mapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()
    private val lock = Any()
    private var lastCall = 0L

    override fun sections() = listOf(
        HomeSectionDef("jikan:top", "MAL top rated", id, defaultEnabled = false),
        HomeSectionDef("jikan:airing", "MAL top airing", id, defaultEnabled = false),
        HomeSectionDef("jikan:popular", "MAL most popular", id, defaultEnabled = false),
        HomeSectionDef("jikan:upcoming", "MAL upcoming", id, defaultEnabled = false),
    )

    private fun url(section: HomeSectionDef) = when (section.id) {
        "jikan:top" -> "https://api.jikan.moe/v4/top/anime?filter=favorite&sfw=true&limit=25"
        "jikan:airing" -> "https://api.jikan.moe/v4/top/anime?filter=airing&sfw=true&limit=25"
        "jikan:popular" -> "https://api.jikan.moe/v4/top/anime?filter=bypopularity&sfw=true&limit=25"
        "jikan:upcoming" -> "https://api.jikan.moe/v4/top/anime?filter=upcoming&sfw=true&limit=25"
        else -> null
    }

    fun fallbackFor(section: HomeSectionDef): List<NekomoApi.CommonAnimePageData>? {
        val target = when (section.id) {
            "top_rated", "recommended" -> "jikan:top"
            "season", "recent" -> "jikan:airing"
            else -> "jikan:popular"
        }
        return fetch(com.lagradost.shiro.AcraApplication.context ?: return null, HomeSectionDef(target, "", id))
    }

    override fun fetch(context: Context, section: HomeSectionDef): List<NekomoApi.CommonAnimePageData>? {
        val url = url(section) ?: return null
        return try {
            val text = synchronized(lock) {
                val wait = 450 - (System.currentTimeMillis() - lastCall)
                if (wait > 0) Thread.sleep(wait)
                val res = khttp.get(url, headers = mapOf("User-Agent" to NekomoApi.USER_AGENT), timeout = 10.0)
                lastCall = System.currentTimeMillis()
                if (res.statusCode != 200) return null
                res.text
            }
            val node: JsonNode = mapper.readTree(text).path("data")
            node.mapNotNull { n ->
                val mal = n.path("mal_id").asInt(0).takeIf { it > 0 } ?: return@mapNotNull null
                val title = n.path("title_english").asText("").ifEmpty { n.path("title").asText("") }
                val poster = n.path("images").path("jpg").path("large_image_url").asText("")
                if (title.isEmpty() || HomeFilters.isBlocked(title, n.path("title").asText(""))) null
                else NekomoApi.CommonAnimePageData(title, poster, "mal$mal", n.path("title").asText(null))
            }.distinctBy { it.slug }
        } catch (e: Exception) {
            logError(e)
            null
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------

/** Kitsu's public API (https://kitsu.docs.apiary.io). Titles with a MyAnimeList mapping open through the AniList page. */
object KitsuHomeSource : HomeSource {
    override val id = "kitsu"
    override val name = "Kitsu"
    override val kind = SourceKind.METADATA
    override val defaultEnabled = false
    override val attribution = "Anime data from Kitsu (kitsu.app) through its public API. Cached."
    override val termsUrl = "https://kitsu.app/terms"

    private val mapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()

    override fun sections() = listOf(
        HomeSectionDef("kitsu:popular", "Kitsu most popular", id, defaultEnabled = false),
        HomeSectionDef("kitsu:rating", "Kitsu highest rated", id, defaultEnabled = false),
        HomeSectionDef("kitsu:airing", "Kitsu currently airing", id, defaultEnabled = false),
        HomeSectionDef("kitsu:upcoming", "Kitsu upcoming", id, defaultEnabled = false),
    )

    private fun query(section: HomeSectionDef) = when (section.id) {
        "kitsu:popular" -> "sort=-userCount"
        "kitsu:rating" -> "sort=-averageRating"
        "kitsu:airing" -> "filter%5Bstatus%5D=current&sort=-userCount"
        "kitsu:upcoming" -> "filter%5Bstatus%5D=upcoming&sort=-userCount"
        else -> null
    }

    override fun fetch(context: Context, section: HomeSectionDef): List<NekomoApi.CommonAnimePageData>? =
        query(section)?.let { fetchQuery(it) }

    /** Stand-in for an AniList row while AniList is unavailable. */
    fun fallbackFor(section: HomeSectionDef): List<NekomoApi.CommonAnimePageData>? {
        val id = section.id
        val q = when {
            id == "top_rated" || id == "recommended" -> "sort=-averageRating"
            id == "season" || id == "recent" -> "filter%5Bstatus%5D=current&sort=-userCount"
            id.startsWith("genre:") -> "filter%5Bcategories%5D=" + id.removePrefix("genre:").lowercase().replace(" ", "-") + "&sort=-userCount"
            else -> "sort=-userCount"
        }
        return fetchQuery(q)
    }

    private fun fetchQuery(q: String): List<NekomoApi.CommonAnimePageData>? {
        val url = "https://kitsu.io/api/edge/anime?$q&page%5Blimit%5D=20&include=mappings" +
                "&fields%5Bmappings%5D=externalSite,externalId&fields%5Banime%5D=canonicalTitle,titles,posterImage,mappings,ageRating"
        return try {
            val res = khttp.get(url, headers = mapOf("User-Agent" to NekomoApi.USER_AGENT, "Accept" to "application/vnd.api+json"), timeout = 20.0)
            if (res.statusCode != 200) return null
            val root = mapper.readTree(res.text)
            val malByMapping = root.path("included").filter { it.path("attributes").path("externalSite").asText() == "myanimelist/anime" }
                .associate { it.path("id").asText() to it.path("attributes").path("externalId").asText() }
            val nsfw = com.lagradost.shiro.utils.ContentPrefs.nsfw()
            root.path("data").mapNotNull { n ->
                val attrs = n.path("attributes")
                if (!nsfw && attrs.path("ageRating").asText() == "R18") return@mapNotNull null
                val mal = n.path("relationships").path("mappings").path("data").map { it.path("id").asText() }
                    .firstNotNullOfOrNull { malByMapping[it] }?.toIntOrNull() ?: return@mapNotNull null
                val titles = attrs.path("titles")
                val title = titles.path("en").asText("").ifEmpty { attrs.path("canonicalTitle").asText("") }
                if (title.isEmpty() || HomeFilters.isBlocked(title, attrs.path("canonicalTitle").asText(""))) return@mapNotNull null
                NekomoApi.CommonAnimePageData(title, attrs.path("posterImage").path("large").asText(""), "mal$mal", attrs.path("canonicalTitle").asText(null))
            }.distinctBy { it.slug }
        } catch (e: Exception) {
            logError(e)
            null
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------

/** TV shows from TVMaze (free, no key). Off on the home page until the user turns it on (and "Show movies and TV shows" must be on too). */
object TvMazeHomeSource : HomeSource {
    override val id = "tvmaze"
    override val name = "TVMaze"
    override val kind = SourceKind.METADATA
    override val defaultEnabled = false
    override val nonAnime = true
    override val attribution = "TV show data from TVMaze (tvmaze.com), licensed CC BY-SA."
    override val termsUrl = "https://www.tvmaze.com/api"

    override fun sections() = listOf(HomeSectionDef("tvmaze:today", "TV shows airing today", id))

    override fun fetch(context: Context, section: HomeSectionDef) = com.lagradost.shiro.utils.meta.MetaHub.tvmazeAiringToday()
}

/** Movies and TV shows from TMDB. Needs your own free API key (Settings -> General). Off on the home page until the user turns it on. */
object TmdbHomeSource : HomeSource {
    override val id = "tmdb"
    override val name = "TMDB"
    override val kind = SourceKind.METADATA
    override val defaultEnabled = false
    override val nonAnime = true
    override val attribution = "This product uses the TMDB API but is not endorsed or certified by TMDB. Add your free API key in Settings -> General."
    override val termsUrl = "https://www.themoviedb.org/api-terms-of-use"

    override fun sections() = listOf(
        HomeSectionDef("tmdb:trending", "Trending movies and shows", id),
        HomeSectionDef("tmdb:movies", "Popular movies", id),
        HomeSectionDef("tmdb:tv", "Popular TV shows", id),
        HomeSectionDef("tmdb:toptv", "Top rated TV shows", id, defaultEnabled = false),
    )

    override fun fetch(context: Context, section: HomeSectionDef): List<NekomoApi.CommonAnimePageData>? = com.lagradost.shiro.utils.meta.MetaHub.tmdbList(
        when (section.id) {
            "tmdb:trending" -> "trending/all/week"
            "tmdb:movies" -> "movie/popular"
            "tmdb:tv" -> "tv/popular"
            "tmdb:toptv" -> "tv/top_rated"
            else -> return null
        }
    )
}

// ---------------------------------------------------------------------------------------------------------------

/**
 * Official streaming services. There is no public API to stream from them, so these only open the service's own
 * website or app. Slugs that start with "http" are treated as links by the home screen.
 */
object OfficialHomeSource : HomeSource {
    override val id = "official"
    override val name = "Official services"
    override val kind = SourceKind.OFFICIAL
    override val defaultEnabled = true
    override val attribution = "Links to the services' own sites. Not affiliated with or endorsed by any of them."
    override val termsUrl = ""

    override fun sections() = listOf(HomeSectionDef("official:watch", "Watch officially", id))

    override fun fetch(context: Context, section: HomeSectionDef) = listOf(
        "Crunchyroll" to "https://www.crunchyroll.com/",
        "HIDIVE" to "https://www.hidive.com/",
        "Netflix" to "https://www.netflix.com/",
        "Prime Video" to "https://www.primevideo.com/",
    ).map { (n, u) -> NekomoApi.CommonAnimePageData(n, "", u, null) }
}

// ---------------------------------------------------------------------------------------------------------------

/** Home rows that the user's own installed CloudStream providers publish (like CloudStream's home page). */
object CloudStreamHomeSource : HomeSource {
    override val id = "cloudstream"
    override val name = "Your CloudStream providers"
    override val kind = SourceKind.THIRD_PARTY
    override val defaultEnabled = false
    override val attribution = "Rows come from extensions you installed. Nekomo does not control their content."
    override val termsUrl = ""

    override fun sections() = CsBridge.homeSections().map {
        HomeSectionDef("cs:${it.apiName}:${it.index}", it.title, id, defaultEnabled = false)
    }

    override fun fetch(context: Context, section: HomeSectionDef): List<NekomoApi.CommonAnimePageData>? {
        val parts = section.id.removePrefix("cs:").split(":")
        val index = parts.lastOrNull()?.toIntOrNull() ?: return null
        val api = parts.dropLast(1).joinToString(":")
        return CsBridge.homeItems(api, index)
    }
}
