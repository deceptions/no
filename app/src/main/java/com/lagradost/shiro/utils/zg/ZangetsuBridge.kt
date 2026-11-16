package com.lagradost.shiro.utils.zg

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.json.JsonMapper
import com.lagradost.shiro.utils.ExtractorLink
import com.lagradost.shiro.utils.Qualities
import com.lagradost.shiro.utils.NekomoApi
import com.lagradost.shiro.AcraApplication
import com.lagradost.shiro.utils.mvvm.logError
import com.lagradost.shiro.utils.subs.SubtitleProviders
import com.lagradost.shiro.utils.subs.SubtitleResult
import java.nio.charset.Charset

/**
 * Maps Zangetsu provider results into the app's data model so installed sources appear in search, on the
 * anime page and in the player. Slugs/tokens look like "zg-hex(sourceId)-hex(url)" (hex + '-' only, safe
 * in file names, prefs keys and "|"-split tokens).
 */
object ZangetsuBridge {
    private const val PREFIX = "zg-"
    private val mapper = JsonMapper.builder().build()

    fun isZgSlug(slug: String) = slug.startsWith(PREFIX)

    fun makeSlug(sourceId: String, url: String) = "$PREFIX${hex(sourceId)}-${hex(url)}"

    fun parseSlug(slug: String): Pair<String, String>? {
        if (!isZgSlug(slug)) return null
        val parts = slug.split("-", limit = 3)
        if (parts.size != 3) return null
        return (unhex(parts[1]) ?: return null) to (unhex(parts[2]) ?: return null)
    }

    private fun hex(s: String) = s.toByteArray(Charset.forName("UTF-8")).joinToString("") { "%02x".format(it) }
    private fun unhex(s: String): String? = try {
        s.chunked(2).map { it.toInt(16).toByte() }.toByteArray().toString(Charset.forName("UTF-8"))
    } catch (t: Throwable) {
        null
    }

    private fun ctx() = AcraApplication.context

    private fun JsonNode.s(name: String): String =
        path(name).let { if (it.isMissingNode || it.isNull) "" else it.asText("") }

    private fun sourceOf(id: String): ZangetsuManager.ZgInstalled? {
        val c = ctx() ?: return null
        return ZangetsuManager.getInstalled(c).firstOrNull { it.id == id && ZangetsuManager.isEnabled(c, id) }
    }

    // ---------- search ----------

    data class Hit(val title: String, val slug: String)

    fun searchOne(source: ZangetsuManager.ZgInstalled, query: String): List<NekomoApi.Companion.Data> {
        val json = ZangetsuManager.call(source, "search", "[${JsonMapper.builder().build().writeValueAsString(query)},1,{}]", 30_000)
        if (json == null) return emptyList()
        return try {
            val arr = mapper.readTree(json)
            arr.mapNotNull { n ->
                val url = n.s("url").ifEmpty { n.s("id") }
                if (url.isEmpty()) return@mapNotNull null
                toData(source, makeSlug(source.id, url), n.s("title").ifEmpty { n.s("englishTitle") }, n.s("cover"))
            }
        } catch (t: Throwable) {
            logError(t)
            emptyList()
        }
    }

    /** Queries every enabled Zangetsu source for [query]; sources are run one after another on the JS thread. */
    fun search(query: String): List<NekomoApi.Companion.Data> {
        val c = ctx() ?: return emptyList()
        return ZangetsuManager.enabledSources(c).flatMap { searchOne(it, query) }
    }

    fun searchBySource(sourceId: String, query: String): List<Hit> {
        val src = sourceOf(sourceId) ?: return emptyList()
        return searchOne(src, query).map { Hit(it.title, it.slug) }
    }

    private fun toData(src: ZangetsuManager.ZgInstalled, slug: String, title: String, cover: String) =
        NekomoApi.Companion.Data(
            id = "", slug = slug, title = title, title_english = src.name, native_title = "",
            poster = cover, banner = "", ids = "{}", type = src.type, format = "", episodes = "",
            episode_duration = "", synopsis = "", language = "", synonyms = "", season = "",
            release_year = null, score = "", rating = "", studios = "", genres = "", aired = "",
            status = "", trailer = "", total_views = "0", created_at = "", updated_at = ""
        )

    // ---------- details / episodes ----------

