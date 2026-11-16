package com.lagradost.shiro.utils.manga

import android.content.Context
import com.lagradost.shiro.utils.mihon.MihonBridge
import com.lagradost.shiro.utils.mvvm.logError
import eu.kanade.tachiyomi.source.CatalogueSource
import com.lagradost.shiro.utils.mihon.MihonBridge.ChapterItem
import java.io.File
import java.security.MessageDigest

/**
 * Downloaded manga chapters: every page image saved under
 * `filesDir/manga_dl/<sourceId>/<md5(chapterUrl)>/p<i>` plus a meta.json with
 * title/url/count. The reader checks [pages] first so downloaded chapters open offline.
 */
object ChapterDownloads {
    private fun md5(s: String) =
        MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    fun dir(ctx: Context, sourceId: Long, url: String) =
        File(ctx.filesDir, "manga_dl/$sourceId/${md5(url)}")

    fun isDownloaded(ctx: Context, sourceId: Long, url: String) =
        File(dir(ctx, sourceId, url), "meta.json").exists()

    /** Page files in reading order, or null when the chapter is not downloaded. */
    fun pages(ctx: Context, sourceId: Long, url: String): List<File>? {
        val d = dir(ctx, sourceId, url)
        val meta = File(d, "meta.json")
        if (!meta.exists()) return null
        val files = d.listFiles { f -> f.name.startsWith("p") && f.name != "meta.json" }
            ?.sortedBy { it.name.removePrefix("p").toIntOrNull() ?: 0 }
            ?.filter { it.length() > 0 }
        return files?.takeIf { it.isNotEmpty() }
    }

    /**
     * Blocking; call off the main thread. Downloads every page through the source's own client.
     * Deletes the partial chapter on failure and returns false.
     */
    fun download(ctx: Context, source: CatalogueSource, sourceId: Long, mangaTitle: String, ch: ChapterItem, onPage: (Int, Int) -> Unit = { _, _ -> }): Boolean {
        val d = dir(ctx, sourceId, ch.url)
        if (File(d, "meta.json").exists()) return true
        val tmp = File(d.parentFile, d.name + ".part")
        try {
            tmp.deleteRecursively()
            tmp.mkdirs()
            val list = MihonBridge.pages(source, ch.url, ch.name)
            if (list.isEmpty()) return false.also { tmp.deleteRecursively() }
            list.forEachIndexed { i, page ->
                val bytes = MihonBridge.imageBytes(source, page) ?: return false.also { tmp.deleteRecursively() }
                File(tmp, "p$i").writeBytes(bytes)
                onPage(i + 1, list.size)
            }
            File(tmp, "meta.json").writeText(
                """{"sourceId":$sourceId,"url":"${ch.url.replace("\"", "\\\"")}","name":"${ch.name.replace("\"", "\\\"")}","manga":"${mangaTitle.replace("\"", "\\\"")}","pages":${list.size}}"""
            )
            d.deleteRecursively()
            tmp.renameTo(d)
            return true
        } catch (t: Throwable) {
            logError(t)
            tmp.deleteRecursively()
            return false
        }
    }

    fun delete(ctx: Context, sourceId: Long, url: String) {
        dir(ctx, sourceId, url).deleteRecursively()
    }

    data class Downloaded(val sourceId: Long, val url: String, val name: String, val manga: String, val pages: Int)

    private fun metaStr(meta: String, key: String) =
        Regex("\"$key\":\"((?:[^\"\\\\]|\\\\.)*)\"").find(meta)?.groupValues?.get(1)?.replace("\\\"", "\"") ?: ""
    private fun metaNum(meta: String, key: String) =
        Regex("\"$key\":(\\d+)").find(meta)?.groupValues?.get(1)?.toLongOrNull() ?: 0L

    /** Every downloaded chapter on this device, newest directories first. */
    fun listAll(ctx: Context): List<Downloaded> {
        val root = File(ctx.filesDir, "manga_dl")
        val out = ArrayList<Downloaded>()
        root.listFiles { f -> f.isDirectory }?.forEach { srcDir ->
            val sid = srcDir.name.toLongOrNull() ?: return@forEach
            srcDir.listFiles { f -> f.isDirectory && !f.name.endsWith(".part") }?.forEach { d ->
                val meta = try { File(d, "meta.json").readText() } catch (t: Throwable) { null } ?: return@forEach
                out += Downloaded(sid, metaStr(meta, "url"), metaStr(meta, "name"), metaStr(meta, "manga"), metaNum(meta, "pages").toInt())
            }
        }
        return out.sortedByDescending { File(root, "${it.sourceId}/${md5(it.url)}").lastModified() }
    }

}
