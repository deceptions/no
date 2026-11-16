package com.lagradost.shiro.utils.auth

import DataStore.getKey
import DataStore.setKey
import android.content.Context
import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.shiro.utils.mvvm.logError

const val KITSU_USERID_KEY = "kitsu_user_id"

/**
 * Kitsu: OAuth2 password-grant login with Kitsu's publicly documented client credentials
 * (https://kitsu.docs.apiary.io), token refresh, MAL/AniList -> Kitsu id mapping, library
 * entry updates and library listing. All calls block; call off the main thread.
 */
object KitsuApi {
    private const val API = "https://kitsu.io/api"
    private const val CLIENT_ID = "dd031b32d2f56c990b1425efe6c42ad847e7fe3ab46bf1299f05ecd856bdb7dd"
    private const val CLIENT_SECRET = "54d7307928f63414defd96399fc31ba847961ceaecef3a5fd93144e960c0e151"
    private const val JSONAPI = "application/vnd.api+json"

    data class Result(val success: Boolean, val message: String)

    data class LibraryItem(
        val entryId: String,
        val title: String,
        val poster: String?,
        val status: String,
        val progress: Int,
        val total: Int?,
        val ratingTwenty: Int?,
        val malId: Int?,
    )

    // ---------- login / tokens ----------

    private fun Context.token() = getKey<String>(KITSU_TOKEN_KEY, KITSU_ACCOUNT_ID, null)

    private fun Context.storeTokens(json: JsonNode) {
        setKey(KITSU_TOKEN_KEY, KITSU_ACCOUNT_ID, json.path("access_token").asText(""))
        json.path("refresh_token").asText("").takeIf { it.isNotEmpty() }
            ?.let { setKey(KITSU_REFRESH_TOKEN_KEY, KITSU_ACCOUNT_ID, it) }
    }

    fun login(context: Context, username: String, password: String): Result {
        if (username.isBlank() || password.isBlank()) return Result(false, "Enter your Kitsu email/username and password")
        return try {
            val tokenRes = khttp.post(
                "$API/oauth/token",
                data = mapOf(
                    "grant_type" to "password",
                    "username" to username.trim(),
                    "password" to password,
                    "client_id" to CLIENT_ID,
                    "client_secret" to CLIENT_SECRET
                ),
                timeout = 30.0
            )
            val tokenJson = AuthManager.mapper.readTree(tokenRes.text)
            if (tokenRes.statusCode != 200 || tokenJson.path("access_token").asText("").isEmpty()) {
                return Result(false, "Incorrect Kitsu username or password")
            }
            val access = tokenJson.path("access_token").asText()

            val userRes = khttp.get(
                "$API/edge/users?filter%5Bself%5D=true",
                headers = mapOf("Authorization" to "Bearer $access", "Accept" to JSONAPI),
                timeout = 30.0
            )
            val user = AuthManager.mapper.readTree(userRes.text).path("data").path(0)
            if (userRes.statusCode != 200 || user.isMissingNode || user.isNull) {
                return Result(false, "Signed in, but Kitsu profile could not be loaded")
            }

            context.storeTokens(tokenJson)
            context.setKey(KITSU_USERID_KEY, KITSU_ACCOUNT_ID, user.path("id").asText(""))
            context.setKey(
                KITSU_USER_KEY, KITSU_ACCOUNT_ID,
                user.path("attributes").path("name").asText(username)
            )
            Result(true, "Signed in")
        } catch (e: Exception) {
            Result(false, "Network error: ${e.message}")
        }
    }

    private fun refresh(context: Context): Boolean = try {
        val refresh = context.getKey<String>(KITSU_REFRESH_TOKEN_KEY, KITSU_ACCOUNT_ID, null) ?: return false
        val res = khttp.post(
            "$API/oauth/token",
            data = mapOf(
                "grant_type" to "refresh_token", "refresh_token" to refresh,
                "client_id" to CLIENT_ID, "client_secret" to CLIENT_SECRET
            )
        )
        val json = AuthManager.mapper.readTree(res.text)
        if (res.statusCode == 200 && json.path("access_token").asText("").isNotEmpty()) {
            context.storeTokens(json); true
        } else false
    } catch (e: Exception) {
        logError(e); false
    }

