package com.lagradost.shiro.ui.welcome

import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.jaredrummler.cyanea.Cyanea
import com.lagradost.shiro.R
import com.lagradost.shiro.utils.AppUtils.openBrowser
import com.lagradost.shiro.utils.AniListApi.Companion.authenticateAniList
import com.lagradost.shiro.utils.MALApi.Companion.authenticateMAL
import com.lagradost.shiro.utils.auth.AuthManager
import com.lagradost.shiro.utils.auth.KitsuApi
import kotlin.concurrent.thread

/**
 * Full-screen, non-cancelable welcome/sign-in gate shown over MainActivity / TvActivity
 * until the user has a verified account and has accepted the community rules.
 *
 * It is a Dialog (own window) rather than a view overlay, so touch, D-pad focus and the
 * back key can not reach the app underneath. OAuth redirects still arrive at the
 * activity via onNewIntent/handleIntent, which stores the token + profile; this gate
 * simply polls AuthManager and advances when the profile has been verified.
 */
class WelcomeGate private constructor(private val activity: FragmentActivity) {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var dialog: Dialog
    private val root = LinearLayout(activity)
    private val ctx get() = activity
    private var step = Step.SIGN_IN
    private var statusView: TextView? = null
    private var hiddenForBrowser = false

    private enum class Step { SIGN_IN, DMCA, RULES, REPOS, SETUP }

    private val bg = Cyanea.instance.backgroundColor
    private val fg = if (Cyanea.instance.isDark) Color.WHITE else Color.BLACK
    private val card = if (Cyanea.instance.isDark) 0x22FFFFFF else 0x14000000

    companion object {
        fun attachIfNeeded(activity: FragmentActivity) {
            if (AuthManager.canEnter(activity)) return
            WelcomeGate(activity).show()
        }

        /** Re-usable rules dialog for Settings -> Community Rules. */
        fun rulesBody(activity: FragmentActivity): Pair<String, String> =
            activity.getString(R.string.rules_title) to activity.getString(R.string.rules_body)
    }

    private fun show() {
        dialog = Dialog(activity, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen).apply {
            setCancelable(false)
            setOnKeyListener { _, keyCode, event ->
                // Back must not reveal the app: leave it instead.
                if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                    activity.finish()
                    true
                } else false
            }
            window?.apply {
                setBackgroundDrawable(ColorDrawable(bg))
                setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            }
        }
        val scroll = ScrollView(activity).apply { isFillViewport = true }
        root.orientation = LinearLayout.VERTICAL
        root.gravity = Gravity.CENTER_HORIZONTAL
        root.setPadding(SocialLinks.dp(ctx, 24), SocialLinks.dp(ctx, 40), SocialLinks.dp(ctx, 24), SocialLinks.dp(ctx, 32))
        scroll.addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        dialog.setContentView(scroll)

        activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                handler.removeCallbacksAndMessages(null)
                if (dialog.isShowing) dialog.dismiss()
            }
        })

        // Returning users who already signed in / skipped only need the steps they have not seen yet
        if (AuthManager.isSignedIn(ctx) || AuthManager.hasSkippedSignIn(ctx)) {
            step = when {
                !AuthManager.hasSeenDmca(ctx) -> Step.DMCA
                !AuthManager.hasAcceptedRules(ctx) -> Step.RULES
                !AuthManager.hasAcceptedRepoWarning(ctx) -> Step.REPOS
                else -> Step.SETUP
            }
        }
        render()
        dialog.show()
        poll()
    }

    // ---- polling ----

    private fun poll() {
        handler.postDelayed({
            if (activity.isFinishing || activity.isDestroyed) return@postDelayed
            try {
                val signedIn = AuthManager.isSignedIn(ctx)
                if (signedIn && step == Step.SIGN_IN) {
                    restore()
                    advance(Step.SIGN_IN)
                } else if (!signedIn && hiddenForBrowser &&
                    activity.supportFragmentManager.findFragmentByTag("WEB_VIEW") == null
                ) {
                    // TV in-app browser was closed without finishing sign-in
                    restore()
                }
            } catch (e: Exception) {
                com.lagradost.shiro.utils.mvvm.logError(e)
            }
            poll()
        }, 600)
    }

    private fun restore() {
        if (hiddenForBrowser) {
            hiddenForBrowser = false
            dialog.show()
        }
    }

    // ---- UI ----

    private fun render() {
        root.removeAllViews()
        when (step) {
            Step.SIGN_IN -> renderSignIn()
            Step.DMCA -> renderDmca()
            Step.RULES -> renderRules()
            Step.REPOS -> renderRepos()
            Step.SETUP -> renderSetup()
        }
        root.post { root.findFocus() ?: root.getChildAt(0)?.requestFocus() }
    }

    private fun text(s: String, size: Float, bold: Boolean = false, alpha: Boolean = false) = TextView(ctx).apply {
        text = s
        textSize = size
        gravity = Gravity.CENTER
        setTextColor(if (alpha) (fg and 0x00FFFFFF or 0xB3000000.toInt()) else fg)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = SocialLinks.dp(ctx, 8) }
    }

    private fun button(label: String, color: Int, onClick: () -> Unit) = Button(ctx).apply {
        text = label
        isAllCaps = false
        textSize = 16f
        setTextColor(Color.WHITE)
        background = SocialLinks.cardBackground(ctx, color, 24)
        isFocusable = true
        stateListAnimator = null
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, SocialLinks.dp(ctx, 52)
        ).apply { bottomMargin = SocialLinks.dp(ctx, 10) }
        setOnClickListener { onClick() }
    }

    private fun linkText(label: String, onClick: () -> Unit) = TextView(ctx).apply {
        text = label
        textSize = 14f
        setTextColor(0xFF4FC3F7.toInt())
        paint.isUnderlineText = true
        setPadding(SocialLinks.dp(ctx, 10), SocialLinks.dp(ctx, 10), SocialLinks.dp(ctx, 10), SocialLinks.dp(ctx, 10))
        isFocusable = true
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun renderSignIn() {
        root.addView(ImageView(ctx).apply {
            setImageResource(if (Cyanea.instance.isDark) R.drawable.nekomo_logo_white else R.drawable.nekomo_logo)
            adjustViewBounds = true
            layoutParams = LinearLayout.LayoutParams(SocialLinks.dp(ctx, 96), SocialLinks.dp(ctx, 96))
                .apply { bottomMargin = SocialLinks.dp(ctx, 12) }
        })
        root.addView(text(ctx.getString(R.string.app_name), 28f, bold = true))
        root.addView(text(ctx.getString(R.string.welcome_tagline), 14f, alpha = true).apply {
            (layoutParams as LinearLayout.LayoutParams).bottomMargin = SocialLinks.dp(ctx, 24)
        })

        root.addView(text(
            "Nekomo does not host, include or provide any videos, manga or other content. After setup you choose and " +
                    "install the sources and extensions you want yourself, and you are responsible for what you use them for.",
            12f, alpha = true
        ).apply { setPadding(0, 0, 0, SocialLinks.dp(ctx, 16)) })
        root.addView(button("Sign in with AniList", 0xFF02A9FF.toInt()) { startBrowserSignIn { ctx.authenticateAniList() } })
        root.addView(button("Sign in with MyAnimeList", 0xFF2E51A2.toInt()) { startBrowserSignIn { ctx.authenticateMAL() } })
        root.addView(button("Continue with Kitsu", 0xFFF75239.toInt()) { showKitsuDialog() })
        statusView = text("", 13f, alpha = true).also { root.addView(it) }

        root.addView(button("Skip for now", 0xFF616161.toInt()) {
            AuthManager.setSignInSkipped(ctx)
            advance(Step.SIGN_IN)
        })
        root.addView(linkText("Create an account") { showCreateAccount() })

        val legal = LinearLayout(ctx).apply { gravity = Gravity.CENTER }
        legal.addView(linkText("Terms of Service") { SocialLinks.openUrl(ctx, ctx.getString(R.string.link_terms)) })
        legal.addView(linkText("Privacy Policy") { SocialLinks.openUrl(ctx, ctx.getString(R.string.link_privacy)) })
        root.addView(legal)

        root.addView(text("Signing in only stores your login token on this device, in the app's private storage. Nekomo never sends it anywhere except to the service you sign in to.", 12f, alpha = true).apply {
            setPadding(0, SocialLinks.dp(ctx, 8), 0, 0)
        })
        root.addView(text("Please make sure to follow the community rules. You will be asked to accept them after signing in.", 12f, alpha = true).apply {
            setPadding(0, SocialLinks.dp(ctx, 12), 0, SocialLinks.dp(ctx, 12))
        })

        root.addView(text("Follow Us", 18f, bold = true).apply {
            setPadding(0, SocialLinks.dp(ctx, 12), 0, SocialLinks.dp(ctx, 4))
        })
        val social = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        SocialLinks.populate(social, fg, card)
        root.addView(social, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    /** Moves to the next step that still has to be shown, or closes the gate when everything is done. */
    private fun advance(from: Step) {
        // Fixed order: Sign in, DMCA notice, Rules, Third-party repository warning. Steps already acknowledged are skipped.
        val next = when {
            from == Step.SIGN_IN && !AuthManager.hasSeenDmca(ctx) -> Step.DMCA
            from.ordinal <= Step.DMCA.ordinal && !AuthManager.hasAcceptedRules(ctx) -> Step.RULES
            from.ordinal <= Step.RULES.ordinal && !AuthManager.hasAcceptedRepoWarning(ctx) -> Step.REPOS
            from.ordinal <= Step.REPOS.ordinal && !AuthManager.hasCompletedSetup(ctx) -> Step.SETUP
            else -> null
        }
        if (next == null) {
            handler.removeCallbacksAndMessages(null)
            dialog.dismiss()
        } else {
            step = next
            render()
        }
    }

    private fun renderDmca() {
        val pad = SocialLinks.dp(ctx, 12)
        root.addView(text("Copyright & DMCA notice", 24f, bold = true).apply {
            setPadding(0, SocialLinks.dp(ctx, 24), 0, pad)
        })
        root.addView(text("Please read before continuing", 16f, bold = true).apply { setPadding(0, 0, 0, pad) })
        val points = listOf(
            "Nekomo does not host, store, upload or distribute any videos, manga or other media.",
            "Anime information and artwork come from public metadata providers such as AniList. Nekomo is not affiliated with or endorsed by them or by any streaming service.",
            "Nekomo ships with no sources installed. An optional official repository of free and legal sources exists, but nothing is installed until you add it and confirm. Anything you add is provided by third parties that Nekomo does not control, and you are responsible for how you use it.",
            "You are responsible for following the copyright laws and the terms of service that apply to you. Please support official releases where you can.",
            "Rights holders: Nekomo cannot remove content it does not host. Please contact the website or service hosting the material. The full DMCA policy explains how to reach us."
        )
        points.forEach { p -> root.addView(text("•  $p", 15f, alpha = true).apply { setPadding(0, 0, 0, pad) }) }
        root.addView(linkText("Read the full DMCA policy") { SocialLinks.openUrl(ctx, ctx.getString(R.string.link_dmca)) })
        root.addView(button("I understand", 0xFF43A047.toInt()) {
            AuthManager.setDmcaSeen(ctx)
            advance(Step.DMCA)
        })
    }

    private fun renderRepos() {
        val pad = SocialLinks.dp(ctx, 12)
        root.addView(text("Step 4 of 5", 13f, alpha = true).apply { setPadding(0, SocialLinks.dp(ctx, 16), 0, 0) })
        root.addView(text("Third-party repositories", 24f, bold = true).apply { setPadding(0, SocialLinks.dp(ctx, 8), 0, pad) })
        root.addView(text("Read this before adding anything", 16f, bold = true).apply { setPadding(0, 0, 0, pad) })
        listOf(
            "Sources, repositories and extensions are written and hosted by independent third parties. Nekomo does not create, review, endorse or control them.",
            "They run inside this app and can read data the app can access and make network requests. Only add repositories from people you trust.",
            "Nothing is installed automatically. Every repository and source asks you to confirm first, and APK extensions are always installed by you.",
            "You are responsible for the content you access and for following the laws and terms that apply to you.",
        ).forEach { p -> root.addView(text("•  $p", 15f, alpha = true).apply { setPadding(0, 0, 0, pad) }) }
        root.addView(linkText("Sources guide") { SocialLinks.openUrl(ctx, "https://nekomoapp.netlify.app/sources") })
        root.addView(button("I understand the risks", 0xFF43A047.toInt()) {
            AuthManager.setRepoWarningAccepted(ctx)
            advance(Step.REPOS)
        })
    }

    /** Last step: CloudStream-style quick setup. Picks the basics once; everything stays editable in Settings. */
    private fun renderSetup() {
        val pad = SocialLinks.dp(ctx, 12)
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(ctx)
        root.addView(text("Step 5 of 5", 13f, alpha = true).apply { setPadding(0, SocialLinks.dp(ctx, 16), 0, 0) })
        root.addView(text("Quick setup", 24f, bold = true).apply { setPadding(0, SocialLinks.dp(ctx, 8), 0, pad) })
        root.addView(text("Pick a few basics. Everything here can be changed later in Settings.", 15f, alpha = true).apply { setPadding(0, 0, 0, pad) })

        val accent = 0xFF7c3aed.toInt()
        fun radioGroup(title: String, options: List<String>, selected: Int, onPick: (Int) -> Unit) {
            root.addView(text(title, 16f, bold = true).apply { setPadding(0, pad, 0, SocialLinks.dp(ctx, 4)) })
            val buttons = options.map { label ->
                android.widget.RadioButton(ctx).apply { text = label; textSize = 14f }
            }
            buttons.forEachIndexed { i, b ->
                b.isChecked = i == selected
                b.setOnClickListener { buttons.forEachIndexed { j, other -> other.isChecked = j == i }; onPick(i) }
                root.addView(b)
            }
        }

        // Interface mode (classic keeps today's look; modern rounds and spaces out cards)
        var uiMode = prefs.getString("ui_mode", "classic") ?: "classic"
        radioGroup("Interface", listOf("Classic — the current Nekomo look", "Modern — rounded cards, more space, subtle motion"),
            if (uiMode == "modern") 1 else 0) { uiMode = if (it == 1) "modern" else "classic" }

        // Content: anime only or also movies/TV
        var nonAnime = prefs.getBoolean("non_anime_enabled", false)
        val moviesBox = android.widget.CheckBox(ctx).apply {
            text = "Also show movies and TV shows (off = anime only)"
            textSize = 14f
            isChecked = nonAnime
            setOnCheckedChangeListener { _, on -> nonAnime = on }
        }
        root.addView(text("Content", 16f, bold = true).apply { setPadding(0, pad, 0, SocialLinks.dp(ctx, 4)) })
        root.addView(moviesBox)

        // DNS over HTTPS provider
        val dohOptions = listOf("Off (system DNS)", "Cloudflare", "Google", "AdGuard", "Quad9")
        val dohKeys = listOf("disabled", "Cloudflare", "Google", "AdGuard", "Quad9")
        var doh = when (prefs.getString("dns_provider", "")) {
            "Cloudflare" -> 1; "Google" -> 2; "AdGuard" -> 3; "Quad9" -> 4; else -> 0
        }
        radioGroup("DNS over HTTPS (privacy)", dohOptions, doh) { doh = it }

        root.addView(text("Reminder: Nekomo does not host or provide any media. You accepted the DMCA notice, community rules and third-party repository warning earlier in this setup — you are responsible for what you access.", 12f, alpha = true).apply { setPadding(0, pad, 0, pad) })
        root.addView(button("Finish setup", accent) {
            val current = prefs.getString("ui_mode", "classic") ?: "classic"
            prefs.edit().apply {
                putString("ui_mode", uiMode)
                if (uiMode != current) {
                    // Seed the matching card defaults, same as the Appearance screen does.
                    if (uiMode == "modern") {
                        if (prefs.getInt("card_corner_dp", 6) == 6) putInt("card_corner_dp", 14)
                        if (prefs.getInt("card_spacing_dp", 5) == 5) putInt("card_spacing_dp", 8)
                    } else {
                        if (prefs.getInt("card_corner_dp", 14) == 14) putInt("card_corner_dp", 6)
                        if (prefs.getInt("card_spacing_dp", 8) == 8) putInt("card_spacing_dp", 5)
                    }
                }
                putBoolean("non_anime_enabled", nonAnime)
                putString("dns_provider", if (doh == 0) ctx.getString(R.string.dns_provider_disabled) else dohKeys[doh])
                apply()
            }
            AuthManager.setSetupCompleted(ctx)
            advance(Step.SETUP)
        })
    }

    private fun renderRules() {
        val (title, body) = rulesBody(activity)
        root.addView(text("Community Rules", 24f, bold = true).apply {
            setPadding(0, SocialLinks.dp(ctx, 24), 0, SocialLinks.dp(ctx, 12))
        })
        root.addView(text(title, 18f, bold = true))
        root.addView(text(body, 15f, alpha = true).apply {
            setPadding(0, 0, 0, SocialLinks.dp(ctx, 24))
        })
        root.addView(button(ctx.getString(R.string.rules_button), 0xFF43A047.toInt()) {
            AuthManager.setRulesAccepted(ctx)
            advance(Step.RULES)
        })
    }

    /**
     * AniList/MAL sign-in goes through the browser and returns via a shiroapp:// redirect.
     * On TV the login page opens as an in-app WebView fragment that sits under this
     * dialog's window, so the dialog is hidden while it is open (see poll()).
     */
    private fun startBrowserSignIn(launch: () -> Unit) {
        statusView?.text = "Waiting for sign-in to finish..."
        if (com.lagradost.shiro.ui.tv.TvActivity.tvActivity != null) {
            hiddenForBrowser = true
            dialog.hide()
        }
        launch()
    }

    private fun showCreateAccount() {
        val names = arrayOf("AniList", "MyAnimeList", "Kitsu")
        val urls = intArrayOf(R.string.link_signup_anilist, R.string.link_signup_mal, R.string.link_signup_kitsu)
        AlertDialog.Builder(ctx)
            .setTitle("Create an account")
            .setItems(names) { _, which -> SocialLinks.openUrl(ctx, ctx.getString(urls[which])) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showKitsuDialog() {
        val pad = SocialLinks.dp(ctx, 20)
        val form = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        val user = EditText(ctx).apply {
            hint = "Kitsu email or username"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            maxLines = 1
        }
        val pass = EditText(ctx).apply {
            hint = "Password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            maxLines = 1
        }
        form.addView(user)
        form.addView(pass)
        AlertDialog.Builder(ctx)
            .setTitle("Sign in with Kitsu")
            .setView(form)
            .setPositiveButton("Sign in") { _, _ ->
                statusView?.text = "Signing in..."
                thread {
                    val res = KitsuApi.login(ctx.applicationContext, user.text.toString(), pass.text.toString())
                    activity.runOnUiThread { statusView?.text = if (res.success) "" else res.message }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
