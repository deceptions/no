package com.lagradost.shiro.utils.extractors

import android.util.Base64
import com.lagradost.shiro.utils.ExtractorApi
import com.lagradost.shiro.utils.ExtractorLink
import com.lagradost.shiro.utils.Qualities
import com.lagradost.shiro.utils.mvvm.logError

class Shiro : ExtractorApi() {
    override val name: String = "Shiro"
    override val mainUrl: String = "https://s04.subsplea.se"
    override val requiresReferer = false

    override fun getExtractorUrl(id: String): String {
        return "$mainUrl/${Base64.encodeToString(id.toByteArray(), Base64.DEFAULT).trimEnd('=')}"
    }

    override fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        try {
            val headers = mapOf("Referer" to (referer ?: "********"))
            val redirectedUrl = khttp.get(url, headers = headers, stream = true).url
            return listOf(
                ExtractorLink(
                    name,
                    redirectedUrl.replace(" ", "%20"),
                    "https://s04.subsplea.se/",
                    // UHD to give top priority
                    Qualities.UHD.value,
                    redirectedUrl.endsWith(".m3u8")
                )
            )

//            println(url)
//            val headers = mapOf("Referer" to "********")
//            val res = khttp.get(url, headers = headers).text
//            println("RESPONSE TEXT $res")
//            Jsoup.parse(res).select("source").firstOrNull()?.attr("src")?.replace("&amp;", "?")?.let {
//                return listOf(
//                    ExtractorLink(
//                        name,
//                        it.replace(" ", "%20"),
//                        "https://s04.subsplea.se/",
//                        // UHD to give top priority
//                        Qualities.UHD.value
//                    )
//                )
//            }
        } catch (e: Exception) {
            logError(e)
        }
        return null
    }
}