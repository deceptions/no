package com.lagradost.shiro.ui.settings

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
import kotlinx.android.synthetic.main.fragment_settings.*
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
        account_icon?.isVisible = tvActivity == null
        val array = arrayOf(
            Pair(settings_general, R.xml.settings_general),
            Pair(settings_style, R.xml.custom_pref_cyanea),
            Pair(settings_player, R.xml.settings_player),
            Pair(settings_accounts, R.xml.settings_accounts),
            Pair(settings_history, R.xml.settings_history),
            Pair(settings_updates, R.xml.settings_update_info),
            Pair(settings_about, R.xml.settings_about),
        )

        array.forEach { pair ->
            pair.first?.background = ColorDrawable(Cyanea.instance.backgroundColorDark)
            pair.first?.setOnClickListener {
                openSettingSubMenu(pair.second)
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
        top_padding_settings?.layoutParams = topParams
        settings_root?.background = ColorDrawable(Cyanea.instance.backgroundColor)

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
            name_text?.text = userName
            name_text?.visibility = VISIBLE
        } else {
            name_text?.visibility = INVISIBLE
        }

        icon_image?.setOnClickListener {
            name_text?.isVisible = !(name_text?.isVisible ?: false)
        }

        context?.let { context ->
            icon_image?.let {
                GlideApp.with(context)
                    .load(userImage ?: "")
                    .transition(DrawableTransitionOptions.withCrossFade(100))
                    .error(R.drawable.shiro_logo_rounded)
                    .into(it)
            }
        }
    }

    private fun openSettingSubMenu(xml: Int) {
        val intent = Intent(getCurrentActivity()!!, SettingsActivity::class.java).apply {
            putExtra(XML_KEY, xml)
        }
        startActivity(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        isInSettings = false
    }

    override fun onResume() {
        val settingsManager = PreferenceManager.getDefaultSharedPreferences(activity)
        activity?.changeStatusBarState(settingsManager.getBoolean("statusbar_hidden", true))?.let {
            statusHeight = it
        }
        isInSettings = true
        if (tvActivity != null) {
        } else {
            activity?.requestedOrientation = if (settingsManager.getBoolean("force_landscape", false)) {
                ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
            } else {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
        }
        super.onResume()
    }

}