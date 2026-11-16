package com.lagradost.shiro.ui.settings

import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.jaredrummler.cyanea.app.CyaneaAppCompatActivity

/**
 * Settings -> About -> Open-source licenses. Shows Nekomo's own license (with the full GPL-3.0 text), the data services
 * and their attribution requirements, bundled components, and every library in the app with its license (searchable).
 */
class LicensesActivity : CyaneaAppCompatActivity() {
    private data class Lib(val name: String, val license: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = "Open-source licenses"
        val b = PageBuilder(this)
        val r = b.root

        r.addView(b.text("Open-source licenses", 24f, bold = true))
        r.addView(b.text("Nekomo is free software built on the work of many projects. Thank you to everyone behind them.", 13f, dim = true).apply { setPadding(0, b.dp(6), 0, b.dp(4)) })

        r.addView(b.heading("Nekomo"))
        r.addView(b.card(
            b.text("GNU General Public License v3.0 or later", 16f, bold = true),
            b.text("Nekomo is distributed under the GPL-3.0-or-later because it includes GPL-3.0 components such as the CloudStream API and the NewPipe extractor. Code that originates from the Shiro app is MIT licensed.", 13f, dim = true),
            b.row("Read the full GPL-3.0 text") { showAsset("COPYING.txt", "GNU GPL v3.0") },
            b.row("Source code", "github.com/Nekomo-App/neko-source") { b.open("https://github.com/Nekomo-App/neko-source") },
        ))

        r.addView(b.heading("Data services"))
        r.addView(b.card(
            b.text("Metadata comes from public services. Nekomo is not affiliated with or endorsed by any of them.", 13f, dim = true),
            b.row("AniList", "Anime catalogue, artwork and trailers. Used under the AniList API terms.") { b.open("https://anilist.co/terms") },
            b.row("Kitsu", "Backup metadata and episode lists.") { b.open("https://kitsu.app/terms") },
            b.row("MyAnimeList via Jikan", "Backup metadata through the unofficial Jikan API.") { b.open("https://docs.api.jikan.moe/") },
            b.row("TVMaze", "TV show data, licensed CC BY-SA 4.0.") { b.open("https://www.tvmaze.com/api") },
            b.row("TMDB", "This product uses the TMDB API but is not endorsed or certified by TMDB.") { b.open("https://www.themoviedb.org/api-terms-of-use") },
            b.row("YouTube trailers", "Resolved with the NewPipe extractor and played in the built-in player.") { b.open("https://www.youtube.com/t/terms") },
        ))

        r.addView(b.heading("Bundled components"))
        r.addView(b.card(
            b.text("CloudStream API (GPL-3.0)", 15f, bold = true),
            b.text("Lets CloudStream plugins you install run inside Nekomo.", 12f, dim = true),
            b.text("Aniyomi / Mihon extension API (Apache-2.0)", 15f, bold = true).apply { setPadding(0, b.dp(10), 0, 0) },
            b.text("Lets APK extensions you install run inside Nekomo.", 12f, dim = true),
            b.text("NewPipe extractor (GPL-3.0-or-later)", 15f, bold = true).apply { setPadding(0, b.dp(10), 0, 0) },
            b.text("Trailer playback.", 12f, dim = true),
            b.text("Rhino JavaScript engine (MPL-2.0)", 15f, bold = true).apply { setPadding(0, b.dp(10), 0, 0) },
            b.text("Runs Zangetsu scripts you install.", 12f, dim = true),
            b.text("Shiro (MIT)", 15f, bold = true).apply { setPadding(0, b.dp(10), 0, 0) },
            b.text("The original app Nekomo is based on.", 12f, dim = true),
        ))

        r.addView(b.heading("Extensions and sources you install"))
        r.addView(b.card(b.text("Sources, repositories and extensions are installed by you and keep their own licenses and terms. Nekomo bundles none of them.", 13f, dim = true)))

        val libs = readLibs()
        r.addView(b.heading("Libraries (${libs.size})"))
        val search = EditText(this).apply {
            hint = "Search libraries or licenses"
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine()
            setTextColor(b.fg)
            setHintTextColor(b.fg and 0x00FFFFFF or 0x66000000)
        }
        r.addView(search)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val count = b.text("", 12f, dim = true).apply { setPadding(0, b.dp(8), 0, b.dp(4)) }
        r.addView(count)
        r.addView(list)

        fun fill(q: String) {
            list.removeAllViews()
            val shown = libs.filter { q.isBlank() || it.name.contains(q, true) || it.license.contains(q, true) }
            count.text = "${shown.size} of ${libs.size}"
            shown.forEach { l ->
                list.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(b.dp(4), b.dp(6), b.dp(4), b.dp(6))
                    addView(b.text(l.name, 13f))
                    addView(b.text(l.license, 11f, dim = true))
                })
            }
            if (shown.isEmpty()) list.addView(b.text("No libraries match.", 13f, dim = true))
        }
        fill("")
        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = fill(s?.toString().orEmpty())
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        setContentView(b.finish())
    }

    private fun readLibs(): List<Lib> = try {
        assets.open("licenses.txt").bufferedReader().readLines().filter { it.isNotBlank() }.chunked(2)
            .filter { it.size == 2 }.map { Lib(it[0].trim(), it[1].trim()) }
    } catch (e: Exception) {
        emptyList()
    }

    private fun showAsset(file: String, title: String) {
        val text = try {
            assets.open(file).bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            "The license text could not be loaded. Read it at https://www.gnu.org/licenses/gpl-3.0.txt"
        }
        val tv = TextView(this).apply {
            this.text = text
            textSize = 11f
            setTextIsSelectable(true)
            setPadding(48, 24, 48, 24)
        }
        AlertDialog.Builder(this).setTitle(title).setView(ScrollView(this).apply { addView(tv) }).setPositiveButton("OK", null).show()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
