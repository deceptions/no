package com.lagradost.shiro.ui.manga

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.lagradost.shiro.utils.mihon.MihonBridge
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.Page
import kotlin.concurrent.thread

/**
 * Manga reader with three modes (continuous webtoon scroll, paged left-to-right and paged
 * right-to-left), reader background choice, volume-key page turning, keep-screen-on and immersive
 * fullscreen. All options live in Settings -> General -> Manga reader and can also be changed
 * mid-chapter from the gear button; the reading position is remembered per chapter.
 */
class MangaReaderActivity : AppCompatActivity() {
    private var index = 0
    private var src: CatalogueSource? = null
    private var pages: List<Page> = emptyList()
    private var localFiles: List<java.io.File>? = null // set when the chapter is downloaded for offline reading
    private lateinit var root: FrameLayout
    private lateinit var recycler: RecyclerView
    private lateinit var label: TextView
    private lateinit var pageLabel: TextView
    private lateinit var gear: TextView
    private lateinit var progress: View
    private lateinit var message: TextView
    private var screenWidth = 1080
    private var snapHelper: PagerSnapHelper? = null

    private val prefs get() = PreferenceManager.getDefaultSharedPreferences(this)
    private val mode get() = prefs.getString("manga_reader_mode", "webtoon") ?: "webtoon"
    private val paged get() = mode != "webtoon"

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        screenWidth = resources.displayMetrics.widthPixels
        index = intent.getIntExtra("index", 0)

