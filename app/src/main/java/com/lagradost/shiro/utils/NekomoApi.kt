package com.lagradost.shiro.utils

import BOOKMARK_KEY
import DataStore.getKey
import DataStore.getKeys
import DataStore.removeKey
import DataStore.setKey
import DataStore.toKotlinObject
import LEGACY_BOOKMARKS
import LEGACY_RECENTS
import LEGACY_SUBS
import SUBSCRIPTIONS_BOOKMARK_KEY
import SUBSCRIPTIONS_KEY
import VIEW_LST_KEY
import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import android.widget.Toast
import androidx.preference.PreferenceManager
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.shiro.BuildConfig
import com.lagradost.shiro.ui.BookmarkedTitle
import com.lagradost.shiro.ui.LastEpisodeInfo
import com.lagradost.shiro.ui.LastEpisodeInfoLegacy
import com.lagradost.shiro.ui.MainActivity.Companion.activity
import com.lagradost.shiro.utils.AppUtils.allApi
import com.lagradost.shiro.utils.AppUtils.md5
import com.lagradost.shiro.utils.AppUtils.settingsManager
import com.lagradost.shiro.utils.Coroutines.main
import com.lagradost.shiro.utils.cs3.CsBridge
import com.lagradost.shiro.utils.mvvm.logError
import khttp.structures.cookie.CookieJar
import java.net.URLEncoder
import kotlin.concurrent.thread

const val SHIRO_TIMEOUT_TIME = 60.0
const val MAIN_URL = "********"

class NekomoApi {

    data class Token(
        @JsonProperty("headers") val headers: Map<String, String>,
        @JsonProperty("cookies") val cookies: CookieJar,
        @JsonProperty("token") val token: String,
    )

    data class Episode(@JsonProperty("file") val file: String)

    data class Donor(@JsonProperty("id") val id: String)

    data class ShiroSearchResponseShow(
        @JsonProperty("image") override val image: String,
        @JsonProperty("_id") val _id: String,
        @JsonProperty("slug") override val slug: String,
        @JsonProperty("name") override val name: String,
        @JsonProperty("english") override val english: String?,
    ) : CommonAnimePage

    data class ShiroHomePageData(
        @JsonProperty("trending_animes") val trending_animes: List<AnimePageData>,
        @JsonProperty("ongoing_animes") val ongoing_animes: List<AnimePageData>,
        @JsonProperty("latest_animes") val latest_animes: List<AnimePageData>,
        @JsonProperty("latest_episodes") val latest_episodes: List<ShiroEpisodes>,
    )

    data class ShiroHomePage(
        @JsonProperty("status") val status: String,
        @JsonProperty("data") val data: ShiroHomePageData,
        @JsonProperty("random") var random: AnimePage?,
        @JsonProperty("favorites") var favorites: List<BookmarkedTitle?>?,
        @JsonProperty("subscribed") var subscribed: List<BookmarkedTitle?>?,
        @JsonProperty("recentlySeen") var recentlySeen: List<LastEpisodeInfo?>?,
        // A hack for android TV MasterCardAdapter
        // @JsonProperty("searchResults") val searchResults: List<ShiroSearchResponseShow?>?
    )


    data class ShiroHomePageNew(
        @JsonProperty("random") var random: Random?,
        @JsonProperty("recents") var recents: List<AnimeHolder>?,
        @JsonProperty("trending") var trending: ShowHolder?,
        @JsonProperty("favorites") var favorites: List<BookmarkedTitle?>?,
        @JsonProperty("subscribed") var subscribed: List<BookmarkedTitle?>?,
        @JsonProperty("recentlySeen") var recentlySeen: List<LastEpisodeInfo?>?,
    )

    data class ShiroSearchResponse(
        @JsonProperty("data") val data: List<ShiroSearchResponseShow>,
        @JsonProperty("status") val status: String
    )

    data class ShiroFullSearchResponseCurrentPage(
        @JsonProperty("items") val items: List<ShiroSearchResponseShow>
    )

    data class ShiroFullSearchResponseNavItems(
        @JsonProperty("currentPage") val currentPage: ShiroFullSearchResponseCurrentPage
    )

    data class ShiroFullSearchResponseNav(
        @JsonProperty("nav") val nav: ShiroFullSearchResponseNavItems
    )

    data class ShiroFullSearchResponse(
        @JsonProperty("data") val data: ShiroFullSearchResponseNav,
        @JsonProperty("status") val status: String
    )

