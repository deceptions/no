package com.lagradost.shiro.ui.settings

import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.ActivityResultLauncher
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import com.jaredrummler.cyanea.Cyanea
import com.lagradost.shiro.R
import com.lagradost.shiro.ui.MainActivity.Companion.statusHeight
import com.lagradost.shiro.ui.settings.SettingsFragmentNew.Companion.isInSettings
import com.lagradost.shiro.utils.AppUtils.changeStatusBarState
import com.lagradost.shiro.utils.AppUtils.getCurrentActivity

class SettingsFragment : PreferenceFragmentCompat() {
    companion object {
        var restoreFileSelector: ActivityResultLauncher<String>? = null
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return super.onCreateView(inflater, container, savedInstanceState)?.apply {
            setBackgroundColor(Cyanea.instance.backgroundColor)
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.settings, rootKey)

        class PreferenceClickListener(val xml: Int) : Preference.OnPreferenceClickListener {
            override fun onPreferenceClick(preference: Preference?): Boolean {
                openSettingSubMenu(xml)
                return true
            }
        }

        findPreference<Preference>("settings_general")?.onPreferenceClickListener =
            PreferenceClickListener(R.xml.settings_general)

        findPreference<Preference>("settings_style")?.onPreferenceClickListener =
            PreferenceClickListener(R.xml.custom_pref_cyanea)

        findPreference<Preference>("settings_player")?.onPreferenceClickListener =
            PreferenceClickListener(R.xml.settings_player)

        findPreference<Preference>("settings_accounts")?.onPreferenceClickListener =
            PreferenceClickListener(R.xml.settings_accounts)

        findPreference<Preference>("settings_history")?.onPreferenceClickListener =
            PreferenceClickListener(R.xml.settings_history)

        findPreference<Preference>("settings_update")?.onPreferenceClickListener =
            PreferenceClickListener(R.xml.settings_update_info)

        findPreference<Preference>("settings_info")?.onPreferenceClickListener =
            PreferenceClickListener(R.xml.settings_about)


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
        activity?.requestedOrientation = if (settingsManager.getBoolean("force_landscape", false)) {
            ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        isInSettings = true
        super.onResume()
    }

}