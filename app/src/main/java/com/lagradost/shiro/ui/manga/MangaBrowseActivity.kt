package com.lagradost.shiro.ui.manga

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.jaredrummler.cyanea.app.CyaneaAppCompatActivity
import com.lagradost.shiro.utils.mihon.MihonBridge
import eu.kanade.tachiyomi.source.CatalogueSource
import kotlin.concurrent.thread

/** Browse and search the manga sources the user installed (Mihon extensions). */
class MangaBrowseActivity : CyaneaAppCompatActivity() {
    private var sources: List<CatalogueSource> = emptyList()
    private var current: CatalogueSource? = null
    private var query = ""
    private var page = 1
    private var hasNext = false
    private var loading = false
    private val items = ArrayList<MihonBridge.MangaItem>()

    private lateinit var sourceBtn: Button
    private lateinit var input: EditText
    private lateinit var status: TextView
    private lateinit var adapter: Adapter

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Manga"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), 0) }
        sourceBtn = Button(this).apply { text = "Choose source"; setOnClickListener { chooseSource() } }
        input = EditText(this).apply {
            hint = "Search manga"
            maxLines = 1
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { _, _, _ -> startQuery(text.toString()); true }
        }
        val searchBtn = Button(this).apply { text = "Search"; setOnClickListener { startQuery(input.text.toString()) } }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(searchBtn)
        status = TextView(this).apply { setPadding(0, dp(8), 0, dp(8)); textSize = 14f }
        adapter = Adapter()
        val list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MangaBrowseActivity)
            adapter = this@MangaBrowseActivity.adapter
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                    val lm = rv.layoutManager as LinearLayoutManager
                    if (hasNext && !loading && lm.findLastVisibleItemPosition() >= items.size - 3) loadMore()
                }
            })
        }
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(sourceBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(Button(this@MangaBrowseActivity).apply {
                text = "Downloaded"
                setOnClickListener { showDownloads() }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        })
        root.addView(row)
        root.addView(status)
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        status.text = "Loading sources…"
        thread {
            val list2 = try { MihonBridge.sources(this) } catch (t: Throwable) { emptyList() }
            runOnUiThread {
                sources = list2
                if (sources.isEmpty()) {
                    status.text = "No manga sources installed.\nAdd a Mihon extension repository in Settings → Extensions and install the sources you want."
                    return@runOnUiThread
                }
                val saved = getSharedPreferences("manga", MODE_PRIVATE).getLong("source", 0)
                select(sources.firstOrNull { it.id == saved } ?: sources.first())
            }
        }
    }

    private fun chooseSource() {
        if (sources.isEmpty()) return
        val names = sources.map { MihonBridge.label(it) }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Choose source").setItems(names) { _, i -> select(sources[i]) }.show()
    }

    private fun select(src: CatalogueSource) {
        current = src
        getSharedPreferences("manga", MODE_PRIVATE).edit().putLong("source", src.id).apply()
        sourceBtn.text = "Source: ${MihonBridge.label(src)}"
        startQuery(input.text.toString())
    }

    private fun startQuery(q: String) {
        query = q.trim()
        page = 1
        items.clear()
        adapter.notifyDataSetChanged()
        loadMore()
    }

    private fun loadMore() {
        val src = current ?: return
        loading = true
        status.text = if (items.isEmpty()) "Loading…" else ""
        val requested = page
        thread {
            val res = try {
                if (query.isEmpty()) MihonBridge.popular(src, requested) else MihonBridge.search(src, query, requested)
            } catch (t: Throwable) {
                com.lagradost.shiro.utils.mvvm.logError(t)
                null
            }
            runOnUiThread {
                loading = false
                if (src !== current) return@runOnUiThread
                if (res == null) {
                    status.text = "Could not load results from this source. Try another source."
                    hasNext = false
                    return@runOnUiThread
                }
                val start = items.size
                items.addAll(res.first.filter { n -> items.none { it.url == n.url } })
                hasNext = res.second && res.first.isNotEmpty()
                page = requested + 1
                adapter.notifyItemRangeInserted(start, items.size - start)
                status.text = if (items.isEmpty()) "No results" else ""
            }
        }
    }

    /** List downloaded chapters; tap reads offline, long-press deletes. */
    private fun showDownloads() {
        val all = com.lagradost.shiro.utils.manga.ChapterDownloads.listAll(this)
        if (all.isEmpty()) {
            Toast.makeText(this, "No downloaded chapters yet. Long-press a chapter on a manga page to download it.", Toast.LENGTH_LONG).show()
            return
        }
        val labels = all.map { "${it.manga}\n${it.name} · ${it.pages} pages" }.toTypedArray()
        val dialog = AlertDialog.Builder(this).setTitle("Downloaded chapters")
            .setItems(labels, null).create()
        dialog.listView.setOnItemClickListener { _, _, pos, _ ->
            val e = all[pos]
            MangaSession.sourceId = e.sourceId
            MangaSession.mangaTitle = e.manga
            MangaSession.chapters = listOf(MihonBridge.ChapterItem(e.name, e.url, 0f, 0L, null))
            dialog.dismiss()
            startActivity(Intent(this, MangaReaderActivity::class.java).putExtra("index", 0))
        }
        dialog.listView.setOnItemLongClickListener { _, _, pos, _ ->
            val e = all[pos]
            com.lagradost.shiro.utils.manga.ChapterDownloads.delete(this, e.sourceId, e.url)
            dialog.dismiss()
            showDownloads()
            true
        }
        dialog.show()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }

    private inner class Adapter : RecyclerView.Adapter<Adapter.VH>() {
        inner class VH(val row: LinearLayout, val cover: ImageView, val title: TextView) : RecyclerView.ViewHolder(row)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val cover = ImageView(this@MangaBrowseActivity).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(0x22888888)
                layoutParams = LinearLayout.LayoutParams(dp(64), dp(92))
            }
            val title = TextView(this@MangaBrowseActivity).apply {
                textSize = 16f
                setPadding(dp(12), 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val row = LinearLayout(this@MangaBrowseActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, dp(6))
                isClickable = true
                isFocusable = true
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                addView(cover)
                addView(title)
            }
            return VH(row, cover, title)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val it = items[position]
            h.title.text = it.title
            MangaSession.loadCover(current, it.thumbnail, h.cover)
            h.row.setOnClickListener { _ ->
                val src = current ?: return@setOnClickListener
                startActivity(
                    Intent(this@MangaBrowseActivity, MangaDetailActivity::class.java)
                        .putExtra("sourceId", src.id).putExtra("url", it.url).putExtra("title", it.title)
                        .putExtra("thumb", it.thumbnail)
                )
            }
        }
    }
}
