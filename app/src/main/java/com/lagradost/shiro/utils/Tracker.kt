package com.lagradost.shiro.utils

import DataStore.containsKey
import DataStore.removeKey
import DataStore.setKey
import VIEWSTATE_KEY
import android.content.Context
import androidx.preference.PreferenceManager
import com.lagradost.shiro.utils.AppUtils.getViewKey

/**
 * Tracker-only features: mark episodes watched and keep progress without any streaming source. Everything is stored
 * on the device (the same watched flags the player uses), mirrors into the offline library, and is pushed to
 * AniList when signed in. Incognito mode turns all of it off.
 */
object Tracker {
    fun isWatched(ctx: Context, slug: String, index: Int) = ctx.containsKey(VIEWSTATE_KEY, getViewKey(slug, index))

    /** Highest episode that is marked watched (1-based), 0 when none. */
    fun progress(ctx: Context, slug: String, total: Int): Int =
        (total - 1 downTo 0).firstOrNull { isWatched(ctx, slug, it) }?.plus(1) ?: 0

    /** Marks episodes [from]..[to] (0-based, inclusive). Returns false while incognito (nothing is saved). */
    fun mark(ctx: Context, slug: String, title: String, poster: String?, total: Int, from: Int, to: Int, watched: Boolean): Boolean {
        if (Privacy.incognito(ctx)) return false
        for (i in from..to) {
            val key = getViewKey(slug, i)
            if (watched) ctx.setKey(VIEWSTATE_KEY, key, System.currentTimeMillis()) else ctx.removeKey(VIEWSTATE_KEY, key)
        }
        val progress = progress(ctx, slug, total)
        LocalLibrary.setProgress(ctx, slug, title, poster, progress, total)
        return true
    }
}

/** The user's preferred streaming source, chosen in the title page dropdown or in Settings -> General. */
object PreferredSource {
    private const val KEY = "preferred_source"

    fun get(ctx: Context): String? =
        PreferenceManager.getDefaultSharedPreferences(ctx).getString(KEY, null)?.takeIf { it.isNotBlank() && it != "none" }

    fun set(ctx: Context, key: String?) {
        PreferenceManager.getDefaultSharedPreferences(ctx).edit().putString(KEY, key ?: "none").apply()
    }
}
