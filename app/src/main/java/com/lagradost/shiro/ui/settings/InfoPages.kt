package com.lagradost.shiro.ui.settings

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.jaredrummler.cyanea.Cyanea
import com.jaredrummler.cyanea.app.CyaneaAppCompatActivity
import com.lagradost.shiro.BuildConfig
import com.lagradost.shiro.R
import com.lagradost.shiro.ui.welcome.SocialLinks

private const val SOURCE_URL = "https://github.com/Nekomo-App/neko-source"
private const val GUIDE_URL = "https://nekomoapp.netlify.app/sources"

/** Shared look for the About / DMCA / Rules pages so they feel like one family. */
internal class PageBuilder(val activity: CyaneaAppCompatActivity) {
    val ctx: Context = activity
    val fg = if (Cyanea.instance.isDark) Color.WHITE else Color.BLACK
    val cardColor = if (Cyanea.instance.isDark) 0x1FFFFFFF else 0x12000000
    val accent = Cyanea.instance.accent
    val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private var shown = 0

    fun dp(v: Int) = SocialLinks.dp(ctx, v)

    init {
        root.setPadding(dp(20), dp(16), dp(20), dp(32))
    }

    fun text(s: CharSequence, size: Float, bold: Boolean = false, dim: Boolean = false, center: Boolean = false) =
        TextView(ctx).apply {
            text = s
            textSize = size
            setTextColor(fg)
            alpha = if (dim) 0.75f else 1f
            if (bold) setTypeface(typeface, Typeface.BOLD)
            if (center) gravity = Gravity.CENTER
        }

    fun heading(s: String) = text(s.uppercase(), 12f, bold = true).apply {
        setTextColor(accent)
        letterSpacing = 0.08f
        setPadding(dp(4), dp(24), 0, dp(8))
        reveal(this)
    }

    fun card(vararg children: View): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = GradientDrawable().apply { setColor(cardColor); cornerRadius = com.lagradost.shiro.utils.UiStyle.cardRadiusPx(ctx) }
        children.forEach { addView(it) }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }
        reveal(this)
    }

    /** A tappable row inside a card: title + optional subtitle. */
    fun row(title: String, sub: String? = null, onClick: () -> Unit): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(10), 0, dp(10))
        isClickable = true
        isFocusable = true
        setBackgroundResource(android.R.drawable.list_selector_background)
        addView(text(title, 16f))
        if (sub != null) addView(text(sub, 12f, dim = true))
        setOnClickListener { onClick() }
    }

    fun primaryButton(label: String, onClick: () -> Unit) = Button(ctx).apply {
        text = label
        isAllCaps = false
        setTextColor(Color.WHITE)
        textSize = 15f
        background = GradientDrawable().apply { setColor(accent); cornerRadius = com.lagradost.shiro.utils.UiStyle.buttonRadiusPx(ctx) }
        layoutParams = LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(8); bottomMargin = dp(8) }
        setOnClickListener { onClick() }
        reveal(this)
    }

    fun open(url: String) = SocialLinks.openUrl(ctx, url)

    /** Gentle staggered fade-in so the page does not pop in all at once. */
    fun reveal(v: View) {
        if (com.lagradost.shiro.utils.Motion.reduced(ctx)) return
        v.alpha = 0f
        v.translationY = dp(12).toFloat()
        v.animate().alpha(1f).translationY(0f).setStartDelay((shown++ * 45L).coerceAtMost(500)).setDuration(280).start()
    }

    fun finish(): ScrollView = ScrollView(ctx).apply {
        setBackgroundColor(Cyanea.instance.backgroundColor)
        addView(root, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }
}

object Licenses {
    fun show(ctx: Context) {
        ctx.startActivity(Intent(ctx, LicensesActivity::class.java))
    }
}

// ---------------------------------------------------------------------------------------------------------------

