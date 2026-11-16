package com.lagradost.shiro.utils.mihon

import android.content.Context
import com.lagradost.shiro.utils.mvvm.logError
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SChapterImpl
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaImpl
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Blocking helpers over Mihon manga sources; every call must run on a worker thread. */
object MihonBridge {
    private const val TIMEOUT = 30_000L

    data class MangaItem(val title: String, val url: String, val thumbnail: String?)
    data class ChapterItem(val name: String, val url: String, val number: Float, val date: Long, val scanlator: String?)
    data class MangaDetails(
        val title: String, val url: String, val thumbnail: String?, val description: String?,
        val author: String?, val genre: String?, val status: Int, val chapters: List<ChapterItem>,
    )

    fun sources(context: Context): List<CatalogueSource> =
        MihonManager.sources(context).filterIsInstance<CatalogueSource>()

    fun label(s: CatalogueSource) = if (s.lang.isBlank() || s.lang == "all") s.name else "${s.name} (${s.lang})"

    fun sourceById(context: Context, id: Long): CatalogueSource? = sources(context).firstOrNull { it.id == id }

    private fun items(list: List<SManga>) = list.map { MangaItem(it.title, it.url, it.thumbnail_url) }

    fun popular(src: CatalogueSource, page: Int): Pair<List<MangaItem>, Boolean> =
        runBlocking { withTimeout(TIMEOUT) { src.getPopularManga(page) } }.let { items(it.mangas) to it.hasNextPage }

    fun search(src: CatalogueSource, query: String, page: Int): Pair<List<MangaItem>, Boolean> =
        runBlocking { withTimeout(TIMEOUT) { src.getSearchManga(page, query, src.getFilterList()) } }
            .let { items(it.mangas) to it.hasNextPage }

    fun details(src: CatalogueSource, url: String, fallbackTitle: String): MangaDetails {
        val stub = SMangaImpl().apply { this.url = url; title = fallbackTitle }
        val upd = runBlocking { withTimeout(TIMEOUT) { src.getMangaUpdate(stub, emptyList(), true, true) } }
        val m = upd.manga
        val chapters = upd.chapters.map { ChapterItem(it.name, it.url, it.chapter_number, it.date_upload, it.scanlator) }
        return MangaDetails(
            m.title.ifBlank { fallbackTitle }, url, m.thumbnail_url, m.description, m.author, m.genre, m.status, chapters
        )
    }

    fun pages(src: CatalogueSource, chapterUrl: String, chapterName: String): List<Page> {
        val ch = SChapterImpl().apply { url = chapterUrl; name = chapterName }
        return runBlocking { withTimeout(TIMEOUT) { src.getPageList(ch) } }
    }

    /** Downloads one page image through the source's own HTTP client (cookies/headers/referer intact). */
    fun imageBytes(src: CatalogueSource, page: Page): ByteArray? = try {
        val http = src as? HttpSource ?: return null
        runBlocking {
            withTimeout(TIMEOUT) {
                if (page.imageUrl.isNullOrBlank()) page.imageUrl = http.getImageUrl(page)
                http.getImage(page).use { it.body?.bytes() }
            }
        }
    } catch (t: Throwable) {
        logError(t)
        null
    }
}
