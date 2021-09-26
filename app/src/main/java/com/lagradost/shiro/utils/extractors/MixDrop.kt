package com.lagradost.shiro.utils.extractors

import com.lagradost.shiro.utils.*
import com.lagradost.shiro.utils.ShiroApi.Companion.USER_AGENT
import com.lagradost.shiro.utils.mvvm.logError

class MixDrop : ExtractorApi() {
    override val name: String = "MixDrop"
    override val mainUrl: String = "https://mixdrop.co"
    private val srcRegex = Regex("""wurl.*?=.*?"(.*?)";""")
    override val requiresReferer = false

    override fun getExtractorUrl(id: String): String {
        return "$mainUrl/e/$id"
    }

    override fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        try {
            val headers =  mapOf("User-Agent" to USER_AGENT)
            with(khttp.get(url, headers = headers)) {
                getAndUnpack(this.text)?.let { unpackedText ->
                    srcRegex.find(unpackedText)?.groupValues?.get(1)?.let { link ->
                        return listOf(
                            ExtractorLink(
                                name,
                                httpsify(link),
                                url,
                                Qualities.Unknown.value,
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            logError(e)
        }
        return null
    }
}