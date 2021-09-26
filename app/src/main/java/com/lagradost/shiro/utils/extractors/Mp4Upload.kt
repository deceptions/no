package com.lagradost.shiro.utils.extractors

import com.lagradost.shiro.utils.ExtractorApi
import com.lagradost.shiro.utils.ExtractorLink
import com.lagradost.shiro.utils.Qualities
import com.lagradost.shiro.utils.getAndUnpack
import com.lagradost.shiro.utils.mvvm.logError

class Mp4Upload : ExtractorApi() {
    override val name: String = "Mp4Upload"
    override val mainUrl: String = "https://www.mp4upload.com"
    private val srcRegex = Regex("""player\.src\("(.*?)"""")
    override val requiresReferer = true

    override fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        try {
            with(khttp.get(url)) {
                if (this.text == "File was deleted") return@with
                getAndUnpack(this.text)?.let { unpackedText ->
                    srcRegex.find(unpackedText)?.groupValues?.get(1)?.let { link ->
                        return listOf(
                            ExtractorLink(
                                name,
                                link,
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