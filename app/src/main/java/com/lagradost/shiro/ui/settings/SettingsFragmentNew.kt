package com.lagradost.shiro.ui.settings
import com.lagradost.shiro.utils.fv

import ANILIST_USER_KEY
import DataStore.getKey
import DataStore.getKeys
import MAL_TOKEN_KEY
import MAL_USER_KEY
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.View.*
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.activity.result.ActivityResultLauncher
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.preference.PreferenceManager
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.jaredrummler.cyanea.Cyanea
import com.lagradost.shiro.R
import com.lagradost.shiro.ui.GlideApp
import com.lagradost.shiro.ui.MainActivity
import com.lagradost.shiro.ui.MainActivity.Companion.statusHeight
import com.lagradost.shiro.ui.WebViewFragment.Companion.onWebViewNavigated
import com.lagradost.shiro.ui.tv.TvActivity.Companion.tvActivity
import com.lagradost.shiro.utils.AniListApi
import com.lagradost.shiro.utils.AppUtils.changeStatusBarState
import com.lagradost.shiro.utils.AppUtils.getCurrentActivity
import com.lagradost.shiro.utils.AppUtils.observe
import com.lagradost.shiro.utils.MALApi
import com.lagradost.shiro.utils.MALApi.Companion.getMalUser
import com.lagradost.shiro.utils.MAL_ACCOUNT_ID
import kotlin.concurrent.thread

