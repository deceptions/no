package com.lagradost.shiro.utils.extractors

import com.lagradost.shiro.utils.ExtractorApi
import com.lagradost.shiro.utils.ExtractorLink
import com.lagradost.shiro.utils.Qualities
import com.lagradost.shiro.utils.mvvm.logError

class StreamTape : ExtractorApi() {
    override val name: String = "StreamTape"
    override val mainUrl: String = "https://streamtape.com"
    override val requiresReferer = false

    // Because they add concatenation to fuck up scrapers
    private val linkRegex =
        Regex("""(i(|" \+ ')d(|" \+ ')=.*?&(|" \+ ')e(|" \+ ')x(|" \+ ')p(|" \+ ')i(|" \+ ')r(|" \+ ')e(|" \+ ')s(|" \+ ')=.*?&(|" \+ ')i(|" \+ ')p(|" \+ ')=.*?&(|" \+ ')t(|" \+ ')o(|" \+ ')k(|" \+ ')e(|" \+ ')n(|" \+ ')=.*)'""")

    override fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        try {
            with(khttp.get(url)) {
                linkRegex.find(this.text)?.let {
                    val extractedUrl = "https://streamtape.com/get_video?${it.groupValues[1]}".replace("""" + '""", "")
                    return listOf(
                        ExtractorLink(
                            name,
                            extractedUrl,
                            url,
                            Qualities.Unknown.value,
                        )
                    )
                }
            }
        } catch (e: Exception) {
            logError(e)
        }
        return null
    }
}