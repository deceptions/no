package com.lagradost.shiro.utils

import kotlin.math.max
import kotlin.math.min

/** Small in-house fuzzy matcher (replaces the GPL-2.0 fuzzywuzzy library). Scores are 0..100. */
object FuzzyMatch {
    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = min(min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    fun ratio(a: String, b: String): Int {
        val len = a.length + b.length
        if (len == 0) return 100
        return ((1.0 - levenshtein(a, b).toDouble() / max(a.length, b.length)) * 100).toInt().coerceIn(0, 100)
    }

    /** Best ratio of the shorter string against every same-length window of the longer one. */
    fun partialRatio(s1: String, s2: String): Int {
        val (short, long) = if (s1.length <= s2.length) s1 to s2 else s2 to s1
        if (short.isEmpty()) return if (long.isEmpty()) 100 else 0
        var best = 0
        for (i in 0..(long.length - short.length)) {
            best = max(best, ratio(short, long.substring(i, i + short.length)))
            if (best == 100) break
        }
        return best
    }
}
