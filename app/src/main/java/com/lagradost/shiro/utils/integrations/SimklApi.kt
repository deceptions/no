package com.lagradost.shiro.utils.integrations

import DataStore.getKey
import DataStore.removeKey
import DataStore.setKey
import android.content.Context
import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.shiro.utils.auth.AuthManager
import com.lagradost.shiro.utils.mvvm.logError
import java.net.URLEncoder

const val SIMKL_TOKEN_KEY = "simkl_token"
const val SIMKL_REFRESH_KEY = "simkl_refresh_token"
const val SIMKL_EXPIRES_KEY = "simkl_expires_at"
const val SIMKL_USER_KEY = "simkl_user"
const val SIMKL_ACCOUNT_ID = "0"

/**
 * SIMKL (https://api.simkl.org). Uses AUTH V2's device flow (RFC 8628): no redirect URI or
 * client secret needed, works the same on phone and TV. The user registers their own app at
 * simkl.com/settings/developer and pastes its client_id into Settings -> Integrations.
 */
object SimklApi {
    private const val API = "https://api.simkl.com"
    private const val APP = "app-name=nekomo&app-version=1.0"
    private const val UA = "Nekomo/1.0"

    data class DeviceCode(
        val deviceCode: String,
        val userCode: String,
        val verificationUrl: String,
        val intervalSec: Int,
        val expiresInSec: Int
    )

    enum class Poll { PENDING, SLOW_DOWN, APPROVED, EXPIRED, FAILED }

    data class LibraryItem(
        val title: String,
        val poster: String?,
        val status: String,
        val watched: Int,
        val total: Int?,
        val rating: Int?,
        val malId: Int?,
    )

    private fun clientId(context: Context) = IntegrationPrefs(context).simklClientId

    fun isConnected(context: Context) = context.getKey<String>(SIMKL_TOKEN_KEY, SIMKL_ACCOUNT_ID, null) != null

    fun logout(context: Context) {
        context.removeKey(SIMKL_TOKEN_KEY, SIMKL_ACCOUNT_ID)
        context.removeKey(SIMKL_REFRESH_KEY, SIMKL_ACCOUNT_ID)
        context.removeKey(SIMKL_EXPIRES_KEY, SIMKL_ACCOUNT_ID)
        context.removeKey(SIMKL_USER_KEY, SIMKL_ACCOUNT_ID)
    }

    // ---------- device flow ----------

    fun startDeviceFlow(context: Context): DeviceCode? {
        val id = clientId(context)
        if (id.isEmpty()) return null
        return try {
            val res = khttp.post(
                "$API/oauth2/device",
                headers = mapOf("User-Agent" to UA),
                data = mapOf("client_id" to id, "scope" to "media:read media:write")
            )
            if (res.statusCode != 200) return null
            val j = AuthManager.mapper.readTree(res.text)
            val userCode = j.path("user_code").asText("")
            DeviceCode(
                j.path("device_code").asText(""),
                userCode,
                j.path("verification_uri_complete").asText("https://simkl.com/pin?user_code=$userCode"),
                j.path("interval").asInt(5),
                j.path("expires_in").asInt(900)
            ).takeIf { it.deviceCode.isNotEmpty() }
        } catch (e: Exception) {
            logError(e); null
        }
    }

    private fun Context.storeTokens(j: JsonNode) {
        setKey(SIMKL_TOKEN_KEY, SIMKL_ACCOUNT_ID, j.path("access_token").asText(""))
        j.path("refresh_token").asText("").takeIf { it.isNotEmpty() }
            ?.let { setKey(SIMKL_REFRESH_KEY, SIMKL_ACCOUNT_ID, it) }
        setKey(
            SIMKL_EXPIRES_KEY, SIMKL_ACCOUNT_ID,
            System.currentTimeMillis() + j.path("expires_in").asLong(604800) * 1000
        )
    }

    fun pollDevice(context: Context, deviceCode: String): Poll {
        return try {
            val res = khttp.post(
                "$API/oauth2/token",
                headers = mapOf("User-Agent" to UA),
                data = mapOf(
                    "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
                    "client_id" to clientId(context),
                    "device_code" to deviceCode
                )
            )
            val j = AuthManager.mapper.readTree(res.text)
            when {
                res.statusCode == 200 && j.path("access_token").asText("").isNotEmpty() -> {
                    context.storeTokens(j)
                    context.setKey(SIMKL_USER_KEY, SIMKL_ACCOUNT_ID, fetchUserName(context) ?: "SIMKL user")
                    Poll.APPROVED
                }
                j.path("error").asText() == "authorization_pending" -> Poll.PENDING
                j.path("error").asText() == "slow_down" -> Poll.SLOW_DOWN
                j.path("error").asText() == "expired_token" -> Poll.EXPIRED
                else -> Poll.FAILED
            }
        } catch (e: Exception) {
            logError(e); Poll.FAILED
        }
    }

