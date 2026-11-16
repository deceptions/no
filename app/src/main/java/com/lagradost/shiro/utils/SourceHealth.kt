package com.lagradost.shiro.utils

import java.util.concurrent.ConcurrentHashMap

/**
 * Remembers (for the current session) which of the user's sources keep failing, so the UI can say so instead of
 * leaving people guessing. Keys look like "cs:<provider>", "zg:<id>" and "ay:<id>".
 */
object SourceHealth {
    private val streaks = ConcurrentHashMap<String, Int>()

    fun ok(key: String) {
        streaks[key] = 0
    }

    fun fail(key: String) {
        streaks[key] = (streaks[key] ?: 0) + 1
    }

    private val latency = ConcurrentHashMap<String, Long>()

    fun record(key: String, ms: Long, success: Boolean) {
        if (success) {
            ok(key)
            latency[key] = latency[key]?.let { (it * 6 + ms * 4) / 10 } ?: ms
        } else fail(key)
    }

    /** Healthy and fast sources first; failing ones last. */
    fun rank(keys: List<String>): List<String> =
        keys.sortedBy { (if (isFailing(it)) 1_000_000L else 0L) + (latency[it] ?: 3000L) }

    fun isFailing(key: String) = (streaks[key] ?: 0) >= 2

    fun label(label: String, key: String) = if (isFailing(key)) "$label  (not responding)" else label
}
