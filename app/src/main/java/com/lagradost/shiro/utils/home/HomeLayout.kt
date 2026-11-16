package com.lagradost.shiro.utils.home

import android.content.Context
import androidx.preference.PreferenceManager
import com.lagradost.shiro.utils.NekomoApi

/**
 * User-customizable home page: which sources are on, which sections are shown and in what order.
 * Defaults give a sensible AniList-based discovery page; everything else is opt-in.
 */
object HomeLayout {
    private const val KEY_ORDER = "home_order_v1"
    private const val KEY_ENABLED = "home_enabled_v1"
    private const val KEY_DISABLED = "home_disabled_v1"
    private const val KEY_SOURCES_ON = "home_sources_on_v1"
    private const val KEY_SOURCES_OFF = "home_sources_off_v1"

    private val defaultOrder = listOf(
        LocalHomeSource.CONTINUE, LocalHomeSource.SUBSCRIBED, LocalHomeSource.FAVORITES,
        "trending", "recent", "popular", "recommended",
        "genre:Action", "genre:Romance", "genre:Comedy", "genre:Fantasy",
        "season", "top_rated", "official:watch"
    )

    data class Entry(val def: HomeSectionDef, val source: HomeSource, val enabled: Boolean)

    private fun prefs(ctx: Context) = PreferenceManager.getDefaultSharedPreferences(ctx)
    private fun set(ctx: Context, key: String) = prefs(ctx).getStringSet(key, emptySet()) ?: emptySet()

    // ---- sources ----

    fun sourceEnabled(ctx: Context, source: HomeSource): Boolean = when {
        source.id in set(ctx, KEY_SOURCES_ON) -> true
        source.id in set(ctx, KEY_SOURCES_OFF) -> false
        else -> source.defaultEnabled
    }

    fun setSourceEnabled(ctx: Context, sourceId: String, on: Boolean) {
        val onSet = set(ctx, KEY_SOURCES_ON).toMutableSet().apply { if (on) add(sourceId) else remove(sourceId) }
        val offSet = set(ctx, KEY_SOURCES_OFF).toMutableSet().apply { if (on) remove(sourceId) else add(sourceId) }
        prefs(ctx).edit().putStringSet(KEY_SOURCES_ON, onSet).putStringSet(KEY_SOURCES_OFF, offSet).apply()
    }

    // ---- sections ----

    private fun order(ctx: Context): List<String> {
        val saved = prefs(ctx).getString(KEY_ORDER, null)?.split("\n")?.filter { it.isNotBlank() }.orEmpty()
        val all = HomeRegistry.allSections().map { it.id }
        val known = (saved + defaultOrder).filter { it in all }.distinct()
        // Sections that appeared later (new genres, a freshly installed provider...) go at the end
        return known + all.filter { it !in known }
    }

    fun sectionEnabled(ctx: Context, def: HomeSectionDef): Boolean = when {
        def.id in set(ctx, KEY_ENABLED) -> true
        def.id in set(ctx, KEY_DISABLED) -> false
        else -> def.defaultEnabled
    }

    fun setSectionEnabled(ctx: Context, id: String, on: Boolean) {
        val en = set(ctx, KEY_ENABLED).toMutableSet().apply { if (on) add(id) else remove(id) }
        val dis = set(ctx, KEY_DISABLED).toMutableSet().apply { if (on) remove(id) else add(id) }
        prefs(ctx).edit().putStringSet(KEY_ENABLED, en).putStringSet(KEY_DISABLED, dis).apply()
    }

    /** All sections in the user's order, including disabled ones and ones from disabled sources. */
    fun entries(ctx: Context): List<Entry> {
        val defs = HomeRegistry.allSections().associateBy { it.id }
        return order(ctx).mapNotNull { id ->
            val def = defs[id] ?: return@mapNotNull null
            val src = HomeRegistry.source(def.sourceId) ?: return@mapNotNull null
            Entry(def, src, sectionEnabled(ctx, def))
        }
    }

    /** What the home screen actually shows. */
    fun active(ctx: Context): List<Entry> =
        entries(ctx).filter {
            it.enabled && sourceEnabled(ctx, it.source) &&
                    (!it.source.nonAnime || com.lagradost.shiro.utils.ContentPrefs.nonAnime(ctx))
        }

    fun move(ctx: Context, id: String, delta: Int) {
        val list = order(ctx).toMutableList()
        val i = list.indexOf(id)
        val j = (i + delta).coerceIn(0, list.size - 1)
        if (i < 0 || i == j) return
        list.add(j, list.removeAt(i))
        prefs(ctx).edit().putString(KEY_ORDER, list.joinToString("\n")).apply()
    }

    fun reset(ctx: Context) {
        prefs(ctx).edit().remove(KEY_ORDER).remove(KEY_ENABLED).remove(KEY_DISABLED)
            .remove(KEY_SOURCES_ON).remove(KEY_SOURCES_OFF).apply()
        HomeFeed.clear()
    }
}

/** Fetches and caches section contents so the home page opens instantly and API terms/rate limits are respected. */
object HomeFeed {
    private const val TTL_MS = 20 * 60 * 1000L
    private val cache = HashMap<String, Pair<Long, List<NekomoApi.CommonAnimePageData>>>()

    fun cached(id: String) = cache[id]?.takeIf { System.currentTimeMillis() - it.first < TTL_MS }?.second

    fun clear() = synchronized(cache) { cache.clear() }

    /** Blocking; call off the main thread. */
    fun load(ctx: Context, entry: HomeLayout.Entry, force: Boolean = false): List<NekomoApi.CommonAnimePageData>? {
        if (!force) cached(entry.def.id)?.let { return it }
        val items = try {
            entry.source.fetch(ctx, entry.def)
        } catch (e: Exception) {
            null
        }?.filter { !HomeFilters.isBlocked(it.name, it.english) }
        if (!items.isNullOrEmpty()) synchronized(cache) { cache[entry.def.id] = System.currentTimeMillis() to items }
        return items
    }
}