    private fun refresh(context: Context): Boolean = try {
        val refresh = context.getKey<String>(SIMKL_REFRESH_KEY, SIMKL_ACCOUNT_ID, null) ?: return false
        val res = khttp.post(
            "$API/oauth2/token",
            headers = mapOf("User-Agent" to UA),
            data = mapOf("grant_type" to "refresh_token", "refresh_token" to refresh, "client_id" to clientId(context))
        )
        val j = AuthManager.mapper.readTree(res.text)
        if (res.statusCode == 200 && j.path("access_token").asText("").isNotEmpty()) {
            context.storeTokens(j); true
        } else false
    } catch (e: Exception) {
        logError(e); false
    }

    private fun call(context: Context, method: String, path: String, body: String? = null): khttp.Response? {
        fun once(): khttp.Response? {
            val token = context.getKey<String>(SIMKL_TOKEN_KEY, SIMKL_ACCOUNT_ID, null) ?: return null
            val sep = if (path.contains("?")) "&" else "?"
            val url = "$API$path${sep}client_id=${URLEncoder.encode(clientId(context), "UTF-8")}&$APP"
            val headers = mapOf(
                "Authorization" to "Bearer $token",
                "User-Agent" to UA,
                "simkl-api-key" to clientId(context),
                "Content-Type" to "application/json"
            )
            return if (method == "GET") khttp.get(url, headers = headers, timeout = 30.0)
            else khttp.post(url, headers = headers, json = body ?: "{}", timeout = 30.0)
        }
        return try {
            var res = once()
            if (res?.statusCode == 401 && refresh(context)) res = once()
            res
        } catch (e: Exception) {
            logError(e); null
        }
    }

    private fun fetchUserName(context: Context): String? = try {
        val res = call(context, "GET", "/users/settings")
        val j = AuthManager.mapper.readTree(res?.text ?: "")
        j.path("user").path("name").asText("").ifEmpty { null }
    } catch (e: Exception) {
        null
    }

    // ---------- writes ----------

    fun statusFromInt(status: Int) = when (status) {
        0, 5 -> "watching"
        1 -> "completed"
        2 -> "hold"
        3 -> "dropped"
        4 -> "plantowatch"
        else -> "watching"
    }

    private fun idsJson(malId: Int?, anilistId: Int?): String? {
        val parts = listOfNotNull(malId?.let { "\"mal\":$it" }, anilistId?.let { "\"anilist\":$it" })
        return if (parts.isEmpty()) null else "{${parts.joinToString(",")}}"
    }

    private fun ok(res: khttp.Response?): Boolean {
        if (res == null || res.statusCode !in 200..299) return false
        val nf = AuthManager.mapper.readTree(res.text).path("not_found")
        return nf.path("shows").size() == 0 && nf.path("movies").size() == 0 && nf.path("anime").size() == 0
    }

    /**
     * Records watched episodes 1..[progress], sets the list status and optional rating (0-10).
     * With [monotonic] (automatic updates) a plain "watching" status is not re-sent, since the
     * history call already puts a show in Watching and must not demote a completed/on-hold entry.
     */
    fun updateEntry(
        context: Context, malId: Int?, anilistId: Int?, status: Int, score: Int, progress: Int,
        monotonic: Boolean = false
    ): Boolean {
        if (!isConnected(context)) return true // not connected: nothing to do
        val ids = idsJson(malId, anilistId) ?: return false

        var success = true
        if (progress > 0) {
            val eps = (1..progress).joinToString(",") { "{\"number\":$it}" }
            success = ok(call(context, "POST", "/sync/history", """{"shows":[{"ids":$ids,"episodes":[$eps]}]}""")) && success
        }
        if (!monotonic || statusFromInt(status) != "watching") {
            success = ok(
                call(context, "POST", "/sync/add-to-list", """{"shows":[{"to":"${statusFromInt(status)}","ids":$ids}]}""")
            ) && success
        }
        if (score in 1..10) {
            success = ok(call(context, "POST", "/sync/ratings", """{"shows":[{"rating":$score,"ids":$ids}]}""")) && success
        }
        return success
    }

    // ---------- reads ----------

    fun getLibrary(context: Context): List<LibraryItem>? {
        val res = call(context, "GET", "/sync/all-items/anime/") ?: return null
        if (res.statusCode != 200) return null
        val root = AuthManager.mapper.readTree(res.text)
        val out = ArrayList<LibraryItem>()
        for (e in root.path("anime")) {
            val show = e.path("show")
            val poster = show.path("poster").asText("").takeIf { it.isNotEmpty() }
                ?.let { "https://simkl.in/posters/${it}_w.webp" }
            out.add(
                LibraryItem(
                    title = show.path("title").asText("?"),
                    poster = poster,
                    status = e.path("status").asText(""),
                    watched = e.path("watched_episodes_count").asInt(0),
                    total = e.path("total_episodes_count").takeIf { !it.isNull && !it.isMissingNode && it.asInt() > 0 }?.asInt(),
                    rating = e.path("user_rating").takeIf { !it.isNull && !it.isMissingNode }?.asInt(),
                    malId = show.path("ids").path("mal").asText("").toIntOrNull()
                )
            )
        }
        return out
    }
}