    data class ShiroVideo(
        @JsonProperty("video_id") val video_id: String,
        @JsonProperty("host") val host: String,
    )

    data class ShiroEpisodes(
        @JsonProperty("anime") val anime: AnimePageData?,
        @JsonProperty("anime_slug") val anime_slug: String?,
        @JsonProperty("create") val create: String?,
        @JsonProperty("dayOfTheWeek") val dayOfTheWeek: String,
        @JsonProperty("episode_number") val episode_number: Int,
        @JsonProperty("slug") val slug: String,
        @JsonProperty("update") val update: String,
        @JsonProperty("_id") val _id: String,
        @JsonProperty("videos") val videos: List<ShiroVideo>
    )

    data class AnimePageData(
        @JsonProperty("banner") val banner: String?,
        @JsonProperty("canonicalTitle") val canonicalTitle: String?,
        @JsonProperty("episodeCount") val episodeCount: String,
        @JsonProperty("genres") val genres: List<String>?,
        @JsonProperty("image") override val image: String,
        @JsonProperty("japanese") val japanese: String?,
        @JsonProperty("language") val language: String,
        @JsonProperty("name") override var name: String,
        @JsonProperty("slug") override var slug: String,
        @JsonProperty("synopsis") val synopsis: String,
        @JsonProperty("type") val type: String?,
        @JsonProperty("views") val views: Int?,
        @JsonProperty("year") val year: String?,
        @JsonProperty("_id") val _id: String,
        @JsonProperty("episodes") var episodes: List<ShiroEpisodes>?,
        @JsonProperty("status") val status: String?,
        @JsonProperty("schedule") val schedule: String?,
        @JsonProperty("english") override val english: String?,
    ) : CommonAnimePage

    data class AnimePage(
        @JsonProperty("data") val data: AnimePageData,
        @JsonProperty("status") val status: String
    )

    // Hack, needed to deserialize
    data class CommonAnimePageData(
        @JsonProperty("name") override val name: String,
        @JsonProperty("image") override val image: String,
        @JsonProperty("slug") override val slug: String,
        @JsonProperty("english") override val english: String? = null,
    ) : CommonAnimePage

    interface CommonAnimePage {
        val name: String
        val image: String
        val slug: String
        val english: String?
    }

    data class AllSearchMethods(
        @JsonProperty("data") val data: List<String>,
        @JsonProperty("status") val status: String
    )

//    data class AllSearchMethodsData(
//        @JsonProperty("genres") val genres: List<Genre>,
//        @JsonProperty("language") val language: List<Genre>,
//        @JsonProperty("sort") val sort: List<Genre>,
//        @JsonProperty("status") val status: List<Genre>,
//        @JsonProperty("type") val type: List<Genre>,
//        @JsonProperty("year") val year: List<Genre>,
//    )

    data class Genre(
        @JsonProperty("slug") val slug: String,
        @JsonProperty("name") val name: String
    )

