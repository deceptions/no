package com.lagradost.shiro.ui.manga

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.jaredrummler.cyanea.app.CyaneaAppCompatActivity
import com.lagradost.shiro.utils.mihon.MihonBridge
import kotlin.concurrent.thread

/** Manga details and chapter list; tapping a chapter opens the reader. */
class MangaDetailActivity : CyaneaAppCompatActivity() {
    private var details: MihonBridge.MangaDetails? = null
    private lateinit var list: ListView
    private lateinit var status: TextView
    private lateinit var header: LinearLayout
    private lateinit var titleView: TextView
    private lateinit var meta: TextView
    private lateinit var desc: TextView
    private lateinit var cover: ImageView
    private var hideRead = false
    private val read: MutableSet<String> by lazy {
        getSharedPreferences("manga_read", MODE_PRIVATE).getStringSet("urls", emptySet())!!.toMutableSet()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sourceId = intent.getLongExtra("sourceId", 0)
        val url = intent.getStringExtra("url") ?: return finish()
        val fallbackTitle = intent.getStringExtra("title") ?: ""
        title = fallbackTitle
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        titleView = TextView(this).apply { textSize = 20f; setTypeface(typeface, android.graphics.Typeface.BOLD); text = fallbackTitle }
        meta = TextView(this).apply { textSize = 13f; setPadding(0, dp(4), 0, 0) }
        cover = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(0x22888888)
            layoutParams = LinearLayout.LayoutParams(dp(110), dp(160))
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, 0, 0)
            addView(titleView)
            addView(meta)
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; addView(cover); addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
        desc = TextView(this).apply { textSize = 14f; setPadding(0, dp(12), 0, dp(12)) }
        status = TextView(this).apply { text = "Loading…"; textSize = 14f; setPadding(0, dp(6), 0, dp(6)) }
        header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), 0)
            addView(top)
            addView(desc)
            addView(status)
            addView(android.widget.CheckBox(this@MangaDetailActivity).apply {
                text = "Hide read chapters"
                isChecked = hideRead
                setOnCheckedChangeListener { _, on -> hideRead = on; bindChapters() }
            })
            addView(android.widget.CheckBox(this@MangaDetailActivity).apply {
                text = "Follow — notify on new chapters"
                isChecked = com.lagradost.shiro.utils.manga.MangaFollows.isFollowed(
                    this@MangaDetailActivity, MangaSession.sourceId, url
                )
                setOnCheckedChangeListener { _, on ->
                    com.lagradost.shiro.utils.manga.MangaFollows.set(
                        this@MangaDetailActivity, MangaSession.sourceId, url,
                        MangaSession.mangaTitle, on
                    )
                }
            })
        }
        list = ListView(this).apply { addHeaderView(header, null, false); adapter = ArrayAdapter<String>(this@MangaDetailActivity, android.R.layout.simple_list_item_1, emptyList<String>()) }
        setContentView(list)

        val ctx = this
        thread {
            val src = try { MihonBridge.sourceById(ctx, sourceId) } catch (t: Throwable) { null }
            val d = try {
                if (src == null) null else MihonBridge.details(src, url, fallbackTitle)
            } catch (t: Throwable) {
                com.lagradost.shiro.utils.mvvm.logError(t)
                null
            }
            runOnUiThread {
                if (src == null || d == null) {
                    status.text = "Could not load this manga. Check your connection or try another source."
                    return@runOnUiThread
                }
                details = d
                MangaSession.sourceId = sourceId
                MangaSession.mangaTitle = d.title
                MangaSession.chapters = d.chapters
                title = d.title
                titleView.text = d.title
                meta.text = listOfNotNull(
                    d.author?.takeIf { it.isNotBlank() },
                    when (d.status) { 1 -> "Ongoing"; 2 -> "Completed"; 3 -> "Licensed"; 4 -> "Publishing finished"; 5 -> "Cancelled"; 6 -> "On hiatus"; else -> null },
                    d.genre?.takeIf { it.isNotBlank() }
                ).joinToString("\n")
                desc.text = d.description.orEmpty()
                MangaSession.loadCover(src, d.thumbnail ?: intent.getStringExtra("thumb"), cover, 400)
                status.text = if (d.chapters.isEmpty()) "No chapters found" else "${d.chapters.size} chapters"
                bindChapters()
            }
        }
    }

    private var shownIdx: List<Int> = emptyList()

    private fun bindChapters() {
        val d = details ?: return
        // Shown chapter indexes point back into the full list so the reader navigates correctly.
        shownIdx = d.chapters.indices.filter { !hideRead || d.chapters[it].url !in read }
        val labels = shownIdx.map { i -> d.chapters[i].let {
            val dl = if (com.lagradost.shiro.utils.manga.ChapterDownloads.isDownloaded(this, MangaSession.sourceId, it.url)) "⬇  " else ""
            (if (it.url in read) "✓  " else "") + dl + it.name.ifBlank { "Chapter ${it.number}" }
        } }
        status.text = if (hideRead && shownIdx.size != d.chapters.size) "${d.chapters.size} chapters (${shownIdx.size} unread)"
                      else if (d.chapters.isEmpty()) "No chapters found" else "${d.chapters.size} chapters"
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
        list.setOnItemClickListener { _, _, pos, _ ->
            val shownPos = pos - list.headerViewsCount
            if (shownPos in shownIdx.indices) {
                startActivity(Intent(this, MangaReaderActivity::class.java).putExtra("index", shownIdx[shownPos]))
            }
        }
        list.setOnItemLongClickListener { _, _, pos, _ ->
            val shownPos = pos - list.headerViewsCount
            if (shownPos in shownIdx.indices) chapterMenu(d.chapters[shownIdx[shownPos]])
            true
        }
    }

    /** Long-press chapter: download for offline reading / delete the download / toggle read. */
    private fun chapterMenu(ch: MihonBridge.ChapterItem) {
        val dl = com.lagradost.shiro.utils.manga.ChapterDownloads.isDownloaded(this, MangaSession.sourceId, ch.url)
        val opts = mutableListOf<String>()
        if (dl) opts += "Delete download" else opts += "Download chapter"
        opts += "Download all unread"
        opts += if (ch.url in read) "Mark unread" else "Mark read"
        opts += "Mark previous read"
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(ch.name.ifBlank { "Chapter ${ch.number}" })
            .setItems(opts.toTypedArray()) { _, which ->
                when (opts[which]) {
                    "Download chapter" -> downloadChapter(ch)
                    "Download all unread" -> downloadAllUnread()
                    "Delete download" -> { com.lagradost.shiro.utils.manga.ChapterDownloads.delete(this, MangaSession.sourceId, ch.url); bindChapters() }
                    "Mark read" -> { read.add(ch.url); saveRead(); bindChapters() }
                    "Mark previous read" -> {
                        // Chapters are newest-first; "previous" in reading order is everything below this one.
                        val list = details?.chapters ?: return@setItems
                        val pos = list.indexOfFirst { it.url == ch.url }
                        if (pos >= 0) list.drop(pos + 1).forEach { read.add(it.url) }
                        saveRead(); bindChapters()
                    }
                    else -> { read.remove(ch.url); saveRead(); bindChapters() }
                }
            }
            .show()
    }

    private fun saveRead() {
        getSharedPreferences("manga_read", MODE_PRIVATE).edit().putStringSet("urls", read.toSet()).apply()
    }

    private fun downloadChapter(ch: MihonBridge.ChapterItem) {
        val sourceId = intent.getLongExtra("sourceId", 0)
        status.text = "Downloading ${ch.name}…"
        thread {
            val ok = try {
                MihonBridge.sourceById(this, sourceId)?.let { src ->
                    com.lagradost.shiro.utils.manga.ChapterDownloads.download(this, src, sourceId, MangaSession.mangaTitle, ch)
                } ?: false
            } catch (t: Throwable) { com.lagradost.shiro.utils.mvvm.logError(t); false }
            runOnUiThread {
                status.text = if (ok) "Downloaded ${ch.name}" else "Download failed — check the source/network"
                bindChapters()
            }
        }
    }

    /** Sequential bulk download of every unread chapter; skips ones already on disk. */
    private fun downloadAllUnread() {
        val d = details ?: return
        val sourceId = intent.getLongExtra("sourceId", 0)
        val todo = d.chapters.filter { it.url !in read && !com.lagradost.shiro.utils.manga.ChapterDownloads.isDownloaded(this, sourceId, it.url) }
        if (todo.isEmpty()) { status.text = "Nothing to download"; return }
        status.text = "Downloading 0/${todo.size}…"
        thread {
            val src = try { MihonBridge.sourceById(this, sourceId) } catch (t: Throwable) { null }
            var done = 0
            for (ch in todo) {
                if (src == null) break
                try { com.lagradost.shiro.utils.manga.ChapterDownloads.download(this, src, sourceId, MangaSession.mangaTitle, ch) } catch (t: Throwable) { com.lagradost.shiro.utils.mvvm.logError(t) }
                done++
                val d2 = done; val total = todo.size
                runOnUiThread { status.text = "Downloading $d2/$total…" }
            }
            runOnUiThread { status.text = "Downloaded $done/${todo.size} unread chapters"; bindChapters() }
        }
    }

    override fun onResume() {
        super.onResume()
        read.clear()
        read.addAll(getSharedPreferences("manga_read", MODE_PRIVATE).getStringSet("urls", emptySet())!!)
        bindChapters()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }
}
