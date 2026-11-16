package com.lagradost.shiro.utils

import android.content.Context
import androidx.preference.PreferenceManager
import com.lagradost.shiro.utils.NekomoApi.Companion.AnimePageNewData
import com.lagradost.shiro.utils.aniyomi.AniyomiBridge
import com.lagradost.shiro.utils.cs3.CsBridge
import com.lagradost.shiro.utils.cs3.CsPluginManager
import com.lagradost.shiro.utils.mvvm.logError
import com.lagradost.shiro.utils.zg.ZangetsuBridge
import com.lagradost.shiro.utils.zg.ZangetsuManager

/**
 * Streaming is kept separate from metadata: the built-in catalogue (AniList and its backups) supplies titles, art,
 * descriptions and episode lists, and an installed extension is only used to find the matching episode and its streams.
 * Source keys: "cs:<provider>", "zg:<id>", "ay:<id>".
 */
object SourcePlayback {
    data class Hit(val title: String, val slug: String)

    sealed class Outcome {
        /** The episode was found in the source: open [page] at [index] in the player. */
        data class Found(val page: AnimePageNewData, val index: Int, val sourceLabel: String, val extSlug: String) : Outcome()

        /** Several possible titles in the source; the user has to pick the right one. */
        data class PickMatch(val hits: List<Hit>, val sourceLabel: String) : Outcome()

        /** Something the user needs to know (missing, incompatible, unavailable, no matching episode). */
        data class Problem(val message: String) : Outcome()
    }

    // ---- installed sources ----

    data class Installed(val key: String, val label: String)

    fun installed(ctx: Context): List<Installed> {
        val cs = runCatching { CsPluginManager.providers().map { it.name }.distinct() }.getOrDefault(emptyList())
        val zg = runCatching { ZangetsuManager.enabledSources(ctx) }.getOrDefault(emptyList())
        val ay = runCatching { AniyomiBridge.sourceChoices() }.getOrDefault(emptyList())
        return cs.map { Installed("cs:$it", it) } + zg.map { Installed("zg:${it.id}", "Zangetsu · ${it.name}") } +
                ay.map { Installed("ay:${it.second}", "Aniyomi · ${it.first}") }
    }

    fun label(ctx: Context, key: String): String = installed(ctx).firstOrNull { it.key == key }?.label ?: key.substringAfter(':')

    /** The source that should be used when the user simply taps an episode, or null when they have to choose. */
    fun defaultKey(ctx: Context): String? {
        val all = installed(ctx)
        val preferred = PreferredSource.get(ctx)
        return when {
            preferred != null && all.any { it.key == preferred } -> preferred
            all.size == 1 -> all.first().key
            else -> null
        }
    }

    // ---- searching a source ----

    private fun search(key: String, title: String): List<Hit> = when {
        key.startsWith("ay:") -> AniyomiBridge.searchBySource(key.removePrefix("ay:").toLong(), title).map { Hit(it.title, it.slug) }
        key.startsWith("zg:") -> ZangetsuBridge.searchBySource(key.removePrefix("zg:"), title).map { Hit(it.title, it.slug) }
        else -> CsBridge.searchByProvider(key.removePrefix("cs:"), title).map { Hit(it.title, it.slug) }
    }

    private fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

    private fun similarity(a: String, b: String): Double {
        val x = norm(a)
        val y = norm(b)
        if (x.isEmpty() || y.isEmpty()) return 0.0
        if (x == y) return 1.0
        if (x.contains(y) || y.contains(x)) return 0.8
        val wx = x.split(' ').toSet()
        val wy = y.split(' ').toSet()
        return wx.intersect(wy).size.toDouble() / wx.union(wy).size
    }

    private fun matchKey(metaSlug: String, key: String) = "srcmatch_${metaSlug}_$key"

    fun rememberMatch(ctx: Context, metaSlug: String, key: String, extSlug: String) {
        PreferenceManager.getDefaultSharedPreferences(ctx).edit().putString(matchKey(metaSlug, key), extSlug).apply()
    }

    fun forgetMatch(ctx: Context, metaSlug: String, key: String) {
        PreferenceManager.getDefaultSharedPreferences(ctx).edit().remove(matchKey(metaSlug, key)).apply()
    }

    // ---- the workflow: metadata episode -> source episode ----