/** Settings -> About: a friendly overview of the app, its community, legal pages and credits. */
class AboutActivity : CyaneaAppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = "About"
        val b = PageBuilder(this)
        val channel = when (BuildConfig.BUILD_TYPE) { "debug" -> "Alpha"; "beta" -> "Beta"; else -> "Stable" }
        val device = if (BuildConfig.FLAVOR.contains("tv", ignoreCase = true)) "Android TV" else "Phone"

        // Header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, b.dp(8), 0, b.dp(8))
        }
        header.addView(ImageView(this).apply {
            setImageResource(R.drawable.nekomo_logo)
            layoutParams = LinearLayout.LayoutParams(b.dp(96), b.dp(96))
        })
        header.addView(b.text("Nekomo", 30f, bold = true, center = true).apply { setPadding(0, b.dp(12), 0, 0) })
        header.addView(b.text("Version ${BuildConfig.VERSION_NAME} · $channel · $device", 13f, dim = true, center = true))
        header.addView(b.text("Track your list and watch or read from the sources you add yourself.", 15f, dim = true, center = true).apply {
            setPadding(b.dp(8), b.dp(10), b.dp(8), 0)
        })
        b.root.addView(header)
        b.reveal(header)

        // Welcome
        b.root.addView(b.heading("Welcome"))
        b.root.addView(b.card(b.text(
            "Hi, and thanks for using Nekomo! It is a free, community-built anime and manga app for Android and Android TV. " +
                    "Discover what is trending, keep track of what you watch, and read or watch through sources you choose and add " +
                    "yourself. Nekomo itself hosts no content, and your list and history stay on your device unless you sign in.",
            14f
        )))
        b.root.addView(b.primaryButton("Visit our website") { b.open(getString(R.string.link_website)) })

        // Community
        b.root.addView(b.heading("Community"))
        val social = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        SocialLinks.populate(social, b.fg, b.cardColor)
        b.root.addView(social)

        // Help
        b.root.addView(b.heading("Help"))
        b.root.addView(b.card(
            b.row("How to add sources", "A step-by-step guide to repositories and extensions") { b.open(GUIDE_URL) },
            b.row("Documentation", "Guides and FAQ (Nekomo-Docs)") { b.open("https://github.com/Nekomo-App/Nekomo-Docs") },
            b.row("Extensions and compatibility", "Supported formats and tested sources") { b.open("https://github.com/Nekomo-App/Nekomo-Extensions") },
            b.row("NEX companion app", "Manage repositories and extensions from a separate app") { b.open("https://github.com/Nekomo-App/NEX") },
            b.row("Screenshots", "See every screen on the website") { b.open("https://nekomoapp.netlify.app/gallery") },
            b.row("Support", "Questions, bugs and feedback") { b.open(getString(R.string.link_support)) },
        ))

        // Legal
        b.root.addView(b.heading("Legal"))
        b.root.addView(b.card(
            b.row("Terms of Service", "The rules for using Nekomo") { b.open(getString(R.string.link_terms)) },
            b.row("Privacy Policy", "What stays on your device") { b.open(getString(R.string.link_privacy)) },
            b.row("DMCA notice", "Copyright information and how to contact us") { LegalPageActivity.open(this, LegalPageActivity.DMCA) },
            b.row("Community rules", "How we treat each other") { LegalPageActivity.open(this, LegalPageActivity.RULES) },
        ))

        // Open source
        b.root.addView(b.heading("Open source"))
        b.root.addView(b.card(
            b.row("Open-source licenses", "Nekomo is GPL-3.0; libraries and their licenses") { Licenses.show(this) },
            b.row("Source code", "github.com/Nekomo-App/neko-source") { b.open(SOURCE_URL) },
            b.row("Nekomo on GitHub", "Releases and issue tracker") { b.open("https://github.com/Nekomo-App/Nekomo") },
            b.row("Brand assets", "Logos and guidelines (Nekomo-Branding)") { b.open("https://github.com/Nekomo-App/Nekomo-Branding") },
        ))

        // Credits
        b.root.addView(b.heading("Credits"))
        b.root.addView(b.card(b.text(
            "Anime and manga information: AniList, with Kitsu and MyAnimeList (Jikan) as backups.\n" +
                    "TV shows: TVMaze (CC BY-SA). Movies and TV: TMDB when you add a key. This product uses the TMDB API but is not endorsed or certified by TMDB.\n" +
                    "Extension systems you can use: CloudStream, Aniyomi, Mihon and Zangetsu.\n" +
                    "Based on the open-source Shiro app. Thank you to every contributor and translator.",
            13f, dim = true
        )))

        b.root.addView(b.text(
            "Nekomo is not affiliated with AniList, MyAnimeList, CloudStream, Aniyomi, Mihon or any streaming service.",
            11f, dim = true, center = true
        ).apply { setPadding(b.dp(8), b.dp(20), b.dp(8), 0) })

        setContentView(b.finish())
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}

