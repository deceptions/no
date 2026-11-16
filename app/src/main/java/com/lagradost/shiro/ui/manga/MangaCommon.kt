package com.lagradost.shiro.ui.manga

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.widget.ImageView
import com.lagradost.shiro.utils.mihon.MihonBridge
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.online.HttpSource
import java.util.concurrent.Executors

/** Holds what the manga screens share: the open chapter list and a small bitmap loader. */
object MangaSession {
    var chapters: List<MihonBridge.ChapterItem> = emptyList()
    var sourceId: Long = 0
    var mangaTitle: String = ""

    private val pool = Executors.newFixedThreadPool(3)
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun decode(bytes: ByteArray, maxWidth: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxWidth) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** Loads [url] into [view] through the source's own HTTP client (covers often need its headers). */
    fun loadCover(src: CatalogueSource?, url: String?, view: ImageView, maxWidth: Int = 300) {
        view.tag = url
        view.setImageDrawable(null)
        if (url.isNullOrBlank()) return
        cache.get(url)?.let { view.setImageBitmap(it); return }
        pool.execute {
            val bytes = try {
                val http = src as? HttpSource
                val req = okhttp3.Request.Builder().url(url).apply { http?.headers?.let { headers(it) } }.build()
                (http?.client ?: okhttp3.OkHttpClient()).newCall(req).execute().use { it.body?.bytes() }
            } catch (t: Throwable) {
                null
            }
            val bmp = bytes?.let { decode(it, maxWidth) } ?: return@execute
            cache.put(url, bmp)
            view.post { if (view.tag == url) view.setImageBitmap(bmp) }
        }
    }

    fun putBitmap(key: String, b: Bitmap) = cache.put(key, b)
    fun getBitmap(key: String): Bitmap? = cache.get(key)
    fun run(block: () -> Unit) = pool.execute(block)
}