    /** Authenticated call that transparently refreshes an expired token once. */
    private fun call(
        context: Context, method: String, path: String, body: String? = null
    ): khttp.Response? {
        fun once(): khttp.Response? {
            val token = context.token() ?: return null
            val headers = mapOf(
                "Authorization" to "Bearer $token",
                "Accept" to JSONAPI,
                "Content-Type" to JSONAPI
            )
            val url = if (path.startsWith("http")) path else "$API/edge/$path"
            return when (method) {
                "GET" -> khttp.get(url, headers = headers, timeout = 30.0)
                "POST" -> khttp.post(url, headers = headers, data = body, timeout = 30.0)
                "PATCH" -> khttp.patch(url, headers = headers, data = body, timeout = 30.0)
                else -> null
            }
        }
        return try {
            var res = once()
            if (res?.statusCode == 401 && refresh(context)) res = once()
            res
        } catch (e: Exception) {
            logError(e); null
        }
    }

    private fun userId(context: Context): String? =
        context.getKey<String>(KITSU_USERID_KEY, KITSU_ACCOUNT_ID, null)?.takeIf { it.isNotEmpty() }
            ?: run {
                val res = call(context, "GET", "users?filter%5Bself%5D=true") ?: return null
                val id = AuthManager.mapper.readTree(res.text).path("data").path(0).path("id").asText("")
                if (id.isNotEmpty()) context.setKey(KITSU_USERID_KEY, KITSU_ACCOUNT_ID, id)
                id.takeIf { it.isNotEmpty() }
            }

    // ---------- mapping ----------

    private val kitsuIdCache = HashMap<String, String>()

    /** Kitsu anime id for a MAL and/or AniList id (via Kitsu's mappings table). */
    fun findAnimeId(context: Context, malId: Int?, anilistId: Int?): String? {
        val candidates = listOfNotNull(
            malId?.let { "myanimelist/anime" to it }, anilistId?.let { "anilist/anime" to it }
        )
        for ((site, id) in candidates) {
            val key = "$site:$id"
            kitsuIdCache[key]?.let { return it }
            val res = call(
                context, "GET",
                "mappings?filter%5BexternalSite%5D=${site.replace("/", "%2F")}&filter%5BexternalId%5D=$id&include=item"
            ) ?: continue
            if (res.statusCode != 200) continue
            val item = AuthManager.mapper.readTree(res.text).path("data").path(0)
                .path("relationships").path("item").path("data")
            if (item.path("type").asText() == "anime") {
                val kid = item.path("id").asText()
                kitsuIdCache[key] = kid
                return kid
            }
        }
        return null
    }

    // ---------- writes ----------

    /** AniList-style status int (0 watching, 1 completed, 2 paused, 3 dropped, 4 planning, 5 rewatching). */
    fun statusFromInt(status: Int) = when (status) {
        0, 5 -> "current"
        1 -> "completed"
        2 -> "on_hold"
        3 -> "dropped"
        4 -> "planned"
        else -> "current"
    }

    fun statusToInt(kitsu: String) = when (kitsu) {
        "current" -> 0; "completed" -> 1; "on_hold" -> 2; "dropped" -> 3; "planned" -> 4; else -> -1
    }

