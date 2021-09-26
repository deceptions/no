package com.lagradost.shiro.utils.extractors

import com.lagradost.shiro.utils.ExtractorApi
import com.lagradost.shiro.utils.ExtractorLink
import com.lagradost.shiro.utils.Qualities
import com.lagradost.shiro.utils.getAndUnpack
import com.lagradost.shiro.utils.mvvm.logError

class StreamSB : ExtractorApi() {
    override val name: String = "StreamSB"
    override val mainUrl: String = "https://sbplay.org"
    private val sourceRegex = Regex("""sources:[\W\w]*?file:\s*"(.*?)"""")

    //private val m3u8Regex = Regex(""".*?(\d*).m3u8""")
    //private val urlRegex = Regex("""(.*?)([^/]+$)""")

    // 1: Resolution 2: url
    private val m3u8UrlRegex = Regex("""RESOLUTION=\d*x(\d*).*\n(http.*.m3u8)""")
    override val requiresReferer = false

    private fun getQuality(string: String): Int {
        return when (string) {
            "360" -> Qualities.SD.value
            "480" -> Qualities.SD.value
            "720" -> Qualities.HD.value
            "1080" -> Qualities.FullHd.value
            else -> Qualities.Unknown.value
        }
    }

    // 	https://sbembed.com/embed-ns50b0cukf9j.html   ->   https://sbvideo.net/play/ns50b0cukf9j
    override fun getUrl(url: String, referer: String?): List<ExtractorLink> {
        val extractedLinksList: MutableList<ExtractorLink> = mutableListOf()
        val newUrl = url.replace("sbplay.org/embed-", "sbplay.org/play/").removeSuffix(".html")
        try {
            with(khttp.get(newUrl, timeout = 10.0)) {
                val fixedText = getAndUnpack(text) ?: text
                sourceRegex.findAll(fixedText).forEach { sourceMatch ->
                    val extractedUrl = sourceMatch.groupValues[1]
                    if (extractedUrl.contains(".m3u8")) {
                        with(khttp.get(extractedUrl)) {
                            m3u8UrlRegex.findAll(this.text).forEach { match ->
                                val extractedUrlM3u8 = match.groupValues[2]
                                val extractedRes = match.groupValues[1]
                                extractedLinksList.add(
                                    ExtractorLink(
                                        "$name ${extractedRes}p",
                                        extractedUrlM3u8,
                                        extractedUrl,
                                        getQuality(extractedRes),
                                        true
                                    )
                                )
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            logError(e)
        }
        return extractedLinksList
    }
}