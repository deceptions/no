package com.lagradost.shiro.utils.skip

import android.content.Context
import com.lagradost.shiro.utils.auth.AuthManager
import com.lagradost.shiro.utils.integrations.IntegrationPrefs
import com.lagradost.shiro.utils.mvvm.logError
import kotlin.math.abs

enum class SkipType(val label: String) { INTRO("Skip Intro"), OUTRO("Skip Outro"), RECAP("Skip Recap") }

data class SkipSegment(val type: SkipType, val startMs: Long, val endMs: Long)

/**
 * Intro/outro/recap timestamps. Anime Skip (api.anime-skip.com, needs the user's client id)
 * is tried first via the AniList id; AniSkip (api.aniskip.com, no key) is the fallback via the
 * MAL id. Blocking - call from a worker thread.
 */
object SkipTimes {
    private const val AS_API = "https://api.anime-skip.com/graphql"

    fun fetch(
        context: Context, anilistId: Int?, malId: Int?, episode: Int, durationMs: Long
    ): List<SkipSegment> {
        val prefs = IntegrationPrefs(context)
        var result = emptyList<SkipSegment>()
        if (prefs.animeSkipClientId.isNotEmpty() && anilistId != null) {
            result = fromAnimeSkip(prefs.animeSkipClientId, anilistId, episode, durationMs)
        }
        if (result.isEmpty() && malId != null) result = fromAniSkip(malId, episode, durationMs)
        return result.sortedBy { it.startMs }
    }

    fun enabled(prefs: IntegrationPrefs, type: SkipType) = when (type) {
        SkipType.INTRO -> prefs.skipIntro
        SkipType.OUTRO -> prefs.skipOutro
        SkipType.RECAP -> prefs.skipRecap
    }

    // ---------- Anime Skip ----------

    private fun gql(clientId: String, query: String) = try {
        val res = khttp.post(
            AS_API,
            headers = mapOf("X-Client-ID" to clientId),
            json = AuthManager.mapper.writeValueAsString(mapOf("query" to query)),
            timeout = 20.0
        )
        if (res.statusCode == 200) AuthManager.mapper.readTree(res.text).path("data") else null
    } catch (e: Exception) {
        logError(e); null
    }

    /** Segment type for an Anime Skip timestamp type name, or null when it isn't skippable. */
    fun typeOf(name: String): SkipType? = when (name.lowercase()) {
        "intro", "new intro", "mixed intro" -> SkipType.INTRO
        "credits", "new credits", "mixed credits" -> SkipType.OUTRO
        "recap" -> SkipType.RECAP
        else -> null
    }

    private fun fromAnimeSkip(clientId: String, anilistId: Int, episode: Int, durationMs: Long): List<SkipSegment> {
        val shows = gql(clientId, """query { findShowsByExternalId(service: ANILIST, serviceId: "$anilistId") { id } }""")
            ?.path("findShowsByExternalId") ?: return emptyList()
        var best: List<SkipSegment> = emptyList()
        var bestDiff = Long.MAX_VALUE
        for (show in shows.take(4)) {
            val eps = gql(
                clientId,
                """query { findEpisodesByShowId(showId: "${show.path("id").asText()}") { number baseDuration timestamps { at type { name } } } }"""
            )?.path("findEpisodesByShowId") ?: continue
            for (e in eps) {
                if (e.path("number").asText("") != episode.toString()) continue
                val segments = segmentsFromTimestamps(e.path("timestamps"), (e.path("baseDuration").asDouble(0.0) * 1000).toLong())
                if (segments.isEmpty()) continue
                val diff = if (durationMs > 0) abs(e.path("baseDuration").asDouble(0.0) * 1000 - durationMs).toLong() else 0L
                if (diff < bestDiff) {
                    best = segments; bestDiff = diff
                }
            }
        }
        return best
    }

    /**
     * Anime Skip timestamps are markers: a segment starts at `at` and lasts until the next
     * marker (or the end of the episode). Only skippable types become segments.
     */
    fun segmentsFromTimestamps(ts: com.fasterxml.jackson.databind.JsonNode, durationMs: Long): List<SkipSegment> {
        val sorted = ts.map { it.path("at").asDouble(0.0) to it.path("type").path("name").asText("") }.sortedBy { it.first }
        val out = ArrayList<SkipSegment>()
        for ((i, t) in sorted.withIndex()) {
            val type = typeOf(t.second) ?: continue
            val start = (t.first * 1000).toLong()
            val end = if (i + 1 < sorted.size) (sorted[i + 1].first * 1000).toLong() else durationMs
            if (end - start >= 3000) out.add(SkipSegment(type, start, end))
        }
        return out
    }

    // ---------- AniSkip ----------

    private fun fromAniSkip(malId: Int, episode: Int, durationMs: Long): List<SkipSegment> = try {
        val length = if (durationMs > 0) durationMs / 1000 else 0
        val url = "https://api.aniskip.com/v2/skip-times/$malId/$episode?types=op&types=ed&types=mixed-op&types=mixed-ed&types=recap&episodeLength=$length"
        val res = khttp.get(url, timeout = 15.0)
        if (res.statusCode != 200) emptyList() else parseAniSkip(res.text)
    } catch (e: Exception) {
        logError(e); emptyList()
    }

    fun parseAniSkip(json: String): List<SkipSegment> {
        val root = AuthManager.mapper.readTree(json)
        if (!root.path("found").asBoolean(false)) return emptyList()
        return root.path("results").mapNotNull { r ->
            val type = when (r.path("skipType").asText()) {
                "op", "mixed-op" -> SkipType.INTRO
                "ed", "mixed-ed" -> SkipType.OUTRO
                "recap" -> SkipType.RECAP
                else -> return@mapNotNull null
            }
            val i = r.path("interval")
            SkipSegment(type, (i.path("startTime").asDouble() * 1000).toLong(), (i.path("endTime").asDouble() * 1000).toLong())
        }
    }
}
