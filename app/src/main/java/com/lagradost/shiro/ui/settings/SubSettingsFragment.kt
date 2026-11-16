package com.lagradost.shiro.ui.settings

import ANILIST_CACHED_LIST
import ANILIST_SHOULD_UPDATE_LIST
import ANILIST_TOKEN_KEY
import ANILIST_UNIXTIME_KEY
import ANILIST_USER_KEY
import DataStore.getKey
import DataStore.getKeys
import DataStore.mapper
import DataStore.removeKey
import DataStore.removeKeys
import DataStore.setKey
import MAL_CACHED_LIST
import MAL_REFRESH_TOKEN_KEY
import MAL_SHOULD_UPDATE_LIST
import MAL_TOKEN_KEY
import MAL_UNIXTIME_KEY
import MAL_USER_KEY
import VIEWSTATE_KEY
import VIEW_DUR_KEY
import VIEW_LST_KEY
import VIEW_POS_KEY
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.net.http.HttpResponseCache
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.preference.MultiSelectListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreference
import com.bumptech.glide.Glide
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.shiro.BuildConfig
import com.lagradost.shiro.R
import com.lagradost.shiro.ui.library.LibraryFragment.Companion.libraryViewModel
import com.lagradost.shiro.ui.settings.SettingsFragment.Companion.restoreFileSelector
import com.lagradost.shiro.ui.settings.SettingsFragmentNew.Companion.settingsViewModel
import com.lagradost.shiro.ui.tv.TvActivity.Companion.tvActivity
import com.lagradost.shiro.utils.ANILIST_ACCOUNT_ID
import com.lagradost.shiro.utils.APIS
import com.lagradost.shiro.utils.AniListApi.Companion.authenticateAniList
import com.lagradost.shiro.utils.AppUtils.allApi
import com.lagradost.shiro.utils.AppUtils.getCurrentActivity
import com.lagradost.shiro.utils.AppUtils.installCache
import com.lagradost.shiro.utils.BackupUtils
import com.lagradost.shiro.utils.BackupUtils.backup
import com.lagradost.shiro.utils.BackupUtils.restore
import com.lagradost.shiro.utils.BackupUtils.restorePrompt
import com.lagradost.shiro.utils.InAppUpdater.runAutoUpdate
import com.lagradost.shiro.utils.MALApi.Companion.authenticateMAL
import com.lagradost.shiro.utils.MAL_ACCOUNT_ID
import com.lagradost.shiro.utils.NekomoApi.Companion.requestHome
import java.io.File
import kotlin.concurrent.thread

const val XML_KEY = "xml"
const val HIGHLIGHT_KEY = "highlight"

val blacklistedTvKeys = listOf(
    "update_repos",
    "force_landscape",
    "swipe_to_refresh",
    "show_subscribed",
    "pick_downloads",
    "subscribe_to_announcements",
    "data_saving",
    "disable_data_downloads",
    "concurrent_downloads",
    "backup_btt",
    "restore_btt",
    "pip_enabled",
    "swipe_enabled",
    "swipe_vertical_enabled",
    "double_tap_enabled",
    "hide_player_ffwd",
    "fullscreen_notch",
    "ignore_ssl",
    "chromecast_tap_time",
    "allow_player_rotation",
    "compact_search_enabled",
    "statusbar_hidden",
    "pref_color_navigation_bar",
    "accent_color_for_nav_view",
    "statusbar_hidden",
    "hide_open_website",
    "expanded_span_count",
    "new_results_page",
    "disable_player_shadow",
    // These gate on-screen touch buttons in PlayerFragment; PlayerFragmentTv uses its own
    // always-available D-pad actions for skip/resize/speed instead, so the toggles are no-ops on TV.
    "skip_op_enabled",
    "playback_speed_enabled",
    "player_resize_enabled",
    // No manga reader on TV
    "manga_reader_mode",
    "manga_reader_bg",
    "manga_volume_keys",
    "manga_keep_screen_on",
    "manga_immersive",
    "manga_remember_position"
)