// ---------------------------------------------------------------------------------------------------------------

/** Settings -> DMCA notice and Rules: a short overview of each, with a link to the full text on the website. */
class LegalPageActivity : CyaneaAppCompatActivity() {
    companion object {
        const val DMCA = "dmca"
        const val RULES = "rules"

        fun open(context: Context, page: String) =
            context.startActivity(Intent(context, LegalPageActivity::class.java).putExtra("page", page))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        val page = intent.getStringExtra("page") ?: DMCA
        val b = PageBuilder(this)

        if (page == RULES) {
            title = "Rules"
            b.root.addView(b.text("Community rules", 26f, bold = true))
            b.root.addView(b.text(getString(R.string.rules_title), 15f, dim = true).apply { setPadding(0, b.dp(6), 0, b.dp(8)) })
            b.root.addView(b.heading("In short"))
            listOf(
                "Be respectful" to "Treat other people kindly. No harassment, hate or personal attacks.",
                "No spam" to "Do not flood chats or issue trackers, advertise, or post scams.",
                "Keep it appropriate" to "No adult or illegal content in the official Discord, Telegram or GitHub spaces.",
                "Do not share sources" to "Nekomo ships with no sources installed, and the community does not trade pirated content or unofficial repository links.",
            ).forEach { (t, d) ->
                b.root.addView(b.card(b.text(t, 16f, bold = true), b.text(d, 13f, dim = true)))
            }
            b.root.addView(b.primaryButton("Read the full rules") { b.open(getString(R.string.link_rules)) })
            b.root.addView(b.heading("Follow us"))
            val social = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            SocialLinks.populate(social, b.fg, b.cardColor)
            b.root.addView(social)
        } else {
            title = "DMCA notice"
            b.root.addView(b.text("Copyright & DMCA", 26f, bold = true))
            b.root.addView(b.text("Nekomo respects creators and rights holders.", 15f, dim = true).apply { setPadding(0, b.dp(6), 0, b.dp(8)) })
            b.root.addView(b.heading("In short"))
            listOf(
                "Nekomo hosts nothing" to "It does not store, upload or distribute videos, manga or other media.",
                "Metadata comes from public providers" to "Titles and artwork come from services such as AniList. Nekomo is not affiliated with them or with any streaming service.",
                "You choose your sources" to "Nekomo ships with no sources installed. An optional official repository of free and legal sources exists, but nothing is installed until you add it and confirm. Anything you add is provided by third parties that Nekomo does not control, and you are responsible for how you use it.",
                "Rights holders" to "Nekomo cannot remove content it does not host. Please contact the website or service that hosts the material. Our full policy explains how to reach us.",
            ).forEach { (t, d) ->
                b.root.addView(b.card(b.text(t, 16f, bold = true), b.text(d, 13f, dim = true)))
            }
            b.root.addView(b.primaryButton("Read the full DMCA policy") { b.open(getString(R.string.link_dmca)) })
            b.root.addView(b.card(
                b.row("Terms of Service") { b.open(getString(R.string.link_terms)) },
                b.row("Contact support") { b.open(getString(R.string.link_support)) },
            ))
        }
        setContentView(b.finish())
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
