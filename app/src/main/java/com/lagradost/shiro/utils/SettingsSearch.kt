package com.lagradost.shiro.utils

import android.app.Activity
import android.content.Context
import com.lagradost.shiro.R
import com.lagradost.shiro.ui.manga.MangaBrowseActivity
import com.lagradost.shiro.ui.settings.AppIdentityActivity
import com.lagradost.shiro.ui.settings.CapturePrivacyActivity
import com.lagradost.shiro.ui.settings.ExtensionManagerActivity
import com.lagradost.shiro.ui.settings.HomeSettingsActivity
import com.lagradost.shiro.ui.settings.IntegrationsActivity
import org.xmlpull.v1.XmlPullParser

/**
 * Searchable index of everything under Settings: every preference with a title inside the
 * preference XML screens, plus the standalone management screens. Used by the search box on the
 * settings root page. Results either open a [SettingsActivity] sub-screen (optionally scrolling to
 * [key]) or launch the matching activity.
 */
object SettingsSearch {
    private const val NS_ANDROID = "http://schemas.android.com/apk/res/android"
    private const val NS_APP = "http://schemas.android.com/apk/res-auto"

    data class Entry(
        val title: String,
        val summary: String,
        val screen: String,
        val xmlRes: Int? = null,
        val key: String? = null,
        val activity: Class<out Activity>? = null,
    )

    private var cached: List<Entry>? = null

    fun index(ctx: Context): List<Entry> = cached ?: build(ctx).also { cached = it }

    fun search(ctx: Context, query: String): List<Entry> {
        val q = query.trim().lowercase()
        if (q.length < 2) return emptyList()
        return index(ctx).filter {
            it.title.lowercase().contains(q) || it.summary.lowercase().contains(q) ||
                    (it.key?.lowercase()?.contains(q) == true)
        }.sortedBy { !it.title.lowercase().startsWith(q) }
    }

    private fun build(ctx: Context): List<Entry> {
        val out = ArrayList<Entry>()
        listOf(
            R.xml.settings_general to "General",
            R.xml.custom_pref_cyanea to "Appearance",
            R.xml.settings_player to "Player",
            R.xml.settings_accounts to "Accounts",
            R.xml.settings_history to "History and privacy",
            R.xml.settings_update_info to "Updates",
        ).forEach { (res, screen) -> out += parse(ctx, res, screen) }

        out += Entry("Home page", "Pick sources and reorder home rows", "Browse and sources",
            activity = HomeSettingsActivity::class.java)
        out += Entry("Extensions and sources", "Repositories, CloudStream, Zangetsu, Aniyomi and Mihon extensions", "Browse and sources",
            activity = ExtensionManagerActivity::class.java)
        out += Entry("Manga", "Manga repositories and library", "Browse and sources",
            activity = MangaBrowseActivity::class.java)
        out += Entry("Integrations", "Tracker and subtitle provider accounts", "Account",
            activity = IntegrationsActivity::class.java)
        out += Entry("App name and icon", "Disguise the app on the home screen", "App",
            activity = AppIdentityActivity::class.java)
        out += Entry("Screen capture", "Allow or block screenshots and recents thumbnails", "Privacy and data",
            activity = CapturePrivacyActivity::class.java)
        return out
    }

    private fun parse(ctx: Context, xmlRes: Int, screen: String): List<Entry> {
        val out = ArrayList<Entry>()
        val parser = ctx.resources.getXml(xmlRes)
        try {
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && parser.name.contains("Preference")) {
                    val title = attr(parser, NS_ANDROID, "title", ctx) ?: attr(parser, NS_APP, "title", ctx)
                    val summary = attr(parser, NS_ANDROID, "summary", ctx) ?: attr(parser, NS_APP, "summary", ctx) ?: ""
                    val key = attr(parser, NS_ANDROID, "key", ctx) ?: attr(parser, NS_APP, "key", ctx)
                    val visible = attr(parser, NS_APP, "isPreferenceVisible", ctx)
                    if (!title.isNullOrBlank() && visible != "false") {
                        out += Entry(title, summary, screen, xmlRes = xmlRes, key = key)
                    }
                }
                event = parser.next()
            }
        } finally {
            parser.close()
        }
        return out
    }

    /** Reads an attribute that may be a literal string or a @string/ reference. */
    private fun attr(parser: android.content.res.XmlResourceParser, ns: String, name: String, ctx: Context): String? {
        for (i in 0 until parser.attributeCount) {
            if (parser.getAttributeNamespace(i) == ns && parser.getAttributeName(i) == name) {
                val resId = parser.getAttributeResourceValue(i, 0)
                return if (resId != 0) {
                    try { ctx.getString(resId) } catch (e: Exception) { parser.getAttributeValue(i) }
                } else parser.getAttributeValue(i)
            }
        }
        return null
    }
}