    /**
     * Finds [episodeNumber] (1-based) of the title in the given source. Blocking: call off the main thread.
     * [chosenExtSlug] is set after the user picked one of the [Outcome.PickMatch] hits.
     */
    fun resolve(
        ctx: Context, metaSlug: String, titles: List<String>, episodeNumber: Int, key: String, chosenExtSlug: String? = null,
    ): Outcome {
        val src = installed(ctx).firstOrNull { it.key == key }
            ?: return Outcome.Problem(
                "${key.substringAfter(':')} is not installed or is turned off. Install or enable it in Settings > Extensions, " +
                        "or pick another source."
            )

        var extSlug = chosenExtSlug ?: PreferenceManager.getDefaultSharedPreferences(ctx).getString(matchKey(metaSlug, key), null)
        if (extSlug == null) {
            val queries = titles.filter { it.isNotBlank() }.distinct()
            var hits: List<Hit> = emptyList()
            var failure: Throwable? = null
            for (q in queries) {
                try {
                    hits = search(key, q)
                } catch (t: Throwable) {
                    failure = t
                    logError(t)
                }
                if (hits.isNotEmpty()) break
            }
            if (hits.isEmpty()) {
                return Outcome.Problem(
                    if (failure != null) "${src.label} did not respond. The extension may be incompatible or its website may be down. Try another source."
                    else "\"${queries.firstOrNull() ?: "This title"}\" was not found in ${src.label}. Try another source."
                )
            }
            val scored = hits.map { h -> h to (queries.maxOfOrNull { similarity(it, h.title) } ?: 0.0) }.sortedByDescending { it.second }
            val best = scored.first()
            val tie = scored.size > 1 && scored[1].second >= best.second
            if (best.second < 0.8 || tie) return Outcome.PickMatch(scored.map { it.first }, src.label)
            extSlug = best.first.slug
        }

        val page = try {
            LiveApi.getAnimePage(extSlug, withEpisodes = true, enrich = false)?.data
        } catch (t: Throwable) {
            logError(t)
            null
        } ?: run {
            forgetMatch(ctx, metaSlug, key)
            return Outcome.Problem("${src.label} could not open this title. The extension may be incompatible or broken, or its website is unavailable.")
        }
        if (page.episodes.isEmpty()) {
            return Outcome.Problem("${src.label} found the title but lists no episodes for it yet.")
        }

        val byNumber = page.episodes.indexOfFirst { it.episode?.trim()?.toDoubleOrNull()?.toInt() == episodeNumber }
        val index = when {
            byNumber >= 0 -> byNumber
            episodeNumber - 1 in page.episodes.indices -> episodeNumber - 1
            else -> return Outcome.Problem("${src.label} has ${page.episodes.size} episode(s) for this title and none matches episode $episodeNumber.")
        }
        rememberMatch(ctx, metaSlug, key, extSlug)
        return Outcome.Found(page, index, src.label, extSlug)
    }

    private fun timed(
        ctx: Context, metaSlug: String, titles: List<String>, episodeNumber: Int, key: String, chosen: String?, limitMs: Long,
    ): Outcome {
        val t0 = System.currentTimeMillis()
        val result = com.lagradost.shiro.utils.meta.Race.first<Outcome>(limitMs, listOf({ resolve(ctx, metaSlug, titles, episodeNumber, key, chosen) }), deadlineMs = limitMs)
            ?: Outcome.Problem("${label(ctx, key)} took too long to answer.")
        SourceHealth.record(key, System.currentTimeMillis() - t0, result is Outcome.Found || result is Outcome.PickMatch)
        return result
    }

    /**
     * Like [resolve], but when the chosen source fails or is too slow the other installed sources are tried, healthiest and
     * fastest first. Only confident matches are used automatically; when none works the original problem is reported.
     */
    fun resolveAuto(
        ctx: Context, metaSlug: String, titles: List<String>, episodeNumber: Int, key: String, chosenExtSlug: String? = null, limitMs: Long = 20_000,
    ): Outcome {
        val first = timed(ctx, metaSlug, titles, episodeNumber, key, chosenExtSlug, limitMs)
        if (first !is Outcome.Problem || chosenExtSlug != null) return first
        val tried = arrayListOf(label(ctx, key))
        for (k in SourceHealth.rank(installed(ctx).map { it.key }.filter { it != key })) {
            val o = timed(ctx, metaSlug, titles, episodeNumber, k, null, limitMs)
            if (o is Outcome.Found) return o
            tried += label(ctx, k)
        }
        return if (tried.size > 1) Outcome.Problem(first.message + "\n\nAlso tried: " + tried.drop(1).joinToString(", ") + ".") else first
    }

    // ---- keeping the tracker in sync while streaming ----

    private fun linkKey(extSlug: String) = "metalink_$extSlug"

    /** Remembers which metadata title an extension page belongs to, so watching there also updates the tracker. */
    fun link(ctx: Context, extSlug: String, metaSlug: String, title: String, poster: String?, total: Int, delta: Int) {
        PreferenceManager.getDefaultSharedPreferences(ctx).edit()
            .putString(linkKey(extSlug), listOf(metaSlug, title.replace("\n", " "), poster ?: "", total.toString(), delta.toString()).joinToString("\n")).apply()
    }

    /** Called when an extension episode is (almost) finished: marks the matching metadata episode as watched. */
    fun onEpisodeWatched(ctx: Context, extSlug: String, extIndex: Int) {
        val parts = PreferenceManager.getDefaultSharedPreferences(ctx).getString(linkKey(extSlug), null)?.split("\n") ?: return
        if (parts.size < 5) return
        val metaIndex = extIndex + (parts[4].toIntOrNull() ?: 0)
        if (metaIndex < 0 || Tracker.isWatched(ctx, parts[0], metaIndex)) return
        Tracker.mark(ctx, parts[0], parts[1], parts[2].ifBlank { null }, parts[3].toIntOrNull() ?: 0, metaIndex, metaIndex, true)
    }
}