class SettingsFragmentNew : Fragment() {
    companion object {
        var isInSettings: Boolean = false
        var restoreFileSelector: ActivityResultLauncher<String>? = null
        var settingsViewModel: SettingsViewModel? = null

    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_settings, container, false)
    }

    /** The logo header uses ~260dp, which leaves almost no room for the list in landscape, so it is hidden there (and on TV). */
    private fun updateHeader(landscape: Boolean) {
        fv<android.widget.LinearLayout>(R.id.account_icon)?.let { h ->
            h.layoutTransition = null // the layout animation kept the old space reserved after hiding
            val show = tvActivity == null && !landscape
            h.visibility = if (show) android.view.View.VISIBLE else android.view.View.GONE
            (h.parent as? android.view.View)?.requestLayout()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        /*val array = arrayOf(
            Pair("General", R.xml.settings_general),
            Pair("Style", R.xml.custom_pref_cyanea),
            Pair("Player", R.xml.settings_player),
            Pair("Accounts", R.xml.settings_accounts),
            Pair("History", R.xml.settings_history),
            Pair("Updates", R.xml.settings_update_info),
            Pair("About", R.xml.settings_about),
        )*/
        updateHeader(resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE)
        // Rotating resizes the view without recreating it (and the fragment sees the old orientation), so follow the real size
        view.addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ -> updateHeader((r - l) > (b - t)) }
        val array = arrayOf(
            Pair(fv<android.widget.TextView>(R.id.settings_general), R.xml.settings_general),
            Pair(fv<android.widget.TextView>(R.id.settings_style), R.xml.custom_pref_cyanea),
            Pair(fv<android.widget.TextView>(R.id.settings_player), R.xml.settings_player),
            Pair(fv<android.widget.TextView>(R.id.settings_accounts), R.xml.settings_accounts),
            Pair(fv<android.widget.TextView>(R.id.settings_history), R.xml.settings_history),
            Pair(fv<android.widget.TextView>(R.id.settings_updates), R.xml.settings_update_info),
        )

        array.forEach { pair ->
            pair.first?.background = ColorDrawable(Cyanea.instance.backgroundColorDark)
            pair.first?.setOnClickListener {
                openSettingSubMenu(pair.second)
            }
        }

        fv<android.widget.TextView>(R.id.settings_capture)?.apply {
            background = ColorDrawable(Cyanea.instance.backgroundColorDark)
            setOnClickListener {
                context?.let { startActivity(Intent(it, CapturePrivacyActivity::class.java)) }
            }
        }

        fv<android.widget.TextView>(R.id.settings_identity)?.apply {
            background = ColorDrawable(Cyanea.instance.backgroundColorDark)
            setOnClickListener {
                context?.let { startActivity(Intent(it, AppIdentityActivity::class.java)) }
            }
        }

        fv<android.widget.TextView>(R.id.settings_home)?.apply {
            background = ColorDrawable(Cyanea.instance.backgroundColorDark)
            setOnClickListener {
                context?.let { startActivity(Intent(it, HomeSettingsActivity::class.java)) }
            }
        }

        fv<android.widget.TextView>(R.id.settings_integrations)?.apply {
            background = ColorDrawable(Cyanea.instance.backgroundColorDark)
            setOnClickListener {
                context?.let { startActivity(Intent(it, IntegrationsActivity::class.java)) }
            }
        }

        // Extensions opens its own activity (repo + plugin management),
        // not a preference-xml submenu
        fv<android.widget.TextView>(R.id.settings_extensions)?.apply {
            background = ColorDrawable(Cyanea.instance.backgroundColorDark)
            setOnClickListener {
                context?.let { startActivity(Intent(it, ExtensionManagerActivity::class.java)) }
            }
        }

        fv<android.widget.TextView>(R.id.settings_manga)?.apply {
            background = ColorDrawable(Cyanea.instance.backgroundColorDark)
            setOnClickListener {
                context?.let { startActivity(Intent(it, com.lagradost.shiro.ui.manga.MangaBrowseActivity::class.java)) }
            }
        }

        fv<android.widget.TextView>(R.id.settings_dmca)?.apply {
            background = ColorDrawable(Cyanea.instance.backgroundColorDark)
            setOnClickListener {
                context?.let { LegalPageActivity.open(it, LegalPageActivity.DMCA) }
            }
        }

        fv<android.widget.TextView>(R.id.settings_about)?.apply {
            background = ColorDrawable(Cyanea.instance.backgroundColorDark)
            setOnClickListener {
                context?.let { startActivity(Intent(it, AboutActivity::class.java)) }
            }
        }

        setupSettingsSearch()

        fv<android.widget.TextView>(R.id.settings_community_rules)?.apply {
            background = ColorDrawable(Cyanea.instance.backgroundColorDark)
            setOnClickListener {
                context?.let { LegalPageActivity.open(it, LegalPageActivity.RULES) }
            }
        }

        settingsViewModel =
            settingsViewModel ?: activity?.let {
                ViewModelProvider(it).get(
                    SettingsViewModel::class.java
                )
            }

        val topParams: LinearLayout.LayoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, // view width
            MainActivity.statusHeight // view height
        )
        fv<android.view.View>(R.id.top_padding_settings)?.layoutParams = topParams
        fv<android.widget.LinearLayout>(R.id.settings_root)?.background = ColorDrawable(Cyanea.instance.backgroundColor)

        if (tvActivity == null) {
            // Because the user isn't necessarily fetched
            if (context?.getKey<String>(MAL_TOKEN_KEY, MAL_ACCOUNT_ID, null) != null
                && context?.getKeys(MAL_USER_KEY)?.isEmpty() == true
            ) {
                thread {
                    context?.getMalUser()
                }
            }

            context?.let { context ->
                /*settings_listview?.adapter = ArrayAdapter(context, R.layout.listview_single_item, array.map { it.first })
                settings_listview.setOnItemClickListener { _, _, position, _ ->
                    openSettingSubMenu(array[position].second)
                }*/
                context.loadProfile()
                observe(settingsViewModel!!.hasLoggedIntoMAL) {
                    context.loadProfile()
                }
                observe(settingsViewModel!!.hasLoggedIntoAnilist) {
                    context.loadProfile()
                }


            }
        }
    }

    private fun Context.loadProfile() {
        var userImage: String? = null
        var userName: String? = null
        getKeys(ANILIST_USER_KEY).forEach { key ->
            getKey<AniListApi.AniListUser>(key, null)?.let {
                userImage = it.picture
                userName = it.name
            }
        }

        if (userImage == null || userName == null) {
            getKeys(MAL_USER_KEY).forEach { key ->
                getKey<MALApi.MalUser>(key, null)?.let {
                    userImage = userImage ?: it.picture
                    userName = userName ?: it.name
                }
            }
        }

        if (userName != null) {
            fv<android.widget.TextView>(R.id.name_text)?.text = userName
            fv<android.widget.TextView>(R.id.name_text)?.visibility = VISIBLE
        } else {
            fv<android.widget.TextView>(R.id.name_text)?.visibility = INVISIBLE
        }

        fv<android.widget.ImageView>(R.id.icon_image)?.setOnClickListener {
            fv<android.widget.TextView>(R.id.name_text)?.isVisible = !(fv<android.widget.TextView>(R.id.name_text)?.isVisible ?: false)
        }

        context?.let { context ->
            fv<android.widget.ImageView>(R.id.icon_image)?.let {
                GlideApp.with(context)
                    .load(userImage ?: "")
                    .transition(DrawableTransitionOptions.withCrossFade(100))
                    .error(R.drawable.nekomo_logo)
                    .into(it)
            }
        }
    }

    private fun openSettingSubMenu(xml: Int, highlight: String? = null) {
        val intent = Intent(context ?: return, SettingsActivity::class.java).apply {
            putExtra(XML_KEY, xml)
            if (highlight != null) putExtra(HIGHLIGHT_KEY, highlight)
        }
        startActivity(intent)
    }

    /** Live-filtered jump list over every preference key and every management screen. */
    private fun setupSettingsSearch() {
        val box = fv<android.widget.EditText>(R.id.settings_search) ?: return
        val results = fv<LinearLayout>(R.id.settings_search_results) ?: return
        val ctx = context ?: return
        val pad = (20 * resources.displayMetrics.density).toInt()
        box.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val hits = com.lagradost.shiro.utils.SettingsSearch.search(ctx, s?.toString() ?: "")
                results.removeAllViews()
                results.isVisible = hits.isNotEmpty()
                hits.take(12).forEach { e ->
                    results.addView(LinearLayout(ctx).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(pad, pad / 2, pad, pad / 2)
                        isClickable = true
                        isFocusable = true
                        val out = android.util.TypedValue()
                        ctx.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, out, true)
                        foreground = androidx.core.content.ContextCompat.getDrawable(ctx, out.resourceId)
                        addView(android.widget.TextView(ctx).apply {
                            text = e.title
                            textSize = 15f
                        })
                        addView(android.widget.TextView(ctx).apply {
                            text = listOfNotNull(e.screen, e.summary.takeIf { it.isNotBlank() }).joinToString("  ·  ")
                            textSize = 12f
                            alpha = 0.65f
                            maxLines = 1
                            ellipsize = android.text.TextUtils.TruncateAt.END
                        })
                        setOnClickListener {
                            if (e.xmlRes != null) openSettingSubMenu(e.xmlRes, e.key)
                            else e.activity?.let { startActivity(Intent(ctx, it)) }
                        }
                    })
                }
            }
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        isInSettings = false
    }

    override fun onResume() {
        val settingsManager = PreferenceManager.getDefaultSharedPreferences(requireContext())
        activity?.changeStatusBarState(settingsManager.getBoolean("statusbar_hidden", true))?.let {
            statusHeight = it
        }
        isInSettings = true
        if (tvActivity != null) {
        } else {
            activity?.requestedOrientation = if (settingsManager.getBoolean("force_landscape", false)) {
                ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
            } else {
                ActivityInfo.SCREEN_ORIENTATION_USER
            }
        }
        super.onResume()
    }

}