    /**
     * Creates or updates the library entry. [score] is 0..10 (0 = leave unrated).
     * With [monotonic] the entry is left alone when Kitsu already has at least [progress]
     * (used for automatic "watched" updates so rewatching never lowers progress).
     * Returns true on success.
     */
    fun updateEntry(
        context: Context, malId: Int?, anilistId: Int?, status: Int, score: Int, progress: Int,
        monotonic: Boolean = false
    ): Boolean {
        if (context.token() == null) return true // not connected: nothing to do
        val user = userId(context) ?: return false
        val animeId = findAnimeId(context, malId, anilistId) ?: return false
        val existing = call(context, "GET", "library-entries?filter%5BuserId%5D=$user&filter%5BanimeId%5D=$animeId")
            ?: return false
        if (existing.statusCode != 200) return false
        val existingEntry = AuthManager.mapper.readTree(existing.text).path("data").path(0)
        val entryId = existingEntry.path("id").asText("")
        if (monotonic && entryId.isNotEmpty() &&
            existingEntry.path("attributes").path("progress").asInt(0) >= progress
        ) return true

        val attrs = StringBuilder("\"status\":\"${statusFromInt(status)}\",\"progress\":$progress")
        if (score in 1..10) attrs.append(",\"ratingTwenty\":${score * 2}")

        val res = if (entryId.isNotEmpty()) {
            call(
                context, "PATCH", "library-entries/$entryId",
                """{"data":{"id":"$entryId","type":"libraryEntries","attributes":{$attrs}}}"""
            )
        } else {
            call(
                context, "POST", "library-entries",
                """{"data":{"type":"libraryEntries","attributes":{$attrs},"relationships":{
                    "user":{"data":{"id":"$user","type":"users"}},
                    "media":{"data":{"id":"$animeId","type":"anime"}}}}}"""
            )
        }
        return res != null && res.statusCode in 200..299
    }

    // ---------- reads ----------

    /** Fetches the user's anime library (up to [maxItems]). */
    fun getLibrary(context: Context, maxItems: Int = 400): List<LibraryItem>? {
        val user = userId(context) ?: return null
        val items = ArrayList<LibraryItem>()
        var url: String? =
            "$API/edge/library-entries?filter%5BuserId%5D=$user&filter%5Bkind%5D=anime" +
                    "&include=anime,anime.mappings&fields%5Banime%5D=canonicalTitle,posterImage,episodeCount,mappings" +
                    "&fields%5Bmappings%5D=externalSite,externalId&page%5Blimit%5D=20&sort=-progressedAt"
        while (url != null && items.size < maxItems) {
            val res = call(context, "GET", url) ?: return if (items.isEmpty()) null else items
            if (res.statusCode != 200) return if (items.isEmpty()) null else items
            val root = AuthManager.mapper.readTree(res.text)

            val animeById = HashMap<String, JsonNode>()
            val mappingById = HashMap<String, JsonNode>()
            for (inc in root.path("included")) {
                when (inc.path("type").asText()) {
                    "anime" -> animeById[inc.path("id").asText()] = inc
                    "mappings" -> mappingById[inc.path("id").asText()] = inc
                }
            }
            for (entry in root.path("data")) {
                val animeRef = entry.path("relationships").path("anime").path("data").path("id").asText("")
                val anime = animeById[animeRef] ?: continue
                val a = anime.path("attributes")
                var mal: Int? = null
                for (m in anime.path("relationships").path("mappings").path("data")) {
                    val mapping = mappingById[m.path("id").asText()]?.path("attributes") ?: continue
                    if (mapping.path("externalSite").asText() == "myanimelist/anime") {
                        mal = mapping.path("externalId").asText("").toIntOrNull()
                    }
                }
                val at = entry.path("attributes")
                items.add(
                    LibraryItem(
                        entryId = entry.path("id").asText(),
                        title = a.path("canonicalTitle").asText("?"),
                        poster = a.path("posterImage").path("small").asText(null),
                        status = at.path("status").asText(""),
                        progress = at.path("progress").asInt(0),
                        total = a.path("episodeCount").takeIf { !it.isNull && !it.isMissingNode }?.asInt(),
                        ratingTwenty = at.path("ratingTwenty").takeIf { !it.isNull && !it.isMissingNode }?.asInt(),
                        malId = mal
                    )
                )
            }
            url = root.path("links").path("next").asText(null)
        }
        return items
    }
}
