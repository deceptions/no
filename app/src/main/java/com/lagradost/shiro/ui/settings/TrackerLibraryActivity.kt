package com.lagradost.shiro.ui.settings

import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.jaredrummler.cyanea.app.CyaneaAppCompatActivity
import com.lagradost.shiro.ui.GlideApp
import com.lagradost.shiro.ui.MainActivity
import com.lagradost.shiro.utils.auth.KitsuApi
import com.lagradost.shiro.utils.integrations.SimklApi
import kotlin.concurrent.thread

/** Read-only view of the user's Kitsu / SIMKL anime library, grouped by status. */
class TrackerLibraryActivity : CyaneaAppCompatActivity() {
    companion object {
        const val EXTRA_SERVICE = "service"
        const val KITSU = "kitsu"
        const val SIMKL = "simkl"
    }

    private data class Row(val title: String, val poster: String?, val status: String, val progress: String, val malId: Int?)

    private lateinit var list: LinearLayout
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        val service = intent.getStringExtra(EXTRA_SERVICE) ?: KITSU
        title = if (service == SIMKL) "SIMKL library" else "Kitsu library"

        val pad = (16 * resources.displayMetrics.density).toInt()
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        status = TextView(this).apply { text = "Loading…"; setPadding(0, pad, 0, pad) }
        list.addView(status)
        setContentView(ScrollView(this).apply { addView(list) })

        thread {
            val rows: List<Row>? = try {
                if (service == SIMKL) SimklApi.getLibrary(this)?.map {
                    Row(it.title, it.poster, it.status, "${it.watched}${it.total?.let { t -> " / $t" } ?: ""}", it.malId)
                } else KitsuApi.getLibrary(this)?.map {
                    Row(it.title, it.poster, it.status, "${it.progress}${it.total?.let { t -> " / $t" } ?: ""}", it.malId)
                }
            } catch (e: Exception) {
                null
            }
            runOnUiThread { show(rows) }
        }
    }

    private fun show(rows: List<Row>?) {
        if (isFinishing || isDestroyed) return
        list.removeAllViews()
        if (rows == null) return list.addView(TextView(this).apply { text = "Could not load the library. Check your connection and that the account is connected." })
        if (rows.isEmpty()) return list.addView(TextView(this).apply { text = "Nothing here yet." })

        val order = listOf("current", "watching", "planned", "plantowatch", "on_hold", "hold", "completed", "dropped")
        val names = mapOf(
            "current" to "Watching", "watching" to "Watching", "planned" to "Plan to watch",
            "plantowatch" to "Plan to watch", "on_hold" to "On hold", "hold" to "On hold",
            "completed" to "Completed", "dropped" to "Dropped"
        )
        val grouped = rows.groupBy { names[it.status] ?: it.status.ifEmpty { "Other" } }
        val sections = grouped.keys.sortedBy { n -> order.indexOfFirst { names[it] == n }.let { if (it < 0) 99 else it } }
        for (section in sections) {
            list.addView(TextView(this).apply {
                text = "$section (${grouped[section]!!.size})"
                textSize = 18f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, 24, 0, 8)
            })
            for (r in grouped[section]!!) list.addView(rowView(r))
        }
    }

    private fun rowView(r: Row): LinearLayout {
        val d = resources.displayMetrics.density
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (6 * d).toInt(), 0, (6 * d).toInt())
            isFocusable = true
            isClickable = true
            setBackgroundResource(android.R.drawable.list_selector_background)
            addView(ImageView(this@TrackerLibraryActivity).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = LinearLayout.LayoutParams((48 * d).toInt(), (68 * d).toInt())
                r.poster?.let { GlideApp.with(this@TrackerLibraryActivity).load(it).into(this) }
            })
            addView(LinearLayout(this@TrackerLibraryActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding((12 * d).toInt(), 0, 0, 0)
                addView(TextView(this@TrackerLibraryActivity).apply { text = r.title; textSize = 15f })
                addView(TextView(this@TrackerLibraryActivity).apply { text = "Episodes: ${r.progress}"; textSize = 12f; alpha = 0.7f })
            })
            setOnClickListener {
                if (r.malId == null) {
                    Toast.makeText(context, "No MyAnimeList id for this title, can't open it in the app", Toast.LENGTH_SHORT).show()
                } else {
                    startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("shiroapp://open/openmal/${r.malId}"), this@TrackerLibraryActivity, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    )
                    finish()
                }
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish(); return true
    }
}