        root = FrameLayout(this)
        recycler = RecyclerView(this)
        label = TextView(this).apply {
            setPadding(dp(12), dp(6), dp(12), dp(6))
            textSize = 13f
        }
        pageLabel = TextView(this).apply {
            setPadding(dp(12), dp(6), dp(12), dp(6))
            textSize = 13f
        }
        gear = TextView(this).apply {
            text = "⚙"
            textSize = 22f
            setPadding(dp(12), dp(4), dp(12), dp(4))
            setOnClickListener { showReaderSettings() }
        }
        progress = android.widget.ProgressBar(this)
        message = TextView(this).apply { gravity = Gravity.CENTER; setPadding(dp(24), 0, dp(24), 0); visibility = View.GONE }
        root.addView(recycler, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(label, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START))
        root.addView(gear, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END))
        root.addView(pageLabel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END))
        root.addView(progress, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        root.addView(message, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        setContentView(root)

        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                updatePageLabel(currentPage())
            }
        })

        applyUi()
        configureRecycler()
        loadChapter()
    }

    // ---- reader chrome ----

    private fun applyUi() {
        if (prefs.getBoolean("manga_keep_screen_on", true)) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        applyImmersive()

        val dark = when (prefs.getString("manga_reader_bg", "black")) {
            "gray" -> 0xFF202020.toInt()
            "white" -> Color.WHITE
            else -> Color.BLACK
        }
        root.setBackgroundColor(dark)
        val onBg = dark == Color.WHITE
        val fg = if (onBg) Color.BLACK else Color.WHITE
        val chip = if (onBg) 0x99FFFFFF.toInt() else 0x99000000.toInt()
        label.setTextColor(fg); label.setBackgroundColor(chip)
        pageLabel.setTextColor(fg); pageLabel.setBackgroundColor(chip)
        gear.setTextColor(fg); gear.setBackgroundColor(chip)
        message.setTextColor(fg)
    }

    private fun applyImmersive() {
        window.decorView.systemUiVisibility = if (prefs.getBoolean("manga_immersive", true)) {
            (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
        } else 0
    }

    private var gapDeco: RecyclerView.ItemDecoration? = null

    private fun configureRecycler(keepPage: Int = -1) {
        snapHelper?.attachToRecyclerView(null)
        val lm = when (mode) {
            "ltr" -> LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
            "rtl" -> LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, true)
            else -> LinearLayoutManager(this)
        }
        recycler.layoutManager = lm
        gapDeco?.let { recycler.removeItemDecoration(it) }
        val gapPx = when (prefs.getString("manga_page_gap", "none")) {
            "small" -> dp(4); "medium" -> dp(10); else -> 0
        }
        if (gapPx > 0) {
            val horiz = lm.orientation == LinearLayoutManager.HORIZONTAL
            gapDeco = object : RecyclerView.ItemDecoration() {
                override fun getItemOffsets(outRect: android.graphics.Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
                    if (horiz) outRect.right = gapPx else outRect.bottom = gapPx
                }
            }
            recycler.addItemDecoration(gapDeco!!)
        } else gapDeco = null
        if (paged) {
            snapHelper = PagerSnapHelper().also { it.attachToRecyclerView(recycler) }
        } else {
            snapHelper = null
        }
        if (pages.isNotEmpty()) recycler.adapter = PageAdapter()
        if (keepPage >= 0) recycler.post { lm.scrollToPosition(keepPage) }
    }

    private fun currentPage(): Int {
        val lm = recycler.layoutManager as? LinearLayoutManager ?: return 0
        val snapped = snapHelper?.findSnapView(lm)?.let { lm.getPosition(it) }
        return (snapped ?: lm.findFirstVisibleItemPosition()).coerceAtLeast(0)
    }

    private fun updatePageLabel(pos: Int) {
        if (pages.isEmpty()) { pageLabel.text = ""; return }
        pageLabel.text = "${(pos + 1).coerceAtMost(pages.size)} / ${pages.size}"
    }

    // ---- position memory ----

    private fun chapterUrl() = MangaSession.chapters.getOrNull(index)?.url ?: ""

    private fun savePosition() {
        if (!prefs.getBoolean("manga_remember_position", true) || chapterUrl().isEmpty()) return
        getSharedPreferences("manga_read", MODE_PRIVATE).edit()
            .putInt("pos_${chapterUrl()}", currentPage()).apply()
    }

    private fun restorePosition() {
        if (!prefs.getBoolean("manga_remember_position", true)) return
        val saved = getSharedPreferences("manga_read", MODE_PRIVATE).getInt("pos_${chapterUrl()}", 0)
        if (saved in 1 until pages.size) {
            recycler.post { (recycler.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(saved, 0) }
        }
    }

    // ---- settings dialog ----

    private fun showReaderSettings() {
        val ctx = this
        val modes = arrayOf("Continuous scroll (webtoon)", "Paged, left to right", "Paged, right to left")
        val modeValues = arrayOf("webtoon", "ltr", "rtl")
        val bgs = arrayOf("Black", "Dark gray", "White")
        val bgValues = arrayOf("black", "gray", "white")
        val toggles = arrayOf("Volume keys turn pages", "Keep screen on", "Fullscreen (hide system bars)", "Remember position per chapter", "Auto-download next chapter")
        val toggleKeys = arrayOf("manga_volume_keys", "manga_keep_screen_on", "manga_immersive", "manga_remember_position", "manga_auto_download")
        val toggleDefaults = booleanArrayOf(true, true, true, true, false)

        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
        }
        fun heading(t: String) = TextView(ctx).apply { text = t; textSize = 13f; alpha = 0.6f; setPadding(0, dp(10), 0, dp(2)) }

        box.addView(heading("Reading mode"))
        val modeButtons = modeValues.map { value ->
            android.widget.RadioButton(ctx).apply { text = modes[modeValues.indexOf(value)]; isChecked = mode == value }
        }
        modeButtons.forEach { box.addView(it) }
        box.addView(heading("Background"))
        val bgButtons = bgValues.map { value ->
            android.widget.RadioButton(ctx).apply { text = bgs[bgValues.indexOf(value)]; isChecked = prefs.getString("manga_reader_bg", "black") == value }
        }
        bgButtons.forEach { box.addView(it) }
        box.addView(heading("Page gap"))
        val gapValues = arrayOf("none", "small", "medium")
        val gapButtons = gapValues.map { v ->
            android.widget.RadioButton(ctx).apply {
                text = v.replaceFirstChar { it.uppercase() }
                isChecked = prefs.getString("manga_page_gap", "none") == v
            }
        }
        gapButtons.forEach { box.addView(it) }
        box.addView(heading("Options"))
        val checks = toggleKeys.mapIndexed { i, key ->
            android.widget.CheckBox(ctx).apply { text = toggles[i]; isChecked = prefs.getBoolean(key, toggleDefaults[i]) }
        }
        checks.forEach { box.addView(it) }

        AlertDialog.Builder(ctx)
            .setTitle("Reader settings")
            .setView(android.widget.ScrollView(ctx).apply { addView(box) })
            .setPositiveButton("Apply") { _, _ ->
                val page = currentPage()
                val e = prefs.edit()
                modeValues.forEachIndexed { i, v -> if (modeButtons[i].isChecked) e.putString("manga_reader_mode", v) }
                bgValues.forEachIndexed { i, v -> if (bgButtons[i].isChecked) e.putString("manga_reader_bg", v) }
                gapValues.forEachIndexed { i, v -> if (gapButtons[i].isChecked) e.putString("manga_page_gap", v) }
                toggleKeys.forEachIndexed { i, k -> e.putBoolean(k, checks[i].isChecked) }
                e.apply()
                applyUi()
                configureRecycler(page)
                updatePageLabel(page)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- keys ----

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            if (!prefs.getBoolean("manga_volume_keys", true)) return super.onKeyDown(keyCode, event)
            val forward = keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
            val lm = recycler.layoutManager as? LinearLayoutManager ?: return true
            if (paged) {
                lm.scrollToPosition((currentPage() + if (forward) 1 else -1).coerceIn(0, pages.size))
            } else {
                recycler.scrollBy(0, ((if (forward) 1 else -1) * recycler.height * 0.8f).toInt())
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onPause() {
        super.onPause()
        savePosition()
    }

    // ---- chapters ----

    private fun loadChapter() {
        val chapters = MangaSession.chapters
        val ch = chapters.getOrNull(index) ?: return finish()
        label.text = "${MangaSession.mangaTitle} · ${ch.name}"
        pageLabel.text = ""
        progress.visibility = View.VISIBLE
        message.visibility = View.GONE
        recycler.adapter = null
        pages = emptyList()
        localFiles = null
        // Downloaded chapters read straight from disk — no source, no network.
        val local = com.lagradost.shiro.utils.manga.ChapterDownloads.pages(this, MangaSession.sourceId, ch.url)
        if (local != null) {
            localFiles = local
            markRead(ch.url)
            recycler.adapter = PageAdapter()
            updatePageLabel(0)
            restorePosition()
            return
        }
        thread {
            val source = src ?: try { MihonBridge.sourceById(this, MangaSession.sourceId) } catch (t: Throwable) { null }
            src = source
            val list = try {
                if (source == null) emptyList() else MihonBridge.pages(source, ch.url, ch.name)
            } catch (t: Throwable) {
                com.lagradost.shiro.utils.mvvm.logError(t)
                emptyList()
            }
            runOnUiThread {
                progress.visibility = View.GONE
                if (list.isEmpty()) {
                    message.text = "This chapter has no pages available.\nIt may be removed, locked, or require a login on the source's website."
                    message.visibility = View.VISIBLE
                    return@runOnUiThread
                }
                pages = list
                markRead(ch.url)
                recycler.adapter = PageAdapter()
                updatePageLabel(0)
                restorePosition()
                maybeAutoDownloadNext()
            }
        }
    }

    /** When "Auto-download next chapter" is on, quietly downloads the next unread chapter in the background. */
    private fun maybeAutoDownloadNext() {
        if (!prefs.getBoolean("manga_auto_download", false)) return
        // Chapters are newest-first; the next one in reading order is the previous list index.
        val next = MangaSession.chapters.getOrNull(index - 1) ?: return
        val sid = MangaSession.sourceId
        if (com.lagradost.shiro.utils.manga.ChapterDownloads.isDownloaded(this, sid, next.url)) return
        thread {
            val s = try { MihonBridge.sourceById(this, sid) } catch (t: Throwable) { null } ?: return@thread
            try {
                com.lagradost.shiro.utils.manga.ChapterDownloads.download(this, s, sid, MangaSession.mangaTitle, next)
            } catch (t: Throwable) {
                com.lagradost.shiro.utils.mvvm.logError(t)
            }
        }
    }

    private fun markRead(url: String) {
        val prefs = getSharedPreferences("manga_read", MODE_PRIVATE)
        prefs.edit().putStringSet("urls", prefs.getStringSet("urls", emptySet())!!.toMutableSet().apply { add(url) }).apply()
    }

    private fun go(delta: Int) {
        val next = index + delta
        if (next !in MangaSession.chapters.indices) {
            Toast.makeText(this, if (delta > 0) "No newer chapter" else "No earlier chapter", Toast.LENGTH_SHORT).show()
            return
        }
        savePosition()
        // Sources list newest first, so "next" in reading order is the previous list entry
        index = next
        loadChapter()
    }

    // ---- pages ----

    private inner class PageAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private val TYPE_PAGE = 0
        private val TYPE_END = 1

        private val count get() = localFiles?.size ?: pages.size

        override fun getItemCount() = count + 1
        override fun getItemViewType(position: Int) = if (position == count) TYPE_END else TYPE_PAGE

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val height = if (paged) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT
            if (viewType == TYPE_END) {
                val box = LinearLayout(this@MangaReaderActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                    setPadding(dp(12), dp(24), dp(12), dp(48))
                    layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height)
                    addView(Button(this@MangaReaderActivity).apply { text = "Previous chapter"; setOnClickListener { go(+1) } })
                    addView(Button(this@MangaReaderActivity).apply { text = "Next chapter"; setOnClickListener { go(-1) } })
                }
                return object : RecyclerView.ViewHolder(box) {}
            }
            val iv = ImageView(this@MangaReaderActivity).apply {
                adjustViewBounds = !paged
                scaleType = ImageView.ScaleType.FIT_CENTER
                if (!paged) minimumHeight = dp(300)
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height)
            }
            return object : RecyclerView.ViewHolder(iv) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (position == count) return
            val iv = holder.itemView as ImageView
            val local = localFiles?.getOrNull(position)
            val page = if (local == null) pages.getOrNull(position) else null
            if (local == null && page == null) return
            val key = "${MangaSession.sourceId}:${MangaSession.chapters.getOrNull(index)?.url}:$position"
            iv.tag = key
            iv.setImageDrawable(null)
            MangaSession.getBitmap(key)?.let { iv.setImageBitmap(it); return }
            MangaSession.run {
                val bytes = if (local != null) try { local.readBytes() } catch (t: Throwable) { null }
                    else MihonBridge.imageBytes(src ?: return@run, page ?: return@run)
                val bmp = bytes?.let { MangaSession.decode(it, screenWidth) }
                if (bmp != null) {
                    MangaSession.putBitmap(key, bmp)
                    iv.post {
                        if (iv.tag == key) {
                            // very tall webtoon strips exceed the GPU texture limit; draw those in software
                            if (!paged && bmp.height > 4096) iv.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                            else iv.setLayerType(View.LAYER_TYPE_NONE, null)
                            iv.setImageBitmap(bmp)
                        }
                    }
                } else {
                    iv.post { if (iv.tag == key) iv.setBackgroundColor(0x33FF0000) }
                }
            }
        }
    }
}
