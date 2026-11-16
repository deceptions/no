package com.lagradost.shiro.utils.meta

import androidx.preference.PreferenceManager
import com.lagradost.shiro.AcraApplication
import com.lagradost.shiro.utils.LiveApi
import com.lagradost.shiro.utils.NekomoApi.Companion.AnimePageNew
import com.lagradost.shiro.utils.NekomoApi.Companion.AnimePageNewRoot
import com.lagradost.shiro.utils.mvvm.logError

/**
 * Extensions (Aniyomi, CloudStream, Zangetsu) are only meant to stream. Their own title pages are often thin: empty
 * titles, missing covers or banners, one-line synopses, no genres or scores. This fills the gaps from the built-in
 * catalogue (AniList, with Kitsu / MyAnimeList as backups) so the details page is always complete. Episodes and
 * stream links are never touched: they stay exactly as the extension reports them.
 */
object MetaEnricher {
    private fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

    /** Drops "(Dub)", "[Sub]", "English Dub" and similar tags extensions append to titles. */
    fun clean(title: String): String =
        title.replace(Regex("[\\[(][^\\])]{0,20}[\\])]"), " ")
            .replace(Regex("(?i)\\b(english\\s+)?(dubbed|dub|subbed|sub|uncensored|hd)\\b"), " ")
            .replace(Regex("\\s+"), " ").trim()

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

    fun enrich(root: AnimePageNewRoot, extSlug: String): AnimePageNewRoot = try {
        val meta = findMetadata(root.data.anime, extSlug)
        if (meta == null) root
        else root.copy(data = root.data.copy(anime = merge(root.data.anime, meta)))
    } catch (e: Throwable) {
        logError(e)
        root
    }

    private fun findMetadata(a: AnimePageNew, extSlug: String): AnimePageNew? {
        val prefs = AcraApplication.context?.let { PreferenceManager.getDefaultSharedPreferences(it) }
        var builtIn = prefs?.getString("enrich_$extSlug", null)
        if (builtIn == null) {
            val q = clean(a.title).takeIf { it.isNotBlank() && !it.equals("Unknown", true) } ?: return null
            val results = (LiveApi.search(q, null)?.takeIf { it.isNotEmpty() } ?: MetaHub.fallbackSearch(q))
                .filter { it.type == "anime" || it.type.isBlank() }
            val best = results.map { d ->
                d to listOfNotNull(d.title, d.title_english, d.native_title).maxOf { similarity(q, it) }
            }.maxByOrNull { it.second }
            if (best == null || best.second < 0.8) return null
            builtIn = best.first.slug
            prefs?.edit()?.putString("enrich_$extSlug", builtIn)?.apply()
        }
        // Metadata only: no episode lookups and no recursion into extension enrichment
        return LiveApi.getAnimePage(builtIn, withEpisodes = false, enrich = false)?.data?.anime
    }

    /** The built-in catalogue is the source of truth for descriptive data; the extension only fills what it lacks. */
    private fun merge(ext: AnimePageNew, m: AnimePageNew): AnimePageNew {
        fun pick(builtIn: String, own: String) = if (builtIn.isBlank() || builtIn == "null") own else builtIn
        return ext.copy(
            title = m.title.ifBlank { ext.title },
            native_title = pick(m.native_title, ext.native_title),
            poster = m.poster.ifBlank { ext.poster },
            banner = m.banner.ifBlank { ext.banner.takeIf { it != ext.poster } ?: ext.poster },
            ids = if (m.ids.isNotBlank() && m.ids != "{}") m.ids else ext.ids,
            format = pick(m.format, ext.format),
            episode_duration = pick(m.episode_duration, ext.episode_duration),
            synopsis = m.synopsis.ifBlank { ext.synopsis.replace(Regex("""^[★☆\s\d.]{3,}"""), "").trim() },
            season = pick(m.season, ext.season),
            release_year = m.release_year?.takeIf { it.isNotBlank() } ?: ext.release_year,
            score = pick(m.score, ext.score),
            studios = pick(m.studios, ext.studios),
            genres = pick(m.genres, ext.genres),
            aired = pick(m.aired, ext.aired),
            status = pick(m.status, ext.status),
            trailer = pick(m.trailer, ext.trailer),
        )
    }
}
