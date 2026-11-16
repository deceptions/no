package com.lagradost.shiro.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.jaredrummler.cyanea.app.CyaneaAppCompatActivity
import com.lagradost.shiro.utils.home.HomeLayout
import com.lagradost.shiro.utils.home.HomeRegistry
import com.lagradost.shiro.utils.home.SourceKind

/**
 * Settings -> Home page. Choose which sources feed the home page, switch individual sections on or off and reorder
 * them (inspired by CloudStream's home page picker). Official services, metadata providers and your own sources are
 * always labelled so it is clear where each row comes from.
 */
class HomeSettingsActivity : CyaneaAppCompatActivity() {
    private lateinit var root: LinearLayout

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Home page"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(24)) }
        setContentView(ScrollView(this).apply { addView(root) })
        render()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun heading(text: String) = TextView(this).apply {
        this.text = text
        textSize = 20f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, dp(20), 0, dp(4))
    }

    private fun body(text: String, size: Float = 13f, alpha: Float = 0.75f) = TextView(this).apply {
        this.text = text
        textSize = size
        this.alpha = alpha
    }

    private fun render() {
        root.removeAllViews()
        root.addView(body("Pick where your home page comes from. AniList is on by default; everything else is optional."))

        root.addView(heading("Metadata services"))
        root.addView(body("If a service is down or rate limited, Nekomo switches to a backup automatically (AniList, then Kitsu and MyAnimeList)."))
        listOf(
            "anilist" to "AniList (main)", "kitsu" to "Kitsu (backup)", "jikan" to "MyAnimeList via Jikan (backup)",
            "tvmaze" to "TVMaze (TV shows)", "tmdb" to "TMDB (movies and TV, needs your key)"
        ).forEach { (id, name) ->
            root.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(4), 0, dp(4))
                addView(TextView(this@HomeSettingsActivity).apply { text = name; textSize = 14f }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(TextView(this@HomeSettingsActivity).apply {
                    text = com.lagradost.shiro.utils.meta.ApiHealth.status(id)
                    textSize = 12f
                    alpha = 0.75f
                })
            })
        }
        root.addView(Button(this).apply {
            text = "Test backups: pretend AniList is down for 5 min"
            isAllCaps = false
            setOnClickListener {
                com.lagradost.shiro.utils.meta.ApiHealth.simulateOutage("anilist", 5)
                com.lagradost.shiro.utils.home.HomeFeed.clear()
                render()
            }
        })
        root.addView(Button(this).apply {
            text = "Reset service status"
            isAllCaps = false
            setOnClickListener { com.lagradost.shiro.utils.meta.ApiHealth.reset(); render() }
        })

        root.addView(heading("Sources"))
        for (src in HomeRegistry.sources.filter { it.kind != SourceKind.LOCAL }) {
            val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, dp(8)) }
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(TextView(this).apply { text = src.name; textSize = 16f }, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(Switch(this).apply {
                isChecked = HomeLayout.sourceEnabled(this@HomeSettingsActivity, src)
                setOnCheckedChangeListener { _, on ->
                    HomeLayout.setSourceEnabled(this@HomeSettingsActivity, src.id, on)
                    render()
                }
            })
            box.addView(row)
            box.addView(body("${src.kind.label} · ${src.kind.description}", 12f, 0.9f))
            box.addView(body(src.attribution, 12f))
            if (src.termsUrl.isNotBlank()) box.addView(body("Terms: ${src.termsUrl}", 12f).apply {
                setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(src.termsUrl))) }
            })
            root.addView(box)
        }

        root.addView(heading("Sections"))
        root.addView(body("Switch rows on or off and use the arrows to reorder them."))
        val entries = HomeLayout.entries(this)
        for (e in entries) {
            val srcOn = HomeLayout.sourceEnabled(this, e.source)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                alpha = if (srcOn) 1f else 0.45f
                setPadding(0, dp(4), 0, dp(4))
            }
            val label = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            label.addView(TextView(this).apply { text = e.def.title; textSize = 15f })
            label.addView(body(e.source.name + if (srcOn) "" else " (source is off)", 11f, 0.6f))
            row.addView(label, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(Button(this).apply {
                text = "▲"; minWidth = 0; minimumWidth = dp(44)
                setOnClickListener { HomeLayout.move(this@HomeSettingsActivity, e.def.id, -1); render() }
            })
            row.addView(Button(this).apply {
                text = "▼"; minWidth = 0; minimumWidth = dp(44)
                setOnClickListener { HomeLayout.move(this@HomeSettingsActivity, e.def.id, 1); render() }
            })
            row.addView(Switch(this).apply {
                isChecked = e.enabled
                setOnCheckedChangeListener { _, on -> HomeLayout.setSectionEnabled(this@HomeSettingsActivity, e.def.id, on) }
            })
            root.addView(row)
        }

        root.addView(Button(this).apply {
            text = "Reset to default home page"
            isAllCaps = false
            setOnClickListener {
                AlertDialog.Builder(this@HomeSettingsActivity)
                    .setMessage("Restore the default home page layout and sources?")
                    .setPositiveButton("Reset") { _, _ -> HomeLayout.reset(this@HomeSettingsActivity); render() }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        })
    }
}
