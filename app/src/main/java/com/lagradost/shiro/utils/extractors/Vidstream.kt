package com.lagradost.shiro.utils.extractors

import com.lagradost.shiro.utils.APIS
import com.lagradost.shiro.utils.ExtractorLink
import com.lagradost.shiro.utils.mvvm.logError
import com.lagradost.shiro.utils.pmap
import org.jsoup.Jsoup

class Vidstream(var providersActive: HashSet<String> = HashSet()) {
    val name: String = "Vidstream"
    private val mainUrl: String
        get() {
            return "https://streamani.net"
//            if (settingsManager?.getBoolean(
//                    "alternative_vidstream",
//                    false
//                ) == true
//            ) "https://streamani.net" else "https://gogo-stream.com"
        }

    private fun getExtractorUrl(id: String): String {
        return "$mainUrl/streaming.php?id=$id"
    }

    // https://gogo-stream.com/streaming.php?id=MTE3NDg5
    //   https://streamani.net/streaming.php?id=MTE3NDg5
    fun getUrl(id: String, isCasting: Boolean = false, callback: (ExtractorLink) -> Unit): Boolean {
        //val extractedLinksList: MutableList<ExtractorLink> = mutableListOf()
        val normalApis = arrayListOf(Shiro(), MultiQuality())
        try {
            normalApis.pmap { api ->
                if (providersActive.size == 0 || providersActive.contains(api.name)) {
                    val url = api.getExtractorUrl(id)
                    val source = api.getUrl(url)
                    source?.forEach {
                        // When shiro serves unavailable.mp4
                        if (!it.url.contains("********")) callback.invoke(it)
                    }
                }
            }

            val url = getExtractorUrl(id)
            with(khttp.get(url)) {
                val document = Jsoup.parse(this.text)
                val primaryLinks = document.select("ul.list-server-items > li.linkserver")
                // All vidstream links passed to extractors
                primaryLinks.forEach { element ->
                    val link = element.attr("data-video")
                    //val name = element.text()

                    // Matches vidstream links with extractors
                    APIS.filter {
                        (!it.requiresReferer || !isCasting) && (providersActive.size == 0 || providersActive.contains(
                            it.name
                        ))
                    }.pmap { api ->
                        if (link.startsWith(api.mainUrl)) {
                            val extractedLinks = api.getUrl(link, url)
                            if (extractedLinks?.isNotEmpty() == true) {
                                extractedLinks.forEach {
                                    callback.invoke(it)
                                }
                            }
                        }
                    }
                }
            }
            return true
        } catch (e: Exception) {
            logError(e)
            return false
        }
    }
}