    fun getAnimePage(slug: String): NekomoApi.Companion.AnimePageNewRoot? {
        val (sourceId, url) = parseSlug(slug) ?: return null
        val src = sourceOf(sourceId) ?: return null
        val json = ZangetsuManager.call(src, "getDetail", "[${mapper.writeValueAsString(url)},{}]", 40_000) ?: return null
        return try {
            val d = mapper.readTree(json)
            val eps = d.path("episodes").mapIndexed { i, e ->
                val epUrl = e.s("url").ifEmpty { e.s("id") }
                val num = e.path("number").let { if (it.isNumber) it.asInt() else i + 1 }
                NekomoApi.Companion.AnimePageNewEpisodes(
                    id = "${src.id}-$i", slug = "${src.id}-$i",
                    title = e.s("title").ifEmpty { "Episode $num" },
                    episode = num.toString(),
                    image = e.s("thumbnail").ifEmpty { null },
                    insight = null,
                    sources = "[{\"slug\":\"gogostream\",\"source\":\"${makeSlug(src.id, epUrl)}\"}]",
                    ext = null, views = "0", created_at = "", updated_at = ""
                )
            }
            val cover = d.s("cover")
            val anime = NekomoApi.Companion.AnimePageNew(
                id = "", slug = slug, title = d.s("title").ifEmpty { "Unknown" },
                title_english = src.name, native_title = "", poster = cover, banner = cover, ids = "{}",
                type = src.type, format = "", episodes = eps.size.toString(), episode_duration = "",
                synopsis = d.s("description"), language = "", synonyms = "", season = "",
                release_year = d.s("year").ifEmpty { null }, score = "", rating = "", studios = d.path("studios").joinToString { it.asText() },
                genres = d.path("genres").joinToString(",") { it.asText() }, aired = "", status = d.s("status"),
                trailer = "", total_views = "0", created_at = "", updated_at = ""
            )
            NekomoApi.Companion.AnimePageNewRoot(
                "success", "", NekomoApi.Companion.AnimePageNewData(anime = anime, episodes = eps)
            )
        } catch (t: Throwable) {
            logError(t)
            null
        }
    }

    // ---------- streams ----------

    fun resolveStreams(token: String, callback: (ExtractorLink) -> Unit): Boolean {
        val (sourceId, epUrl) = parseSlug(token) ?: return false
        val src = sourceOf(sourceId) ?: return true
        val json = ZangetsuManager.call(src, "getVideoSources", "[${mapper.writeValueAsString(epUrl)}]", 60_000)
            ?: return true
        try {
            SubtitleProviders.extensionSubtitles.clear()
            val root = mapper.readTree(json)
            // providers return either a list of streams or { sources: [...], subtitles: [...] }
            val list = if (root.isArray) root else root.path("sources").let { if (it.isArray) it else root.path("streams") }
            if (list.size() == 0) logError(Exception("Zangetsu ${src.id}: no streams for $epUrl. Provider answered: ${json.take(300)}"))
            list.forEach { st ->
                val url = st.s("url")
                if (url.isEmpty()) return@forEach
                val headers = HashMap<String, String>()
                st.path("headers").fields().forEach { (k, v) -> headers[k] = v.asText() }
                val q = st.s("quality")
                val quality = when {
                    q.contains("2160") || q.contains("4k", true) -> Qualities.UHD
                    q.contains("1080") -> Qualities.FullHd
                    q.contains("720") -> Qualities.HD
                    q.isEmpty() || q.equals("auto", true) -> Qualities.FullHd
                    else -> Qualities.SD
                }
                val hls = st.s("container").equals("hls", true) || url.contains(".m3u8")
                callback(
                    ExtractorLink(
                        "${src.name} • ${q.ifEmpty { "auto" }}", url, headers["Referer"] ?: "",
                        quality.value, hls, false, headers.ifEmpty { null }
                    )
                )
                st.path("subtitles").forEach { sub ->
                    val su = sub.s("url")
                    if (su.isNotEmpty()) {
                        val lang = sub.s("lang").ifEmpty { sub.s("label") }.ifEmpty { "Subtitle" }
                        SubtitleProviders.extensionSubtitles.add(
                            SubtitleResult("Extension", lang, lang, su, headers)
                        )
                    }
                }
            }
        } catch (t: Throwable) {
            logError(t)
        }
        return true
    }
}
