package com.lagradost.shiro.ui.settings

import DataStore.getKey
import DataStore.getKeys
import ANILIST_TOKEN_KEY
import MAL_TOKEN_KEY
import MAL_USER_KEY
import ANILIST_USER_KEY
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.jaredrummler.cyanea.app.CyaneaAppCompatActivity
import com.lagradost.shiro.utils.ANILIST_ACCOUNT_ID
import com.lagradost.shiro.utils.AniListApi.Companion.authenticateAniList
import com.lagradost.shiro.utils.MALApi.Companion.authenticateMAL
import com.lagradost.shiro.utils.MAL_ACCOUNT_ID
import com.lagradost.shiro.utils.auth.AuthManager
import com.lagradost.shiro.utils.auth.KitsuApi
import com.lagradost.shiro.utils.integrations.IntegrationPrefs
import com.lagradost.shiro.utils.integrations.SimklApi
import com.lagradost.shiro.utils.subs.SubtitleProviders
import com.lagradost.shiro.utils.skip.SkipTimes
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Settings -> Integrations: account connections (AniList, MAL, Kitsu, SIMKL), subtitle providers
 * (OpenSubtitles, SubDL) and Anime Skip. Keys are entered by the user and stored on the device only.
 */
class IntegrationsActivity : CyaneaAppCompatActivity() {
    private lateinit var root: LinearLayout
    private lateinit var prefs: IntegrationPrefs
    private val d get() = resources.displayMetrics.density

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = "Integrations"
        prefs = IntegrationPrefs(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (20 * d).toInt(); setPadding(p, p, p, p)
        }
        setContentView(ScrollView(this).apply { addView(root) })
        build()
    }

    override fun onResume() {
        super.onResume()
        if (::root.isInitialized) build()
    }

    // ---------- building blocks ----------

    private fun header(t: String, sub: String? = null) {
        root.addView(TextView(this).apply {
            text = t; textSize = 20f; setTypeface(typeface, Typeface.BOLD); setPadding(0, (24 * d).toInt(), 0, 4)
        })
        sub?.let { root.addView(TextView(this).apply { text = it; textSize = 13f; alpha = 0.75f; setPadding(0, 0, 0, (8 * d).toInt()) }) }
    }

    private fun line(t: String) = TextView(this).apply { text = t; textSize = 15f; setPadding(0, 10, 0, 10) }.also { root.addView(it) }

    private fun button(label: String, onClick: (Button) -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; setOnClickListener { onClick(this) }
    }.also { root.addView(it) }

    private fun field(label: String, hint: String, get: () -> String, set: (String) -> Unit, secret: Boolean = false) {
        root.addView(TextView(this).apply { text = label; textSize = 13f; setPadding(0, 12, 0, 0) })
        root.addView(EditText(this).apply {
            this.hint = hint
            setText(get())
            maxLines = 1
            inputType = InputType.TYPE_CLASS_TEXT or
                    (if (secret) InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) = set(s.toString())
            })
        })
    }

    private fun check(label: String, get: () -> Boolean, set: (Boolean) -> Unit) {
        root.addView(CheckBox(this).apply {
            text = label; isChecked = get(); setOnCheckedChangeListener { _, c -> set(c) }
        })
    }

    private fun toast(m: String) = runOnUiThread { Toast.makeText(this, m, Toast.LENGTH_LONG).show() }

    private fun link(url: String) = try {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (e: Exception) {
        toast("No app available to open $url")
    }

    // ---------- screen ----------

    private fun build() {
        root.removeAllViews()

        header("Accounts", "Progress you watch is synced to every connected account.")
        val hasAniList = getKey<String>(ANILIST_TOKEN_KEY, ANILIST_ACCOUNT_ID, null) != null
        val hasMal = getKey<String>(MAL_TOKEN_KEY, MAL_ACCOUNT_ID, null) != null
        line("AniList: " + if (hasAniList) "connected" else "not connected")
        if (!hasAniList) button("Connect AniList") { authenticateAniList() }
        line("MyAnimeList: " + if (hasMal) "connected" else "not connected")
        if (!hasMal) button("Connect MyAnimeList") { authenticateMAL() }
        if (hasAniList || hasMal) root.addView(TextView(this).apply { text = "Log out of AniList/MAL under Settings > Accounts."; textSize = 12f; alpha = 0.7f })

        val hasKitsu = AuthManager.isLoggedIntoKitsu(this)
        line("Kitsu: " + if (hasKitsu) "connected" else "not connected")
        if (hasKitsu) {
            button("Open Kitsu library") { startActivity(Intent(this, TrackerLibraryActivity::class.java).putExtra(TrackerLibraryActivity.EXTRA_SERVICE, TrackerLibraryActivity.KITSU)) }
            button("Disconnect Kitsu") { AuthManager.logoutKitsu(this); build() }
        } else button("Connect Kitsu") { kitsuLogin() }

        header("SIMKL", "Create an app at simkl.com/settings/developer (AUTH V2) and paste its client id here.")
        field("SIMKL client id", "client_id", { prefs.simklClientId }, { prefs.simklClientId = it })
        button("How to get a client id") { link("https://simkl.com/settings/developer/") }
        val simkl = SimklApi.isConnected(this)
        line("SIMKL: " + if (simkl) "connected" else "not connected")
        if (simkl) {
            button("Open SIMKL library") { startActivity(Intent(this, TrackerLibraryActivity::class.java).putExtra(TrackerLibraryActivity.EXTRA_SERVICE, TrackerLibraryActivity.SIMKL)) }
            button("Disconnect SIMKL") { SimklApi.logout(this); build() }
        } else button("Connect SIMKL") { simklConnect() }

        header("Subtitles", "Subtitles are searched by title and episode when an episode starts, and can be picked from the player's subtitle button.")
        field("Subtitle language (ISO code, e.g. en, es, pt, fr)", "en", { prefs.subtitleLanguage }, { prefs.subtitleLanguage = it })
        check("Automatically search and apply subtitles", { prefs.autoFetchSubtitles }, { prefs.autoFetchSubtitles = it })
        field("OpenSubtitles API key", "opensubtitles.com/consumers", { prefs.openSubsApiKey }, { prefs.openSubsApiKey = it })
        field("OpenSubtitles username", "username", { prefs.openSubsUser }, { prefs.openSubsUser = it })
        field("OpenSubtitles password", "password (needed for downloads)", { prefs.openSubsPassword }, { prefs.openSubsPassword = it }, secret = true)
        button("Test OpenSubtitles login") {
            thread { toast(SubtitleProviders.openSubtitlesLogin(prefs).second.let { if (it == "OK") "OpenSubtitles login works" else it }) }
        }
        field("SubDL API key", "subdl.com/panel/api", { prefs.subDlApiKey }, { prefs.subDlApiKey = it })
        button("Test SubDL") {
            thread {
                val n = if (prefs.subDlApiKey.isEmpty()) -1 else SubtitleProviders.searchSubDl(prefs, "Cowboy Bebop", 1, "en").size
                toast(if (n < 0) "Enter your SubDL API key" else if (n == 0) "SubDL returned no results (check the key)" else "SubDL works ($n results)")
            }
        }

        header("Anime Skip", "Intro/outro/recap timestamps. Without a client id the free AniSkip database is used instead.")
        field("Anime Skip client id", "anime-skip.com/account", { prefs.animeSkipClientId }, { prefs.animeSkipClientId = it })
        check("Skip automatically (no button press)", { prefs.autoSkip }, { prefs.autoSkip = it })
        check("Skip intros", { prefs.skipIntro }, { prefs.skipIntro = it })
        check("Skip outros / credits", { prefs.skipOutro }, { prefs.skipOutro = it })
        check("Skip recaps", { prefs.skipRecap }, { prefs.skipRecap = it })
        button("Test skip times (One Piece ep 1)") {
            thread {
                val segs = SkipTimes.fetch(this, 21, 21, 1, 0)
                toast(if (segs.isEmpty()) "No skip times returned (check the client id / connection)" else "Found ${segs.size} segment(s): " + segs.joinToString { it.type.name.lowercase() })
            }
        }
    }

    // ---------- Kitsu ----------

    private fun kitsuLogin() {
        val pad = (20 * d).toInt()
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad / 2, pad, 0) }
        val user = EditText(this).apply { hint = "Kitsu email or username"; maxLines = 1; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS }
        val pass = EditText(this).apply { hint = "Password"; maxLines = 1; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        form.addView(user); form.addView(pass)
        AlertDialog.Builder(this).setTitle("Connect Kitsu").setView(form)
            .setPositiveButton("Connect") { _, _ ->
                thread {
                    val r = KitsuApi.login(applicationContext, user.text.toString(), pass.text.toString())
                    toast(if (r.success) "Kitsu connected" else r.message)
                    runOnUiThread { build() }
                }
            }.setNegativeButton("Cancel", null).show()
    }

    // ---------- SIMKL device flow ----------

    private fun simklConnect() {
        if (prefs.simklClientId.isEmpty()) return toast("Enter your SIMKL client id first")
        toast("Contacting SIMKL…")
        thread {
            val code = SimklApi.startDeviceFlow(applicationContext)
            if (code == null) return@thread toast("Could not start SIMKL sign-in. Check the client id (it must be an AUTH V2 app).")
            val cancelled = AtomicBoolean(false)
            runOnUiThread {
                val dialog = AlertDialog.Builder(this)
                    .setTitle("Connect SIMKL")
                    .setMessage("On any device open simkl.com/pin and enter this code:\n\n${code.userCode}\n\nThis screen finishes by itself once you approve.")
                    .setPositiveButton("Open simkl.com", null)
                    .setNegativeButton("Cancel") { _, _ -> cancelled.set(true) }
                    .setOnCancelListener { cancelled.set(true) }
                    .show()
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { link(code.verificationUrl) }
                thread {
                    var interval = code.intervalSec.coerceAtLeast(2)
                    val end = System.currentTimeMillis() + code.expiresInSec * 1000L
                    var outcome = "SIMKL sign-in timed out"
                    while (!cancelled.get() && System.currentTimeMillis() < end) {
                        Thread.sleep(interval * 1000L)
                        if (cancelled.get()) break
                        when (SimklApi.pollDevice(applicationContext, code.deviceCode)) {
                            SimklApi.Poll.APPROVED -> { outcome = "SIMKL connected"; break }
                            SimklApi.Poll.SLOW_DOWN -> interval += 5
                            SimklApi.Poll.EXPIRED -> { outcome = "The code expired, try again"; break }
                            SimklApi.Poll.FAILED -> { outcome = "SIMKL sign-in failed"; break }
                            SimklApi.Poll.PENDING -> {}
                        }
                    }
                    runOnUiThread {
                        if (dialog.isShowing) dialog.dismiss()
                        if (!cancelled.get()) { toast(outcome); build() }
                    }
                }
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish(); return true
    }
}
