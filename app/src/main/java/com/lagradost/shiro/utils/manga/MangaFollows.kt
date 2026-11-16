package com.lagradost.shiro.utils.manga

import android.content.Context

/**
 * Per-device "followed manga" list — the manga counterpart of anime subscriptions.
 * Entries are `sourceId|url` keys plus a display title, stored as JSON-ish lines so the
 * notification worker can re-check each followed title for new chapters.
 */
object MangaFollows {
    private const val PREFS = "manga_follows"

    data class Follow(val sourceId: Long, val url: String, val title: String)

    private fun store(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun keyOf(sourceId: Long, url: String) = "$sourceId|$url"

    fun all(c: Context): List<Follow> =
        store(c).all.entries.mapNotNull { (k, v) ->
            val parts = k.split("|", limit = 2)
            val srcId = parts.getOrNull(0)?.toLongOrNull()
            if (srcId != null && parts.size == 2) Follow(srcId, parts[1], v as? String ?: "") else null
        }

    fun isFollowed(c: Context, sourceId: Long, url: String) = store(c).contains(keyOf(sourceId, url))

    fun set(c: Context, sourceId: Long, url: String, title: String, on: Boolean) {
        store(c).edit().apply {
            if (on) putString(keyOf(sourceId, url), title) else remove(keyOf(sourceId, url))
        }.apply()
    }

    /** Last seen chapter count per follow, in the same "first run sets baseline" spirit as episodes. */
    private fun seen(c: Context) = c.getSharedPreferences("episode_notify_seen", Context.MODE_PRIVATE)
    fun lastCount(c: Context, sourceId: Long, url: String) = seen(c).getInt("manga_" + keyOf(sourceId, url).hashCode(), -1)
    fun setCount(c: Context, sourceId: Long, url: String, count: Int) =
        seen(c).edit().putInt("manga_" + keyOf(sourceId, url).hashCode(), count).apply()
}