class SubSettingsFragment : PreferenceFragmentCompat() {
    private var pendingNsfw: SwitchPreference? = null
    private val lockLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == android.app.Activity.RESULT_OK) pendingNsfw?.isChecked = true
        pendingNsfw = null
    }

    private var xmlFile: Int? = null

    companion object {
        fun newInstance(xml: Int, highlight: String? = null) =
            SubSettingsFragment().apply {
                arguments = Bundle().apply {
                    putInt(XML_KEY, xml)
                    if (highlight != null) putString(HIGHLIGHT_KEY, highlight)
                }
            }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        //val rv: RecyclerView = listView // This holds the PreferenceScreen's items
        //rv.setPadding(0, getCurrentActivity()!!.getNavigationBarSizeFake() + 20.toPx, 0, 0)
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)

        arguments?.getInt(XML_KEY)?.let {
            xmlFile = it
        }
    }

    fun setTitle(title: String) {
        activity?.title = title
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        xmlFile?.let { setPreferencesFromResource(it, rootKey) }

        // Settings search can ask for a specific row; give the list a beat to lay out first.
        arguments?.getString(HIGHLIGHT_KEY)?.let { key ->
            view?.postDelayed({
                try {
                    scrollToPreference(key)
                } catch (e: Exception) {
                    com.lagradost.shiro.utils.mvvm.logError(e)
                }
            }, 350)
        }

        if (tvActivity != null) {
            blacklistedTvKeys.forEach {
                findPreference<Preference>(it)?.isVisible = false
            }
        }
        when (xmlFile) {

            R.xml.settings_general -> {

                /** General settings */

                setTitle("General settings")

                // Preferred source: filled with whatever the user has installed
                findPreference<androidx.preference.ListPreference>("preferred_source")?.let { lp ->
                    val ctx = requireContext()
                    val cs = runCatching { com.lagradost.shiro.utils.cs3.CsPluginManager.providers().map { it.name }.distinct() }.getOrDefault(emptyList())
                    val zg = runCatching { com.lagradost.shiro.utils.zg.ZangetsuManager.enabledSources(ctx) }.getOrDefault(emptyList())
                    val ay = runCatching { com.lagradost.shiro.utils.aniyomi.AniyomiBridge.sourceChoices() }.getOrDefault(emptyList())
                    lp.entries = (listOf("None (ask each time)") + cs + zg.map { "Zangetsu · ${it.name}" } + ay.map { "Aniyomi · ${it.first}" }).toTypedArray()
                    lp.entryValues = (listOf("none") + cs.map { "cs:$it" } + zg.map { "zg:${it.id}" } + ay.map { "ay:${it.second}" }).toTypedArray()
                    if (lp.value == null || lp.entryValues.none { it == lp.value }) lp.value = "none"
                    val none = cs.isEmpty() && zg.isEmpty() && ay.isEmpty()
                    lp.summaryProvider = androidx.preference.Preference.SummaryProvider<androidx.preference.ListPreference> { p ->
                        if (none) "No sources installed. Nekomo works as a tracker until you add some in Settings > Extensions."
                        else p.entry?.toString() ?: "None (ask each time)"
                    }
                }
                findPreference<androidx.preference.EditTextPreference>("tmdb_api_key")?.setOnPreferenceChangeListener { _, _ ->
                    com.lagradost.shiro.utils.home.HomeFeed.clear()
                    com.lagradost.shiro.utils.meta.ApiHealth.reset()
                    true
                }

                // 18+ content is allowed but off by default; turning it on needs an explicit confirmation.
                findPreference<SwitchPreference>("nsfw_enabled")?.setOnPreferenceChangeListener { pref, newValue ->
                    if (newValue == true) {
                        AlertDialog.Builder(requireContext(), R.style.AlertDialogCustom)
                            .setTitle("Show 18+ content?")
                            .setMessage(
                                "This shows adult sources and extensions in extension lists, search and the source chooser, " +
                                        "and lets search include adult titles. Only turn it on if you are 18 or older (or the age of " +
                                        "majority where you live). You can switch it off again at any time."
                            )
                            .setPositiveButton("I'm 18+ — show it") { _, _ ->
                                val ctx = requireContext()
                                if (!com.lagradost.shiro.utils.Privacy.adultLock(ctx)) {
                                    (pref as SwitchPreference).isChecked = true
                                } else {
                                    val intent = com.lagradost.shiro.utils.Privacy.confirmLockIntent(ctx)
                                    if (intent == null) {
                                        Toast.makeText(ctx, "Set a screen lock on your device first, or turn off the 18+ lock.", Toast.LENGTH_LONG).show()
                                    } else {
                                        pendingNsfw = pref as SwitchPreference
                                        lockLauncher.launch(intent)
                                    }
                                }
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                        false
                    } else true
                }

                // New-episode alerts: reschedule the periodic check and ask for the notification
                // permission (Android 13+) the first time the user turns it on.
                findPreference<SwitchPreference>("notify_new_episodes")?.setOnPreferenceChangeListener { _, newValue ->
                    if (newValue == true && android.os.Build.VERSION.SDK_INT >= 33 &&
                        androidx.core.content.ContextCompat.checkSelfPermission(
                            requireContext(), android.Manifest.permission.POST_NOTIFICATIONS
                        ) != android.content.pm.PackageManager.PERMISSION_GRANTED
                    ) {
                        requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 7402)
                    }
                    context?.let { com.lagradost.shiro.utils.NewEpisodeWorker.schedule(it, newValue == true) }
                    true
                }

                findPreference<SwitchPreference>("force_landscape")?.setOnPreferenceChangeListener { _, newValue ->
                    if (newValue == true) {
                        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
                    } else {
                        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER
                    }
                    return@setOnPreferenceChangeListener true
                }

                findPreference<SwitchPreference>("hide_chinese")?.setOnPreferenceChangeListener { _, newValue ->
                    thread {
                        context?.requestHome(false)
                    }
                    return@setOnPreferenceChangeListener true
                }


                /*findPreference<SwitchPreference?>("use_external_storage")?.setOnPreferenceChangeListener { _, newValue ->
                    if (newValue == true) {
                        if (!activity?.checkWrite()!!) {
                            activity?.requestRW()
                        }
                    }
                    return@setOnPreferenceChangeListener true
                }*/

                // Push announcements were backed by Firebase on the old backend;
                // hide the switch rather than leave an inert toggle.
                findPreference<Preference>("subscribe_to_announcements")?.isVisible = false

                /** End of General settings */
            }

            R.xml.settings_player -> {

                /** Player settings */

                setTitle("Player settings")

                if (tvActivity == null) {
                    findPreference<Preference?>("pip_enabled")?.isVisible =
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    findPreference<Preference>("fullscreen_notch")?.isVisible =
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                }

                // Subtitle delay rides the Experimental features switch (General -> Advanced);
                // stays visible if a non-zero delay is already set so existing users keep access.
                androidx.preference.PreferenceManager.getDefaultSharedPreferences(requireContext()).let { prefs ->
                    findPreference<Preference>("subtitle_delay_ms")?.isVisible =
                        prefs.getBoolean("experimental_features", false) || prefs.getInt("subtitle_delay_ms", 0) != 0
                    findPreference<Preference>("audio_delay_ms")?.isVisible =
                        prefs.getBoolean("experimental_features", false) || prefs.getInt("audio_delay_ms", 0) != 0
                }

                val selectedProvidersPreference = findPreference<MultiSelectListPreference?>("selected_providers")
                selectedProvidersPreference?.isVisible = false
                val apiNames = APIS.map { it.name }

                selectedProvidersPreference?.entries = apiNames.toTypedArray()
                selectedProvidersPreference?.entryValues = apiNames.toTypedArray()
                selectedProvidersPreference?.setOnPreferenceChangeListener { _, newValue ->
                    allApi.providersActive = newValue as HashSet<String>

                    return@setOnPreferenceChangeListener true
                }

                /** End of Player settings */

            }

            R.xml.settings_accounts -> {

                /** Account settings */

                setTitle("Account settings")



                fun isLoggedIntoMal(): Boolean {
                    return context?.getKey<String>(MAL_TOKEN_KEY, MAL_ACCOUNT_ID, null) != null
                }

                fun isLoggedIntoAniList(): Boolean {
                    return context?.getKey<String>(ANILIST_TOKEN_KEY, ANILIST_ACCOUNT_ID, null) != null
                }

                val anilistButton = findPreference("anilist_setting_btt") as Preference?
                val isLoggedInAniList = isLoggedIntoAniList()
                val malButton = findPreference("mal_setting_btt") as Preference?

                anilistButton?.summary = if (isLoggedInAniList) "Logged in" else "Not logged in"
                anilistButton?.setOnPreferenceClickListener {
                    if (!isLoggedIntoAniList()) {
                        activity?.authenticateAniList()
                    } else {
                        activity?.let {
                            AlertDialog.Builder(it, R.style.AlertDialogCustom)
                                .setPositiveButton(
                                    "Logout"
                                ) { _, _ ->
                                    context?.removeKey(ANILIST_UNIXTIME_KEY, ANILIST_ACCOUNT_ID)
                                    context?.removeKey(ANILIST_TOKEN_KEY, ANILIST_ACCOUNT_ID)
                                    context?.removeKey(ANILIST_USER_KEY, ANILIST_ACCOUNT_ID)
                                    context?.setKey(ANILIST_SHOULD_UPDATE_LIST, true)
                                    context?.removeKey(ANILIST_CACHED_LIST)

                                    libraryViewModel?.requestAnilistList(context)

                                    anilistButton.summary = if (isLoggedIntoMal()) "Logged in" else "Not logged in"
                                    settingsViewModel?.hasLoggedIntoAnilist?.postValue(false)
                                }
                                .setNegativeButton(
                                    "Cancel"
                                ) { _, _ ->
                                    // User cancelled the dialog
                                }
                                // Set other dialog properties
                                .setTitle("Logout from AniList")

                                // Create the AlertDialog
                                .create()
                                .show()
                        }

                    }
                    anilistButton.summary = if (isLoggedIntoAniList()) "Logged in" else "Not logged in"

                    return@setOnPreferenceClickListener true
                }

                val kitsuButton = findPreference("kitsu_setting_btt") as Preference?
                kitsuButton?.summary =
                    if (context?.let { com.lagradost.shiro.utils.auth.AuthManager.isLoggedIntoKitsu(it) } == true)
                        "Logged in" else "Not logged in (sign in from the welcome screen)"
                kitsuButton?.setOnPreferenceClickListener {
                    val ctx = context ?: return@setOnPreferenceClickListener true
                    if (com.lagradost.shiro.utils.auth.AuthManager.isLoggedIntoKitsu(ctx)) {
                        AlertDialog.Builder(ctx, R.style.AlertDialogCustom)
                            .setTitle("Logout from Kitsu")
                            .setPositiveButton("Logout") { _, _ ->
                                com.lagradost.shiro.utils.auth.AuthManager.logoutKitsu(ctx)
                                kitsuButton.summary = "Not logged in"
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                    true
                }

                val isLoggedInMAL = isLoggedIntoMal()
                malButton?.summary = if (isLoggedInMAL) "Logged in" else "Not logged in"
                malButton?.setOnPreferenceClickListener {
                    if (!isLoggedIntoMal()) {
                        activity?.authenticateMAL()
                    } else {
                        activity?.let { activity ->
                            AlertDialog.Builder(activity, R.style.AlertDialogCustom)
                                .setPositiveButton(
                                    "Logout"
                                ) { _, _ ->
                                    context?.removeKey(MAL_TOKEN_KEY, MAL_ACCOUNT_ID)
                                    context?.removeKey(MAL_REFRESH_TOKEN_KEY, MAL_ACCOUNT_ID)
                                    context?.removeKey(MAL_USER_KEY, MAL_ACCOUNT_ID)
                                    context?.removeKey(MAL_UNIXTIME_KEY, MAL_ACCOUNT_ID)
                                    context?.setKey(MAL_SHOULD_UPDATE_LIST, true)
                                    context?.removeKey(MAL_CACHED_LIST)

                                    libraryViewModel?.requestMalList(context)

                                    malButton.summary = if (isLoggedIntoMal()) "Logged in" else "Not logged in"
                                    settingsViewModel?.hasLoggedIntoMAL?.postValue(false)
                                }
                                .setNegativeButton(
                                    "Cancel"
                                ) { _, _ ->
                                    // User cancelled the dialog
                                }
                                // Set other dialog properties
                                .setTitle("Logout from MAL")

                                // Create the AlertDialog
                                .create()
                                .show()
                        }
                    }

                    return@setOnPreferenceClickListener true
                }

                /** End of Account settings */
            }

            R.xml.settings_history -> {

                /** History settings */

                setTitle("History settings")

                // App lock: set/change/remove the startup PIN. Removing or changing asks the current code.
                findPreference<Preference>("app_lock")?.apply {
                    summary = if (com.lagradost.shiro.utils.AppLock.enabled(requireContext()))
                        "On — the app asks for your code when it starts" else "Asks for your code every time the app starts"
                    setOnPreferenceClickListener { pref ->
                        val c = requireContext()
                        val wasOn = com.lagradost.shiro.utils.AppLock.enabled(c)
                        fun pinDialog(title: String, onPin: (String) -> Boolean) {
                            val input = android.widget.EditText(c).apply {
                                inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
                                maxLines = 1
                            }
                            AlertDialog.Builder(c, R.style.AlertDialogCustom)
                                .setTitle(title)
                                .setView(input)
                                .setPositiveButton("OK") { _, _ ->
                                    if (!onPin(input.text.toString())) {
                                        Toast.makeText(c, "Wrong PIN", Toast.LENGTH_SHORT).show()
                                    } else {
                                        pref.summary = if (com.lagradost.shiro.utils.AppLock.enabled(c))
                                            "On — the app asks for your code when it starts" else "Asks for your code every time the app starts"
                                    }
                                }
                                .setNegativeButton("Cancel", null)
                                .show()
                        }
                        if (!wasOn) {
                            pinDialog("Choose a PIN (4+ digits)") { pin ->
                                if (pin.length >= 4) {
                                    com.lagradost.shiro.utils.AppLock.set(c, pin)
                                    Toast.makeText(c, "App lock enabled", Toast.LENGTH_SHORT).show()
                                    true
                                } else {
                                    Toast.makeText(c, "PIN needs at least 4 digits", Toast.LENGTH_SHORT).show()
                                    false
                                }
                            }
                        } else {
                            pinDialog("Enter your current PIN") { pin ->
                                if (!com.lagradost.shiro.utils.AppLock.check(c, pin)) false
                                else {
                                    AlertDialog.Builder(c, R.style.AlertDialogCustom)
                                        .setTitle("App lock")
                                        .setItems(arrayOf("Change PIN", "Remove app lock")) { _, which ->
                                            if (which == 0) {
                                                pinDialog("Choose a new PIN (4+ digits)") { np ->
                                                    if (np.length >= 4) {
                                                        com.lagradost.shiro.utils.AppLock.set(c, np)
                                                        Toast.makeText(c, "PIN changed", Toast.LENGTH_SHORT).show()
                                                        true
                                                    } else {
                                                        Toast.makeText(c, "PIN needs at least 4 digits", Toast.LENGTH_SHORT).show()
                                                        false
                                                    }
                                                }
                                            } else {
                                                com.lagradost.shiro.utils.AppLock.clear(c)
                                                pref.summary = "Asks for your code every time the app starts"
                                                Toast.makeText(c, "App lock removed", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                        .show()
                                    true
                                }
                            }
                        }
                        true
                    }
                }

                findPreference<Preference?>("clear_search_history")?.setOnPreferenceClickListener {
                    context?.let { c ->
                        com.lagradost.shiro.utils.SearchHistory.clear(c)
                        Toast.makeText(c, "Search history cleared", Toast.LENGTH_SHORT).show()
                    }
                    true
                }
                val clearHistory = findPreference<Preference?>("clear_history")
                val historyItems = context?.getKeys(VIEW_POS_KEY)?.size?.plus(
                    context?.getKeys(
                        VIEWSTATE_KEY
                    )?.size ?: 0
                )
                clearHistory?.summary = "$historyItems item${if (historyItems == 1) "" else "s"}"
                clearHistory?.setOnPreferenceClickListener {
                    val alertDialog: AlertDialog? = activity?.let {
                        val builder = AlertDialog.Builder(it, R.style.AlertDialogCustom)
                        builder.apply {
                            setPositiveButton(
                                "OK"
                            ) { _, _ ->
                                val amount = context.removeKeys(VIEW_POS_KEY) + context.removeKeys(
                                    VIEWSTATE_KEY
                                )
                                context.removeKeys(VIEW_LST_KEY)
                                context.removeKeys(VIEW_DUR_KEY)
                                if (amount != 0) {
                                    Toast.makeText(
                                        context,
                                        "Cleared $amount item${if (amount == 1) "" else "s"}",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                                thread {
                                    context.requestHome(true)
                                }
                                clearHistory.summary = "0 items"
                            }
                            setNegativeButton(
                                "Cancel"
                            ) { _, _ ->
                                // User cancelled the dialog
                            }
                        }
                        // Set other dialog properties
                        builder.setTitle("Clear watch history")
                        // Create the AlertDialog
                        builder.create()
                    }
                    if (context?.getKeys(VIEW_POS_KEY)?.isNotEmpty() == true || context?.getKeys(
                            VIEWSTATE_KEY
                        )?.isNotEmpty() == true
                    ) {
                        alertDialog?.show()
                    }
                    return@setOnPreferenceClickListener true
                }
                val clearCache = findPreference("clear_cache") as Preference?
                clearCache?.setOnPreferenceClickListener {
                    val glide = Glide.get(getCurrentActivity()!!)
                    glide.clearMemory()
                    thread {
                        glide.clearDiskCache()
                    }
                    val updateFile = File(activity?.filesDir.toString() + "/Download/apk/update.apk")
                    if (updateFile.exists()) {
                        updateFile.delete()
                    }
                    Toast.makeText(context, "Cleared image cache", Toast.LENGTH_LONG).show()
                    return@setOnPreferenceClickListener true
                }

                val clearNetworkCache = findPreference("clear_network_cache") as Preference?
                clearNetworkCache?.setOnPreferenceClickListener {
                    HttpResponseCache.getInstalled()?.delete()
                    getCurrentActivity()!!.installCache()
                    context?.removeKey(MAL_CACHED_LIST)
                    context?.setKey(MAL_SHOULD_UPDATE_LIST, true)
                    context?.removeKey(ANILIST_CACHED_LIST)
                    context?.setKey(ANILIST_SHOULD_UPDATE_LIST, true)
                    Toast.makeText(context, "Cleared network cache", Toast.LENGTH_LONG).show()
                    return@setOnPreferenceClickListener true
                }

                findPreference<Preference?>("error_log_btt")?.setOnPreferenceClickListener {
                    startActivity(Intent(activity, ErrorLogActivity::class.java)); true
                }

                // Advanced & diagnostics
                findPreference<Preference>("adv_error_log")?.setOnPreferenceClickListener {
                    startActivity(Intent(activity, ErrorLogActivity::class.java)); true
                }
                findPreference<Preference>("adv_diagnostics")?.setOnPreferenceClickListener {
                    context?.let { c ->
                        val info = "Nekomo ${com.lagradost.shiro.BuildConfig.VERSION_NAME} (${com.lagradost.shiro.BuildConfig.VERSION_CODE}) ${com.lagradost.shiro.BuildConfig.BUILD_TYPE}/${com.lagradost.shiro.BuildConfig.FLAVOR}" +
                                " | ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} | Android ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})"
                        (c.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                            .setPrimaryClip(android.content.ClipData.newPlainText("Nekomo diagnostics", info))
                        Toast.makeText(c, "Diagnostics copied", Toast.LENGTH_SHORT).show()
                    }
                    true
                }
                findPreference<Preference>("adv_cache_clear")?.setOnPreferenceClickListener {
                    context?.let { c ->
                        var freed = 0L
                        fun du(f: java.io.File): Long = f.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                        val dirs = listOf(java.io.File(c.filesDir, "exoplayer"), java.io.File(c.cacheDir, "image_manager_disk_cache"))
                        val total = dirs.sumOf { if (it.exists()) du(it) else 0 }
                        dirs.forEach { it.deleteRecursively() }
                        freed = total
                        Toast.makeText(c, "Freed ${freed / (1024 * 1024)} MB of cache", Toast.LENGTH_SHORT).show()
                    }
                    true
                }

                findPreference<Preference?>("backup_btt")?.setOnPreferenceClickListener {
                    activity?.backup()
                    return@setOnPreferenceClickListener true
                }

                restoreFileSelector = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
                    activity?.let { activity ->
                        uri?.let {
                            try {
                                val input =
                                    activity.contentResolver.openInputStream(uri) ?: return@registerForActivityResult

                                /*val bis = BufferedInputStream(input)
                                val buf = ByteArrayOutputStream()
                                var result = bis.read()
                                while (result != -1) {
                                    buf.write(result)
                                    result = bis.read()
                                }
                                val fullText = buf.toString("UTF-8")

                                 println(fullText)*/
                                val builder = AlertDialog.Builder(activity)
                                val items = arrayOf("Settings", "Data")
                                val preselectedItems = booleanArrayOf(true, true)
                                builder.setTitle("Select what to restore")
                                    .setMultiChoiceItems(
                                        items, preselectedItems
                                    ) { _, which, isChecked ->
                                        preselectedItems[which] = isChecked
                                    }
                                builder.setPositiveButton("OK") { _, _ ->
                                    val restoredValue = mapper.readValue<BackupUtils.BackupFile>(input)
                                    activity.restore(restoredValue, preselectedItems[0], preselectedItems[1])
                                    activity.requestHome(true)
                                    val intent = Intent(activity, getCurrentActivity()!!::class.java)
                                    startActivity(intent)
                                    activity.finishAffinity()
                                }

                                builder.setNegativeButton("Cancel") { _, _ ->

                                }
                                builder.show()
                            } catch (e: Exception) {
                                e.printStackTrace()
                                Toast.makeText(activity, "Error restoring backup file :(", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }


                findPreference<Preference?>("restore_btt")?.setOnPreferenceClickListener {
                    activity?.restorePrompt()
                    return@setOnPreferenceClickListener true
                }

                /** End of History settings */
            }

            R.xml.settings_update_info -> {

                /** Update info settings */

                setTitle("Updates")

                // Changelog
                val changeLog = findPreference("changelog") as Preference?
                changeLog?.setOnPreferenceClickListener {
                    val alertDialog: AlertDialog? = activity?.let {
                        val builder = AlertDialog.Builder(it, R.style.AlertDialogCustom)
                        builder.apply {
                            setPositiveButton("OK") { _, _ -> }
                        }
                        // Set other dialog properties
                        builder.setTitle(BuildConfig.VERSION_NAME)
                        builder.setMessage(getString(R.string.changelog))
                        // Create the AlertDialog
                        builder.create()
                    }
                    alertDialog?.show()
                    return@setOnPreferenceClickListener true
                }
                val checkUpdates = findPreference("check_updates") as Preference?
                checkUpdates?.setOnPreferenceClickListener {
                    thread {
                        if (context != null && activity != null) {
                            activity?.runOnUiThread { Toast.makeText(activity, "Checking for updates...", Toast.LENGTH_SHORT).show() }
                            val updateSuccess = requireActivity().runAutoUpdate(false)
                            if (!updateSuccess) {
                                activity?.runOnUiThread {
                                    Toast.makeText(activity, "You're up to date (${com.lagradost.shiro.utils.InAppUpdater.installedChannel()} channel)", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                    return@setOnPreferenceClickListener true
                }

                findPreference<Preference>("subscribe_to_updates")?.isVisible = false

                val versionButton = findPreference("version") as Preference?
                versionButton?.summary = BuildConfig.VERSION_NAME + " · " +
                        com.lagradost.shiro.utils.InAppUpdater.installedChannel() + " channel · " +
                        (if (com.lagradost.shiro.utils.InAppUpdater.isTvBuild()) "TV" else "Phone") +
                        " · built on " + BuildConfig.BUILDDATE
                versionButton?.setOnPreferenceClickListener {
                    /*if (easterEggClicks == 7) {
                        if (DataStore.getKey("pink_theme", false) != true) {
                            Toast.makeText(context, "Unlocked pink theme", Toast.LENGTH_LONG).show()
                            DataStore.setKey("pink_theme", true)
                            unlockPinkTheme()
                        }
                    }
                    easterEggClicks++*/
                    return@setOnPreferenceClickListener true
                }

                /** End of Update info settings */

            }

            R.xml.settings_about -> {

                /** Info settings */

                setTitle("About")
                val channel = when (com.lagradost.shiro.BuildConfig.BUILD_TYPE) {
                    "debug" -> "Alpha"
                    "beta" -> "Beta"
                    else -> "Stable"
                }
                findPreference<Preference>("about_licenses")?.setOnPreferenceClickListener {
                    val ctx = requireContext()
                    val libs = try { ctx.assets.open("licenses.txt").bufferedReader().use { it.readText() } } catch (e: Exception) { "" }
                    val text = "Nekomo is free software, distributed under the GNU General Public License v3.0 or later " +
                            "(it includes GPL-3.0 components such as the CloudStream API). Original Shiro code is MIT. " +
                            "Source: https://github.com/Nekomo-App/neko-source" + "\n\n" +
                            "Extensions, repositories and sources are installed by you and keep their own licenses; " +
                            "Nekomo includes none." + "\n\n" + libs
                    val tv = android.widget.TextView(ctx).apply {
                        this.text = text
                        textSize = 12f
                        setTextIsSelectable(true)
                        setPadding(48, 24, 48, 24)
                    }
                    AlertDialog.Builder(ctx, R.style.AlertDialogCustom)
                        .setTitle("Open-source licenses")
                        .setView(android.widget.ScrollView(ctx).apply { addView(tv) })
                        .setPositiveButton("OK", null)
                        .setNeutralButton("Source code") { _, _ ->
                            com.lagradost.shiro.ui.welcome.SocialLinks.openUrl(ctx, "https://github.com/Nekomo-App/neko-source")
                        }
                        .show()
                    true
                }
                findPreference<Preference>("about_version")?.summary =
                    "${com.lagradost.shiro.BuildConfig.VERSION_NAME} · $channel channel"

                findPreference<Preference>("about_devin")?.setOnPreferenceClickListener {
                    val ctx = requireContext()
                    val text = "Nekomo is built and maintained with assistance from Devin AI " +
                            "(Cognition). Devin helps the maintainers review and analyze the app's " +
                            "code, make improvements and bug fixes, manage code changes, and handle " +
                            "GitHub operations such as committing, pulling, pushing and publishing " +
                            "releases.\n\n" +
                            "Every change is reviewed before it ships, and Devin runs inside the " +
                            "project's own repository — no user data, accounts or viewing activity " +
                            "is ever sent to it. This notice exists for transparency about how the " +
                            "project is developed."
                    val tv = android.widget.TextView(ctx).apply {
                        this.text = text
                        textSize = 14f
                        setTextIsSelectable(true)
                        setPadding(48, 24, 48, 24)
                    }
                    AlertDialog.Builder(ctx, R.style.AlertDialogCustom)
                        .setTitle("AI-assisted development")
                        .setView(android.widget.ScrollView(ctx).apply { addView(tv) })
                        .setPositiveButton("OK", null)
                        .show()
                    true
                }
                findPreference<Preference>("about_original")?.setOnPreferenceClickListener {
                    val ctx = requireContext()
                    val text = "Nekomo is a continuation of the original Shiro anime app " +
                            "(the open-source project by Lag and Blue Fire that Kuro also descends from). " +
                            "It keeps Shiro's recognizable look, architecture and data so existing users feel at home, " +
                            "then builds on it:\n\n" +
                            "• Extensions: CloudStream (.cs3), Zangetsu (JS), Aniyomi anime and Mihon manga sources\n" +
                            "• Manga reader (webtoon / paged LTR–RTL, offline chapter downloads) and a manga source browser\n" +
                            "• Classic and Modern interface modes, card layout presets, poster size, corners, spacing\n" +
                            "• Quick-setup onboarding, PIN app lock, settings search\n" +
                            "• New-episode alerts with quiet hours and per-show mutes, \"Coming up\" home row\n" +
                            "• Offline library with statuses, custom collections, sorting, export/import and AniList sync\n" +
                            "• DNS-over-HTTPS, in-app updater, Android TV builds, 40+ themes\n\n" +
                            "The original project's core is MIT-licensed; the combined app is GPL-3.0-or-later " +
                            "because it ships GPL-3.0 components (see Open-source licenses)."
                    val tv = android.widget.TextView(ctx).apply {
                        this.text = text
                        textSize = 14f
                        setTextIsSelectable(true)
                        setPadding(48, 24, 48, 24)
                    }
                    AlertDialog.Builder(ctx, R.style.AlertDialogCustom)
                        .setTitle("Nekomo vs the original app")
                        .setView(android.widget.ScrollView(ctx).apply { addView(tv) })
                        .setPositiveButton("OK", null)
                        .setNeutralButton("Shiro source") { _, _ ->
                            com.lagradost.shiro.ui.welcome.SocialLinks.openUrl(ctx, "https://github.com/deceptions/no")
                        }
                        .show()
                    true
                }

                /** End of Info settings */

            }

        }

    }
}