    companion object {
        var isConvertingRecents = false
        infix fun Int.fmod(other: Int) = ((this % other) + other) % other
        const val maxStale = 60 * 10 // 10m

        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; rv:68.0) Gecko/20100101 Firefox/68.0"
        private val mapper = JsonMapper.builder().addModule(KotlinModule.Builder().build())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false).build()

        // NULL IF ERROR



        fun getVideoLink(id: String, isCasting: Boolean = false): List<ExtractorLink>? {
            val links = mutableListOf<ExtractorLink>()
            allApi.getUrl(id, isCasting) {
                links.add(it)
            }
            return if (links.isNullOrEmpty()) null else links.sortedBy { -it.quality }.distinctBy { it.url }
        }

        fun loadLinks(id: String, isCasting: Boolean, callback: (ExtractorLink) -> Unit): Boolean {
            return allApi.getUrl(id, isCasting) {
                callback.invoke(it)
            }
        }


        fun getSearchMethods(): List<String>? {
            return try {
                LiveApi.getGenres()
            } catch (e: Exception) {
                logError(e)
                null
            }
        }

        data class AllAnimeJson(
            @JsonProperty("id") val id: String,
            @JsonProperty("slug") val slug: String,
            @JsonProperty("mal_id") val mal_id: String?,
            @JsonProperty("title") val title: String?,
        )


        data class ShowHolder(
            @JsonProperty("status") val status: String,
            @JsonProperty("message") val message: String,
            @JsonProperty("data") val data: List<Data>
        )

        data class Data(
            @JsonProperty("id") val id: String,
            @JsonProperty("slug") val slug: String,
            @JsonProperty("title") val title: String,
            @JsonProperty("title_english") val title_english: String?,
            @JsonProperty("native_title") val native_title: String,
            @JsonProperty("poster") val poster: String,
            @JsonProperty("banner") val banner: String,
            @JsonProperty("ids") val ids: String,
            @JsonProperty("type") val type: String,
            @JsonProperty("format") val format: String,
            @JsonProperty("episodes") val episodes: String,
            @JsonProperty("episode_duration") val episode_duration: String,
            @JsonProperty("synopsis") val synopsis: String,
            @JsonProperty("language") val language: String,
            @JsonProperty("synonyms") val synonyms: String,
            @JsonProperty("season") val season: String,
            @JsonProperty("release_year") val release_year: String?,
            @JsonProperty("score") val score: String,
            @JsonProperty("rating") val rating: String,
            @JsonProperty("studios") val studios: String,
            @JsonProperty("genres") val genres: String,
            @JsonProperty("aired") val aired: String,
            @JsonProperty("status") val status: String,
            @JsonProperty("trailer") val trailer: String,
            @JsonProperty("total_views") val total_views: String,
            @JsonProperty("created_at") val created_at: String,
            @JsonProperty("updated_at") val updated_at: String
        )

        private fun getTrending(hideChinese: Boolean = false): ShowHolder? {
            return try {
                LiveApi.getTrending()
            } catch (e: Exception) {
                logError(e)
                null
            }
        }


        data class AnimeHolder(
            @JsonProperty("anime") val anime: Anime,
            @JsonProperty("episode") val episode: Episode,
        )


        data class Anime(
            @JsonProperty("title") val title: String,
            @JsonProperty("slug") val slug: String,
            @JsonProperty("poster") val poster: String,
            @JsonProperty("synopsis") val synopsis: String,
            @JsonProperty("format") val format: String
        )

        data class Episode(
            @JsonProperty("id") val id: String,
            @JsonProperty("slug") val slug: String,
            @JsonProperty("title") val title: String,
            @JsonProperty("episode") val episode: String,
            @JsonProperty("image") val image: String,
            @JsonProperty("insight") val insight: String?,
            @JsonProperty("sources") val sources: String,
            @JsonProperty("ext") val ext: String?,
            @JsonProperty("views") val views: String,
            @JsonProperty("created_at") val created_at: String,
            @JsonProperty("updated_at") val updated_at: String
        )

        private fun getRecents(hideChinese: Boolean = false): List<AnimeHolder>? {
            return try {
                LiveApi.getRecents()
            } catch (e: Exception) {
                logError(e)
                null
            }
        }

        data class Random(
            @JsonProperty("status") val status: String,
            @JsonProperty("message") val message: String,
            @JsonProperty("data") val data: Data
        )

        fun getRandom(): Random? {
            return try {
                LiveApi.getRandom()
            } catch (e: Exception) {
                logError(e)
                null
            }
        }

        data class AnimePageNewRoot(
            @JsonProperty("status") val status: String,
            @JsonProperty("message") val message: String,
            @JsonProperty("data") val data: AnimePageNewData
        )

        data class AnimePageNew(
            @JsonProperty("id") val id: String,
            @JsonProperty("slug") val slug: String,
            @JsonProperty("title") val title: String,
            @JsonProperty("title_english") val title_english: String?,
            @JsonProperty("native_title") val native_title: String,
            @JsonProperty("poster") val poster: String,
            @JsonProperty("banner") val banner: String,
            @JsonProperty("ids") val ids: String,
            @JsonProperty("type") val type: String,
            @JsonProperty("format") val format: String,
            @JsonProperty("episodes") val episodes: String,
            @JsonProperty("episode_duration") val episode_duration: String,
            @JsonProperty("synopsis") val synopsis: String,
            @JsonProperty("language") val language: String,
            @JsonProperty("synonyms") val synonyms: String,
            @JsonProperty("season") val season: String,
            @JsonProperty("release_year") val release_year: String?,
            @JsonProperty("score") val score: String,
            @JsonProperty("rating") val rating: String,
            @JsonProperty("studios") val studios: String,
            @JsonProperty("genres") val genres: String,
            @JsonProperty("aired") val aired: String,
            @JsonProperty("status") val status: String,
            @JsonProperty("trailer") val trailer: String,
            @JsonProperty("total_views") val total_views: String,
            @JsonProperty("created_at") val created_at: String,
            @JsonProperty("updated_at") val updated_at: String
        )

        data class AnimePageNewEpisodes(
            @JsonProperty("id") val id: String?,
            @JsonProperty("slug") val slug: String,
            @JsonProperty("title") val title: String?,
            @JsonProperty("episode") val episode: String?,
            @JsonProperty("image") val image: String?,
            @JsonProperty("insight") val insight: String?,
            @JsonProperty("sources") val sources: String,
            @JsonProperty("ext") val ext: String?,
            @JsonProperty("views") val views: String,
            @JsonProperty("created_at") val created_at: String,
            @JsonProperty("updated_at") val updated_at: String
        )

        data class AnimePageNewData(
            @JsonProperty("anime") val anime: AnimePageNew,
            @JsonProperty("episodes") val episodes: List<AnimePageNewEpisodes>
        )

        /** Resolves a MyAnimeList id to an AniList catalogue page (used by myanimelist.net links). */
        fun getSlugFromMalId(malId: String, title: String?): String? =
            getAnimePageNewMal(malId)?.data?.anime?.slug

        fun getAnimePageNewMal(malId: String): AnimePageNewRoot? {
            println("Loading mal page $malId")
            return try {
                LiveApi.getAnimePageByMal(malId)
            } catch (e: Exception) {
                logError(e)
                null
            }
        }

        data class EpisodeObject(
            @JsonProperty("slug") val slug: String,
            @JsonProperty("source") val source: String?
        )

        fun getAnimePageNew(slug: String): AnimePageNewRoot? {
            println("Loading anime page $slug")
            return try {
                LiveApi.getAnimePage(slug)
            } catch (e: Exception) {
                logError(e)
                null
            }
        }








        data class SearchNew(
            @JsonProperty("status") val status: String,
            @JsonProperty("message") val message: String,
            @JsonProperty("data") val data: List<Data>
        )


        fun searchNew(query: String, genres: List<String>? = null, hideChinese: Boolean = false): List<Data>? {
            val primary = try {
                if (genres == null) {
                    // AniList first; if it has not answered within 3.5 s (or answers empty) Kitsu/Jikan start in parallel
                    com.lagradost.shiro.utils.meta.Race.first<List<Data>>(
                        3500,
                        listOf({ LiveApi.search(query, genres) }, { com.lagradost.shiro.utils.meta.MetaHub.fallbackSearch(query) }),
                        ok = { it.isNotEmpty() }
                    )
                } else LiveApi.search(query, genres)
            } catch (e: Exception) {
                logError(e)
                null
            }
            // AniList failed or is rate limited: Kitsu / Jikan answer instead (titles keep their MAL ids)
            val base = primary ?: try {
                com.lagradost.shiro.utils.meta.MetaHub.fallbackSearch(query).takeIf { it.isNotEmpty() }
            } catch (e: Exception) {
                logError(e)
                null
            }
            // Movies and TV shows, only when enabled in Settings -> General
            val mediaResults = try {
                com.lagradost.shiro.utils.meta.MetaHub.searchMedia(query)
            } catch (e: Exception) {
                logError(e)
                emptyList()
            }
            // Loaded CloudStream extension providers also answer search
            val extensionResults = try {
                CsBridge.search(query)
            } catch (e: Exception) {
                logError(e)
                emptyList()
            }
            val zangetsuResults = try {
                com.lagradost.shiro.utils.zg.ZangetsuBridge.search(query)
            } catch (e: Exception) {
                logError(e)
                emptyList()
            }
            val aniyomiResults = try {
                com.lagradost.shiro.utils.aniyomi.AniyomiBridge.search(query)
            } catch (e: Exception) {
                logError(e)
                emptyList()
            }
            // Built-in sources supply the catalogue. Extension results only join the list when the user asks for them.
            val includeSources = com.lagradost.shiro.AcraApplication.context?.let {
                PreferenceManager.getDefaultSharedPreferences(it).getBoolean("search_include_sources", false)
            } ?: false
            val extra = mediaResults + if (includeSources) extensionResults + zangetsuResults + aniyomiResults else emptyList()
            return if (base == null && extra.isEmpty()) null
            else base.orEmpty() + extra
        }

        fun getFullUrlCdn(url: String): String {
            // New backend serves absolute image urls (anilistcdn/allanime)
            return url.removePrefix("/").replace("-dubbed", "-dub")
        }

        var cachedHome: ShiroHomePageNew? = null


        // OTHERWISE CRASH AT BOOT FROM HAVING OLD FAVORITES SYSTEM
        private fun Context.convertOldRecents() {
            isConvertingRecents = true
            val keys = try {
                getKeys(VIEW_LST_KEY)
            } catch (e: Exception) {
                logError(e)
                isConvertingRecents = false
                return
            }
            thread {
                try {
                    var converted = 0
                    keys.pmap {
                        getKey<LastEpisodeInfoLegacy>(it)
                    }
                    if (!keys.isNullOrEmpty()) {
                        main {
                            Toast.makeText(
                                this,
                                "Converting your latest watched, internet required, do not close the app",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                    keys.forEach {
                        val data = getKey<LastEpisodeInfoLegacy>(it)
                        if (data != null && data.seenAt + 60L * 60L * 24L * 30L * 1000L > System.currentTimeMillis() && converted < 25) { // last 30 days
                            // NEEDS REMOVAL TO PREVENT DUPLICATES
                            removeKey(it)
                            val newData = getAnimePageNew(data.id.slug.replace("-dubbed", "-dub"))?.data
                            if (newData == null) {
                                converted++
                                return@forEach
                            }
                            setKey(
                                it, LastEpisodeInfo(
                                    data.pos,
                                    data.dur,
                                    data.seenAt,
                                    newData,
                                    data.aniListId,
                                    data.episodeIndex,
                                    data.seasonIndex,
                                    data.isMovie,
                                    newData.episodes.getOrNull(data.episodeIndex),
                                    newData.anime.poster,
                                    data.title ?: data.id.canonicalTitle ?: newData.anime.title,
                                    data.bannerImage,
                                    data.anilistID,
                                    data.malID,
                                    data.fillerEpisodes
                                )
                            )
                            converted++
                        } else {
                            removeKey(it)
                        }
                    }
                    setKey(LEGACY_RECENTS, false)
                    requestHome(false)
                    main {
                        Toast.makeText(
                            this,
                            "Latest watched converted",
                            Toast.LENGTH_LONG
                        )
                            .show()
                    }
                } catch (e: Exception) {
                    logError(e)
                    main {
                        Toast.makeText(
                            this,
                            "Error converting latest watched",
                            Toast.LENGTH_LONG
                        )
                            .show()
                    }
                } finally {
                    isConvertingRecents = false
                }
            }
        }

        private fun Context.convertOldFavorites() {
            val keys = try {
                getKeys(BOOKMARK_KEY)
            } catch (e: Exception) {
                logError(e)
                return
            }
            thread {
                try {
                    keys.pmap {
                        getKey<BookmarkedTitle>(it)
                    }
                    keys.forEach {
                        val data = getKey<BookmarkedTitle>(it)
                        if (data != null) {
                            setKey(
                                it,
                                BookmarkedTitle(
                                    data.name.replace("Dubbed", "(Dub)"),
                                    data.image,
                                    data.slug.replace("-dubbed", "-dub"),
                                    data.english
                                )
                            )
                        } else {
                            removeKey(it)
                        }
                    }
                    setKey(LEGACY_BOOKMARKS, false)
                } catch (e: Exception) {
                    logError(e)
                }
            }
        }

        private fun Context.convertOldSubbed() {
            val keys = try {
                getKeys(SUBSCRIPTIONS_BOOKMARK_KEY)
            } catch (e: Exception) {
                logError(e)
                return
            }
            thread {
                try {
                    keys.pmap {
                        getKey<BookmarkedTitle>(it)
                    }
                    keys.forEach {
                        val data = getKey<BookmarkedTitle>(it)
                        if (data != null) {
                            removeKey(SUBSCRIPTIONS_KEY, data.slug)
                            setKey(SUBSCRIPTIONS_KEY, data.slug.replace("-dubbed", "-dub"))

                            setKey(
                                it,
                                BookmarkedTitle(
                                    data.name.replace("Dubbed", "(Dub)"),
                                    data.image,
                                    data.slug.replace("-dubbed", "-dub"),
                                    data.english
                                )
                            )
                        } else {
                            removeKey(it)
                        }
                    }
                    setKey(LEGACY_SUBS, false)
                } catch (e: Exception) {
                    logError(e)
                }
            }
        }


        fun Context.getFav(): List<BookmarkedTitle?> {
            val legacyBookmarks = getKey(LEGACY_BOOKMARKS, true)
            if (legacyBookmarks == true) {
                convertOldFavorites()
            }
            val keys = getKeys(BOOKMARK_KEY)

            thread {
                keys.pmap {
                    getKey<BookmarkedTitle>(it)
                }
            }

            return keys.map<String, BookmarkedTitle?> {
                getKey(it)
            }.distinctBy { it?.slug }
        }

        fun Context.getSubbed(): List<BookmarkedTitle?> {
            val legacySubs = getKey(LEGACY_SUBS, true)
            if (legacySubs == true) {
                convertOldSubbed()
            }
            val keys = getKeys(SUBSCRIPTIONS_BOOKMARK_KEY)

            thread {
                keys.pmap {
                    getKey<BookmarkedTitle>(it)
                }
            }

            return keys.map {
                getKey(it)
            }
        }

        fun Context.getLastWatch(): List<LastEpisodeInfo?> {
            val legacyRecents = getKey(LEGACY_RECENTS, true)
            if (legacyRecents == true && !isConvertingRecents) {
                convertOldRecents()
            }
            val keys = getKeys(VIEW_LST_KEY)
            thread {
                keys.pmap {
                    getKey<LastEpisodeInfo>(it)?.data
                }
            }
            return (getKeys(VIEW_LST_KEY).map {
                getKey<LastEpisodeInfo>(it)
            }).sortedBy { if (it == null) 0 else -(it.seenAt) }
        }

        fun Context.requestHome(canBeCached: Boolean = true): ShiroHomePageNew? {
            //if (currentToken == null) return null
            return getHome(canBeCached)
        }


        private fun Context.getHome(canBeCached: Boolean, usedToken: Token? = currentToken): ShiroHomePageNew? {
            var res: ShiroHomePageNew? = null
            if (canBeCached && cachedHome != null) {
                res = cachedHome
            } else {
                val settingsManager = PreferenceManager.getDefaultSharedPreferences(this)
                val hideChinese = settingsManager.getBoolean("hide_chinese", false)
                res = ShiroHomePageNew(
                    getRandom(),
                    getRecents(hideChinese),
                    getTrending(hideChinese),
                    null,
                    null,
                    null
                )
//                res?.recents = getRecents()
//                val url = "********${usedToken!!.token}"
//                try {
//                    val headers = mapOf("Cache-Control" to "max-stale=$maxStale")
//                    val response = khttp.get(url, timeout = SHIRO_TIMEOUT_TIME, headers = headers)
//                    res = response.text.let { mapper.readValue(it) }
//                } catch (e: Exception) {
//                    println(e.message)
//                }
//                if (res != null) {
//                }
                //res?.schedule = getSchedule()
            }
            // Anything below here shouldn't do network requests (network on main thread)
            // (card.removeButton.setOnClickListener {requestHome(true)})

            if (res == null || (res.recents.isNullOrEmpty() && res.trending?.data.isNullOrEmpty())) {
                if (res != null && res === cachedHome) cachedHome = null
                hasThrownError = 0
                onHomeError.invoke(false)
                return null
            }
            res.favorites = getFav()
            res.subscribed = getSubbed()
            res.recentlySeen = getLastWatch()
            cachedHome = res
            onHomeFetched.invoke(res)

            return res
        }

        var currentToken: Token? = null
        var currentHeaders: MutableMap<String, String>? = null
        var onHomeFetched = Event<ShiroHomePageNew?>()
        var onTokenFetched = Event<Boolean>()
        var onHomeError = Event<Boolean>() // TRUE IF FULL RELOAD OF TOKEN, FALSE IF JUST HOME
        var hasThrownError = -1

        fun Context.initShiroApi() {
            requestHome()

//            if (currentToken != null) return
//
//            currentToken = getToken()
//            if (currentToken != null) {
//                currentHeaders = currentToken?.headers?.toMutableMap()
//                currentHeaders?.set("Cookie", "")
//                currentToken?.cookies?.forEach {
//                    currentHeaders?.set("Cookie", it.key + "=" + it.value.substring(0, it.value.indexOf(';')) + ";")
//                }
//                onTokenFetched.invoke(true)
//
//            } else {
//                println("TOKEN ERROR")
//                hasThrownError = 1
//                onHomeError.invoke(true)
//            }
        }
    }
}
