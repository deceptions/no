package com.lagradost.shiro.utils.extractors

import com.lagradost.shiro.utils.ExtractorLink
import com.lagradost.shiro.utils.mvvm.logError

class Vidstream(var providersActive: HashSet<String> = HashSet()) {
    val name: String = "Vidstream"
    fun getUrl(id: String, isCasting: Boolean = false, callback: (ExtractorLink) -> Unit): Boolean {
        // New backend: episode sources are "<showRef>|<episode>|<sub|dub>" tokens
        if (id.contains("|") || com.lagradost.shiro.utils.cs3.CsBridge.isCs3Slug(id) ||
            com.lagradost.shiro.utils.zg.ZangetsuBridge.isZgSlug(id) ||
            com.lagradost.shiro.utils.aniyomi.AniyomiBridge.isAySlug(id)
        ) {
            return try {
                com.lagradost.shiro.utils.LiveApi.resolveStreams(id, isCasting, callback)
            } catch (e: Exception) {
                logError(e)
                false
            }
        }
        // Legacy fastani/vidstream ids are dead with the old backend
        return false
    }
}