package com.lagradost.shiro.ui.result
import com.lagradost.shiro.utils.fv

import ANILIST_TOKEN_KEY
import BOOKMARK_KEY
import DataStore.containsKey
import DataStore.getKey
import DataStore.mapper
import DataStore.removeKey
import DataStore.setKey
import MAL_TOKEN_KEY
import RESULTS_PAGE_OVERRIDE_ANILIST
import RESULTS_PAGE_OVERRIDE_MAL
import SUBSCRIPTIONS_BOOKMARK_KEY
import SUBSCRIPTIONS_KEY
import VIEWSTATE_KEY
import android.annotation.SuppressLint
import android.content.*
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Html
import android.view.LayoutInflater
import android.view.View
import android.view.View.GONE
import android.view.View.VISIBLE
import android.view.ViewGroup
import android.view.animation.AlphaAnimation
import android.widget.AbsListView.CHOICE_MODE_SINGLE
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.mediarouter.app.MediaRouteButton
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import androidx.transition.ChangeBounds
import androidx.transition.Transition
import androidx.transition.TransitionManager
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.discord.panels.PanelsChildGestureRegionObserver
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.readValue
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastState
import com.google.android.gms.cast.framework.CastStateListener
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.jaredrummler.cyanea.Cyanea
import com.lagradost.shiro.R
import com.lagradost.shiro.ui.BookmarkedTitle
import com.lagradost.shiro.ui.GlideApp
import com.lagradost.shiro.ui.downloads.DownloadButtonSetup.handleDownloadClick
import com.lagradost.shiro.ui.home.HomeFragment.Companion.homeViewModel
import com.lagradost.shiro.ui.player.PlayerFragment.Companion.onPlayerNavigated
import com.lagradost.shiro.ui.search.ResAdapter
import com.lagradost.shiro.utils.*
import com.lagradost.shiro.utils.AniListApi.Companion.authenticateAniList
import com.lagradost.shiro.utils.AniListApi.Companion.fromIntToAnimeStatus
import com.lagradost.shiro.utils.AniListApi.Companion.getShowId
import com.lagradost.shiro.utils.AniListApi.Companion.postDataAboutId
import com.lagradost.shiro.utils.AniListApi.Companion.secondsToReadable
import com.lagradost.shiro.utils.AppUtils.castEpisode
import com.lagradost.shiro.utils.AppUtils.dubbify
import com.lagradost.shiro.utils.AppUtils.getColorFromAttr
import com.lagradost.shiro.utils.AppUtils.getCurrentActivity
import com.lagradost.shiro.utils.AppUtils.getLatestSeenEpisode
import com.lagradost.shiro.utils.AppUtils.getTextColor
import com.lagradost.shiro.utils.AppUtils.getViewKey
import com.lagradost.shiro.utils.AppUtils.getViewPosDur
import com.lagradost.shiro.utils.AppUtils.guaranteedContext
import com.lagradost.shiro.utils.AppUtils.loadPage
import com.lagradost.shiro.utils.mvvm.logError
import com.lagradost.shiro.utils.AppUtils.hideKeyboard
import com.lagradost.shiro.utils.AppUtils.isCastApiAvailable
import com.lagradost.shiro.utils.AppUtils.loadPlayer
import com.lagradost.shiro.utils.AppUtils.notNull
import com.lagradost.shiro.utils.AppUtils.observe
import com.lagradost.shiro.utils.AppUtils.openBrowser
import com.lagradost.shiro.utils.AppUtils.settingsManager
import com.lagradost.shiro.utils.AppUtils.showNavigation
import com.lagradost.shiro.utils.AppUtils.transparentStatusAndNavigation
import com.lagradost.shiro.utils.Coroutines.main
import com.lagradost.shiro.utils.MALApi.Companion.authenticateMAL
import com.lagradost.shiro.utils.NekomoApi.Companion.getFav
import com.lagradost.shiro.utils.NekomoApi.Companion.getFullUrlCdn
import com.lagradost.shiro.utils.NekomoApi.Companion.getSubbed
import com.lagradost.shiro.utils.NekomoApi.Companion.getVideoLink
import com.lagradost.shiro.utils.mvvm.Resource
import com.lagradost.shiro.utils.mvvm.normalSafeApiCall
import com.lagradost.shiro.utils.mvvm.safeApiCall
import kotlinx.coroutines.Job
import java.util.*
import kotlin.concurrent.schedule
import kotlin.concurrent.thread


const val DESCRIPTION_LENGTH1 = 200
const val SLUG = "slug"
const val NAME = "name"
const val IS_MAL_ID = "isMalId"

class ResultFragment : Fragment(), PanelsChildGestureRegionObserver.GestureRegionsListener {

    private var fillerEpisodes: HashMap<Int, Boolean>? = null
    private var isBookmarked = false

    private var hasLoadedAnilist = false
    private var anilistPage: AniListApi.GetSearchMedia? = null
    private var episodeOffset = 0

    private val hasAniList = guaranteedContext(null).getKey<String>(
        ANILIST_TOKEN_KEY,
        ANILIST_ACCOUNT_ID,
        null
    ) != null
    private val hasMAL = guaranteedContext(null).getKey<String>(MAL_TOKEN_KEY, MAL_ACCOUNT_ID, null) != null
    private var resultViewModel: ResultsViewModel? = null

    companion object {
        //var lastSelectedEpisode = 0
        var isInResults: Boolean = false

        var publicResultViewModel: ResultsViewModel? = null
        fun fixEpTitle(
            _title: String?,
            epNum: Int,
            isMovie: Boolean,
            formatBefore: Boolean = false,
        ): String {
            var title = _title
            if (title == null || title.replace(" ", "") == "") {
                title = "Episode $epNum"
            }
            if (!isMovie && _title != null) {
                title = if (formatBefore) {
                    "E$epNum $title" //•
                } else {
                    "$epNum. $title"
                }
            }
            return title
        }

        fun newInstance(slug: String, name: String, isMalId: Boolean = false) =
            ResultFragment().apply {
                arguments = Bundle().apply {
                    putString(SLUG, slug.replace("-dubbed", "-dub"))
                    putString(NAME, name)
                    putBoolean(IS_MAL_ID, isMalId)
                }
            }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        fv<com.lagradost.shiro.ui.AutofitRecyclerView>(R.id.relatedScrollView)?.adapter?.notifyDataSetChanged()
        fv<com.lagradost.shiro.ui.AutofitRecyclerView>(R.id.recommendationsScrollView)?.adapter?.notifyDataSetChanged()
        super.onConfigurationChanged(newConfig)
        // The page height is pinned to the screen height when it loads; re-measure after rotating so it never stays at the old height
        view?.let { v ->
            v.layoutParams = v.layoutParams?.apply { height = ViewGroup.LayoutParams.MATCH_PARENT }
            v.post { v.layoutParams = v.layoutParams?.apply { height = v.rootView?.height ?: height } }
        }
    }


    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? {
//        val useNewLayout = settingsManager!!.getBoolean("new_results_page", false)
        // TV has its own overlay
        val layout = R.layout.fragment_results_2
        //  if (tvActivity == null) (if (useNewLayout) R.layout.fragment_results_new else R.layout.fragment_results) else R.layout.fragment_results_tv_

        return inflater.inflate(layout, container, false)

    }

    override fun onResume() {
        super.onResume()
        onPlayerNavigated += ::handleVideoPlayerNavigation
        // DownloadManager.downloadStartEvent += ::onDownloadStarted
        isInResults = true
    }

    /**
     * Lets the user pick where this title is streamed from: the built-in catalogue or any installed
     * extension provider (CloudStream etc.). The title is searched in the chosen source and the
     * matching entry opened, replacing this page.
     */
    private fun showSourceChooser() {
        val ctx = context ?: return
        val anime = resultViewModel?.data?.value?.anime
        val title = (anime?.title_english?.takeIf { it.isNotBlank() && !com.lagradost.shiro.utils.isExtensionSlug(anime.slug) }
            ?: anime?.title) ?: return
        val currentSlug = anime?.slug ?: ""
        thread {
            val providers = try {
                com.lagradost.shiro.utils.cs3.CsPluginManager.providers().map { it.name }.distinct()
            } catch (e: Exception) {
                emptyList()
            }
            val zgSources = try {
                com.lagradost.shiro.utils.zg.ZangetsuManager.enabledSources(ctx)
            } catch (e: Exception) {
                emptyList()
            }
            val ayChoices = try {
                com.lagradost.shiro.utils.aniyomi.AniyomiBridge.sourceChoices()
            } catch (e: Exception) {
                emptyList()
            }
            main {
                // (label, key): key null = default, "cs:<name>" = CloudStream provider, "zg:<id>" = Zangetsu source
                val health = com.lagradost.shiro.utils.SourceHealth
                val entries: List<Pair<String, String?>> = listOf("AniList info (no streams)" to null) +
                        providers.map { health.label(it, "cs:$it") to "cs:$it" } +
                        zgSources.map { health.label("Zangetsu · ${it.name}", "zg:${it.id}") to "zg:${it.id}" } +
                        ayChoices.map { health.label("Aniyomi · ${it.first}", "ay:${it.second}") to "ay:${it.second}" }
                val currentKey = com.lagradost.shiro.utils.cs3.CsBridge.parseSlug(currentSlug)?.first?.let { "cs:$it" }
                    ?: com.lagradost.shiro.utils.zg.ZangetsuBridge.parseSlug(currentSlug)?.first?.let { "zg:$it" }
                    ?: com.lagradost.shiro.utils.aniyomi.AniyomiBridge.parseSlug(currentSlug)?.first?.let { "ay:$it" }
                val checked = entries.indexOfFirst { it.second == currentKey }.takeIf { it >= 0 } ?: 0
                AlertDialog.Builder(ctx, R.style.AlertDialogCustom)
                    .setTitle("Choose source")
                    .setSingleChoiceItems(entries.map { it.first }.toTypedArray(), checked) { dialog, which ->
                        dialog.dismiss()
                        searchSource(entries[which].second, entries[which].first, title)
                    }
                    .setNegativeButton("Cancel", null)
                    .apply {
                        if (entries.size == 1) setMessage(
                            if (com.lagradost.shiro.utils.cs3.CsPluginManager.getInstalled(ctx).isNotEmpty() || com.lagradost.shiro.utils.zg.ZangetsuManager.getInstalled(ctx).isNotEmpty())
                                "Your installed sources are hidden by a content filter. Turn on \"Show movies and TV shows\" (or the 18+ option) in Settings → General to use them."
                            else "No extensions or sources installed. Add them in Settings → Extensions."
                        )
                    }
                    .show()
            }
        }
    }

    private fun metaTitle(): String? {
        val anime = resultViewModel?.data?.value?.anime
        return (anime?.title_english?.takeIf { it.isNotBlank() && !com.lagradost.shiro.utils.isExtensionSlug(anime.slug) } ?: anime?.title)
    }

    private fun currentSourceKey(slug: String): String? =
        com.lagradost.shiro.utils.cs3.CsBridge.parseSlug(slug)?.first?.let { "cs:$it" }
            ?: com.lagradost.shiro.utils.zg.ZangetsuBridge.parseSlug(slug)?.first?.let { "zg:$it" }
            ?: com.lagradost.shiro.utils.aniyomi.AniyomiBridge.parseSlug(slug)?.first?.let { "ay:$it" }

    /**
     * Sources dropdown: the user's preferred source (default) and every other installed source or extension.
     * Picking one makes it the new default and opens this title in it. Hidden when nothing is installed.
     */
    private fun setupSourceDropdown(data: NekomoApi.Companion.AnimePageNewData) {
        val ctx = context ?: return
        val row = fv<View>(R.id.source_row)
        val spinner = fv<android.widget.Spinner>(R.id.source_spinner)
        val slug = data.anime.slug
        thread {
            val cs = try { com.lagradost.shiro.utils.cs3.CsPluginManager.providers().map { it.name }.distinct() } catch (e: Exception) { emptyList() }
            val zg = try { com.lagradost.shiro.utils.zg.ZangetsuManager.enabledSources(ctx) } catch (e: Exception) { emptyList() }
            val ay = try { com.lagradost.shiro.utils.aniyomi.AniyomiBridge.sourceChoices() } catch (e: Exception) { emptyList() }
            main {
                val entries: List<Pair<String, String?>> = listOf("Info only (no streams)" to null) +
                        cs.map { it to "cs:$it" } + zg.map { "Zangetsu · ${it.name}" to "zg:${it.id}" } + ay.map { "Aniyomi · ${it.first}" to "ay:${it.second}" }
                if (entries.size == 1) {
                    row.visibility = GONE
                    return@main
                }
                val pref = com.lagradost.shiro.utils.PreferredSource.get(ctx)
                val current = currentSourceKey(slug)
                val labels = entries.map { (l, k) ->
                    val tagged = l + if (k != null && k == pref) "  ★ default" else ""
                    com.lagradost.shiro.utils.SourceHealth.label(tagged, k ?: "")
                }
                spinner.adapter = android.widget.ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, labels)
                val selected = entries.indexOfFirst { it.second != null && it.second == current }.takeIf { it >= 0 }
                    ?: entries.indexOfFirst { it.second != null && it.second == pref }.takeIf { it >= 0 } ?: 0
                spinner.setSelection(selected, false)
                row.visibility = VISIBLE
                spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                    override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
                    override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                        val (label, key) = entries[position]
                        if (key == null || position == selected) return
                        com.lagradost.shiro.utils.PreferredSource.set(ctx, key)
                        if (key != current) metaTitle()?.let { searchSource(key, label, it) }
                    }
                }
            }
        }
    }

    /** Tapping a metadata-only episode: play it through a source, or just track it. */
    private fun showPlaySourcePicker(data: NekomoApi.Companion.AnimePageNewData, index: Int) {
        val ctx = context ?: return
        val all = com.lagradost.shiro.utils.SourcePlayback.installed(ctx)
        if (all.isEmpty()) {
            Toast.makeText(ctx, "No sources installed. Add one in Settings > Extensions.", Toast.LENGTH_LONG).show()
            return
        }
        val def = com.lagradost.shiro.utils.PreferredSource.get(ctx)
        AlertDialog.Builder(ctx, R.style.AlertDialogCustom)
            .setTitle("Stream episode ${index + 1} with")
            .setItems(all.map { com.lagradost.shiro.utils.SourceHealth.label(it.label + if (it.key == def) "  ★ default" else "", it.key) }.toTypedArray()) { _, which ->
                playViaSource(data, index, all[which].key)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Metadata episode -> installed source: finds the title in the source, matches the episode number and opens the
     * player on it. Every failure is explained (missing source, incompatible extension, nothing found, no such episode).
     */
    private fun playViaSource(data: NekomoApi.Companion.AnimePageNewData, index: Int, key: String, chosenExtSlug: String? = null) {
        val ctx = context ?: return
        val anime = data.anime
        val label = com.lagradost.shiro.utils.SourcePlayback.label(ctx, key)
        val titles = listOfNotNull(anime.title_english, anime.title, anime.native_title.takeIf { it.isNotBlank() })
        val epNumber = data.episodes.getOrNull(index)?.episode?.toIntOrNull() ?: (index + 1)
        val loading = AlertDialog.Builder(ctx, R.style.AlertDialogCustomTransparent)
            .setView(layoutInflater.inflate(R.layout.dialog_loading, null)).create()
        loading.show()
        Toast.makeText(ctx, "Finding episode $epNumber in $label…", Toast.LENGTH_SHORT).show()
        thread {
            val outcome = try {
                com.lagradost.shiro.utils.SourcePlayback.resolveAuto(ctx, anime.slug, titles, epNumber, key, chosenExtSlug)
            } catch (t: Throwable) {
                logError(t)
                com.lagradost.shiro.utils.SourcePlayback.Outcome.Problem("$label failed unexpectedly (${t.message ?: t.javaClass.simpleName}). Try another source.")
            }
            main {
                normalSafeApiCall { loading.dismiss() }
                when (outcome) {
                    is com.lagradost.shiro.utils.SourcePlayback.Outcome.Found -> {
                        if (outcome.sourceLabel != label) Toast.makeText(ctx, "$label didn't answer, playing via ${outcome.sourceLabel}", Toast.LENGTH_LONG).show()
                        val alId = Regex("^al(\\d+)$").find(anime.slug)?.groupValues?.get(1)?.toIntOrNull()
                        val malId = Regex("^mal(\\d+)$").find(anime.slug)?.groupValues?.get(1)?.toIntOrNull()
                            ?: runCatching { mapper.readTree(anime.ids).path("mal").asText(null)?.toIntOrNull() }.getOrNull()
                        com.lagradost.shiro.utils.SourcePlayback.link(ctx, outcome.extSlug, anime.slug, anime.title, anime.poster, data.episodes.size, index - outcome.index)
                        activity?.loadPlayer(outcome.index, 0L, outcome.page, alId, malId, null)
                    }
                    is com.lagradost.shiro.utils.SourcePlayback.Outcome.PickMatch ->
                        AlertDialog.Builder(guaranteedContext(context), R.style.AlertDialogCustom)
                            .setTitle("${outcome.sourceLabel}: which one is \"${anime.title}\"?")
                            .setItems(outcome.hits.map { it.title }.toTypedArray()) { _, which ->
                                playViaSource(data, index, key, outcome.hits[which].slug)
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    is com.lagradost.shiro.utils.SourcePlayback.Outcome.Problem ->
                        AlertDialog.Builder(guaranteedContext(context), R.style.AlertDialogCustom)
                            .setTitle("Can't play with $label")
                            .setMessage(outcome.message)
                            .setPositiveButton("Choose another source") { _, _ -> showPlaySourcePicker(data, index) }
                            .setNegativeButton("Close", null)
                            .show()
                }
            }
        }
    }

    private fun showMetaEpisodeMenu(data: NekomoApi.Companion.AnimePageNewData, index: Int, longPress: Boolean = false) {
        val ctx = context ?: return
        // A plain tap streams straight away through the default source; a long press opens the full menu
        val defaultKey = com.lagradost.shiro.utils.SourcePlayback.defaultKey(ctx)
        if (!longPress && defaultKey != null) {
            playViaSource(data, index, defaultKey)
            return
        }
        val slug = data.anime.slug
        val total = data.episodes.size
        val watched = com.lagradost.shiro.utils.Tracker.isWatched(ctx, slug, index)
        val epTitle = data.episodes.getOrNull(index)?.title ?: "Episode ${index + 1}"
        val pref = com.lagradost.shiro.utils.PreferredSource.get(ctx)
        val options = ArrayList<Pair<String, () -> Unit>>()

        if (com.lagradost.shiro.utils.SourcePlayback.installed(ctx).isNotEmpty()) {
            if (defaultKey != null) options.add("Play with ${com.lagradost.shiro.utils.SourcePlayback.label(ctx, defaultKey)}" to { playViaSource(data, index, defaultKey) })
            options.add((if (defaultKey != null) "Play with another source…" else "Choose a source to play…") to { showPlaySourcePicker(data, index) })
        } else {
            options.add("How to add a source" to { activity?.openBrowser(getString(R.string.link_sources_guide)) })
        }

        fun mark(from: Int, to: Int, value: Boolean) {
            val ok = com.lagradost.shiro.utils.Tracker.mark(ctx, slug, data.anime.title, data.anime.poster, total, from, to, value)
            if (!ok) {
                Toast.makeText(ctx, "Incognito mode is on, so progress is not saved.", Toast.LENGTH_LONG).show()
                return
            }
            (fv<RecyclerView>(R.id.episodes_res_view)?.adapter as? MasterEpisodeAdapter)?.notifyDataSetChanged()
            val alId = Regex("^al(\\d+)$").find(slug)?.groupValues?.get(1)?.toIntOrNull()
            val progress = com.lagradost.shiro.utils.Tracker.progress(ctx, slug, total)
            if (alId != null && hasAniList) thread {
                val status = if (total > 0 && progress >= total) AniListApi.Companion.AniListStatusType.Completed else AniListApi.Companion.AniListStatusType.Watching
                ctx.postDataAboutId(alId, status, 0, progress)
            }
            Toast.makeText(ctx, if (value) "Marked as watched" else "Marked as unwatched", Toast.LENGTH_SHORT).show()
        }

        options.add((if (watched) "Mark as unwatched" else "Mark as watched") to { mark(index, index, !watched) })
        if (index > 0) options.add("Mark all up to here as watched" to { mark(0, index, true) })

        AlertDialog.Builder(ctx, R.style.AlertDialogCustom)
            .setTitle(epTitle)
            .setItems(options.map { it.first }.toTypedArray()) { _, which -> options[which].second() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Globe button: switch sub/dub when both exist, and open this title on the web (AniList, MyAnimeList, ...). */
    private fun showGlobeMenu() {
        val ctx = context ?: return
        val data = resultViewModel?.data?.value ?: return
        val slug = data.anime.slug
        val options = ArrayList<Pair<String, () -> Unit>>()
        if (resultViewModel?.hasLoadedOther?.value == true) options.add("Switch between sub and dub" to { resultViewModel?.swapData() })

        val al = Regex("^al(\\d+)$").find(slug)?.groupValues?.get(1) ?: resultViewModel?.totalAniListId?.value?.toString()
        val mal = Regex("^mal(\\d+)$").find(slug)?.groupValues?.get(1)
            ?: runCatching { mapper.readTree(data.anime.ids).path("mal").asText(null)?.takeIf { it != "null" } }.getOrNull()
            ?: resultViewModel?.totalMalId?.value?.toString()
        if (al != null) options.add("Open on AniList" to { activity?.openBrowser("https://anilist.co/anime/$al") })
        if (mal != null) options.add("Open on MyAnimeList" to { activity?.openBrowser("https://myanimelist.net/anime/$mal") })
        Regex("^kt(\\d+)$").find(slug)?.groupValues?.get(1)?.let { id -> options.add("Open on Kitsu" to { activity?.openBrowser("https://kitsu.app/anime/$id") }) }
        Regex("^tvm(\\d+)$").find(slug)?.groupValues?.get(1)?.let { id -> options.add("Open on TVMaze" to { activity?.openBrowser("https://www.tvmaze.com/shows/$id") }) }
        Regex("^tmm(\\d+)$").find(slug)?.groupValues?.get(1)?.let { id -> options.add("Open on TMDB" to { activity?.openBrowser("https://www.themoviedb.org/movie/$id") }) }
        Regex("^tmt(\\d+)$").find(slug)?.groupValues?.get(1)?.let { id -> options.add("Open on TMDB" to { activity?.openBrowser("https://www.themoviedb.org/tv/$id") }) }
        if (options.isEmpty()) {
            Toast.makeText(ctx, "No web page is available for this title.", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(ctx, R.style.AlertDialogCustom)
            .setTitle("Language and web")
            .setItems(options.map { it.first }.toTypedArray()) { _, which -> options[which].second() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun searchSource(key: String?, label: String, title: String) {
        val ctx = context ?: return
        val providerName = if (key == null) null else label
        Toast.makeText(ctx, "Searching ${providerName ?: "default source"}…", Toast.LENGTH_SHORT).show()
        thread {
            val hits: List<Pair<String, String>> = try {
                when {
                    key == null ->
                        com.lagradost.shiro.utils.LiveApi.search(title, null).orEmpty()
                            .filter {
                                !com.lagradost.shiro.utils.cs3.CsBridge.isCs3Slug(it.slug) &&
                                        !com.lagradost.shiro.utils.zg.ZangetsuBridge.isZgSlug(it.slug) &&
                                        !com.lagradost.shiro.utils.aniyomi.AniyomiBridge.isAySlug(it.slug)
                            }
                            .map { (it.title_english?.takeIf { e -> e.isNotBlank() } ?: it.title) to it.slug }
                    key.startsWith("ay:") ->
                        com.lagradost.shiro.utils.aniyomi.AniyomiBridge.searchBySource(key.removePrefix("ay:").toLong(), title)
                            .map { it.title to it.slug }
                    key.startsWith("zg:") ->
                        com.lagradost.shiro.utils.zg.ZangetsuBridge.searchBySource(key.removePrefix("zg:"), title)
                            .map { it.title to it.slug }
                    else ->
                        com.lagradost.shiro.utils.cs3.CsBridge.searchByProvider(key.removePrefix("cs:"), title)
                            .map { it.title to it.slug }
                }
            } catch (e: Exception) {
                logError(e)
                emptyList()
            }
            main {
                if (hits.isEmpty()) {
                    Toast.makeText(context, "\"$title\" was not found in ${providerName ?: "the default source"}", Toast.LENGTH_LONG).show()
                    return@main
                }
                val labels = hits.map { it.first }.toTypedArray()
                AlertDialog.Builder(guaranteedContext(context), R.style.AlertDialogCustom)
                    .setTitle("${providerName ?: "Default"}: pick a match")
                    .setItems(labels) { _, which ->
                        val (name, slug) = hits[which]
                        activity?.onBackPressed()
                        activity?.loadPage(slug, name)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }

    /** Genres arrive either as a JSON array or as a plain comma-separated string. */
    private fun parseGenres(raw: String?): List<String> {
        val t = raw?.trim().orEmpty()
        if (t.isEmpty()) return emptyList()
        return if (t.startsWith("[")) runCatching { mapper.readValue<List<String>>(t) }.getOrDefault(emptyList())
        else t.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }

    private val loadHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var loadFailed = false

    /** Loading screen: the title being opened plus a status line that explains what is happening (and when it is slow). */
    private fun startLoadingStatus() {
        loadFailed = false
        val name = arguments?.getString(NAME)
        fv<android.widget.TextView>(R.id.loading_title)?.text = name?.takeIf { it.isNotBlank() } ?: ""
        fv<View>(R.id.loading_actions)?.visibility = GONE
        fv<View>(R.id.main_load)?.visibility = VISIBLE
        fv<android.widget.TextView>(R.id.loading_status)?.text = "Loading details…"
        loadHandler.removeCallbacksAndMessages(null)
        loadHandler.postDelayed({ fv<android.widget.TextView>(R.id.loading_status)?.text = "Fetching artwork and episodes…" }, 3000)
        loadHandler.postDelayed({
            fv<android.widget.TextView>(R.id.loading_status)?.text = "Still working. The metadata service is slow right now; a backup is used automatically if needed."
        }, 9000)
        loadHandler.postDelayed({ showLoadError("This is taking too long. Check your connection and try again.") }, 25000)
        fv<android.widget.Button>(R.id.loading_retry)?.setOnClickListener { retryLoad() }
        fv<android.widget.Button>(R.id.loading_back)?.setOnClickListener { activity?.onBackPressed() }
    }

    private fun showLoadError(message: String) {
        loadHandler.removeCallbacksAndMessages(null)
        loadFailed = true
        fv<View>(R.id.main_load)?.visibility = GONE
        fv<android.widget.TextView>(R.id.loading_status)?.text = message
        fv<View>(R.id.loading_actions)?.visibility = VISIBLE
        fv<android.widget.FrameLayout>(R.id.loading_overlay)?.alpha = 1f
        fv<android.widget.FrameLayout>(R.id.loading_overlay)?.apply { visibility = VISIBLE; isClickable = true }
    }

    private fun retryLoad() {
        val slug = arguments?.getString(SLUG) ?: return
        val name = arguments?.getString(NAME) ?: slug
        val act = activity ?: return
        act.onBackPressed()
        act.window.decorView.postDelayed({ act.loadPage(slug, name) }, 250)
    }

    private fun onDataLoaded(data: NekomoApi.Companion.AnimePageNewData?) {
        episodeOffset = if (data?.episodes?.filter { it.episode == "0" }.isNullOrEmpty()) 0 else -1

        main {
            if (data == null) {
                showLoadError("Couldn't load this title. It may be unavailable, or your connection or the metadata service is down.")
                return@main
            }
            loadHandler.removeCallbacksAndMessages(null)
            val fadeAnimation = AlphaAnimation(1f, 0f)
            fadeAnimation.duration = 100
            fadeAnimation.isFillEnabled = true
            fadeAnimation.fillAfter = true
            fv<android.widget.FrameLayout>(R.id.loading_overlay)?.startAnimation(fadeAnimation)
            fv<android.widget.FrameLayout>(R.id.loading_overlay)?.postDelayed({
                // fully remove the overlay so it can never block scrolling or taps on the page underneath
                fv<android.widget.FrameLayout>(R.id.loading_overlay)?.apply { clearAnimation(); visibility = GONE; isClickable = false }
            }, 150)
            fv<android.widget.Button>(R.id.open_website_btt)?.isEnabled = false
            loadSeason()
            // Nekomo bundles no streaming source: an AniList title has no episodes until the user
            // picks one of the sources they installed themselves.
            val needsSource = data.episodes.none { it.ext != "meta" } && !com.lagradost.shiro.utils.isExtensionSlug(data.anime.slug)
            setupSourceDropdown(data)
            fv<android.widget.TextView>(R.id.trailer_btt)?.apply {
                val token = data.anime.trailer
                visibility = if (token.isNotBlank() && com.lagradost.shiro.utils.Trailers.webUrl(token) != null) VISIBLE else GONE
                setOnClickListener { activity?.let { com.lagradost.shiro.utils.Trailers.show(it, data.anime.title, token) } }
            }
            fv<android.widget.TextView>(R.id.source_hint)?.apply {
                visibility = if (needsSource) VISIBLE else GONE
                val hasEpisodes = data.episodes.isNotEmpty()
                val hasSources = com.lagradost.shiro.utils.SourceState.hasAny(context)
                text = when {
                    hasEpisodes && hasSources -> "Tap an episode to play it or mark it watched. To stream, pick a source in the dropdown or tap here."
                    hasEpisodes -> "Tracker mode: tap an episode to mark it watched. Add a source in Settings > Extensions if you also want to stream."
                    else -> "No episodes yet. Tap here (or the cloud icon) to find this title in the sources you installed."
                }
                setOnClickListener { showSourceChooser() }
            }

            data class IdObject(
                @JsonProperty("mal") val mal: String?,
            )

            if (!hasLoadedAnilist && (hasAniList || hasMAL)) {
                thread {
                    hasLoadedAnilist = true
                    val idMal = data.anime.ids?.takeIf { it.isNotBlank() }?.let { runCatching { mapper.readValue<IdObject?>(it) }.getOrNull() }?.mal

                    anilistPage =
                        getShowId(idMal, data.anime.title, data.anime.release_year?.toIntOrNull())
                    resultViewModel?.setAnilistId(anilistPage?.id ?: -1)
                    resultViewModel?.setMalId(idMal?.toIntOrNull() ?: anilistPage?.idMal ?: -1)
                }
            }

            // Somehow the above animation doesn't trigger sometimes on lower android versions
            thread {
                Timer().schedule(500) {
                    activity?.runOnUiThread {
                        fv<android.widget.FrameLayout>(R.id.loading_overlay)?.alpha = 0f
                        fv<android.widget.FrameLayout>(R.id.loading_overlay)?.visibility = GONE
                        fv<android.widget.Button>(R.id.open_website_btt)?.isEnabled = false
                    }
                }
            }

            val glideUrl =
                getFullUrlCdn(data.anime.poster)

            data class TrailerObject(
                @JsonProperty("name") val name: String?,
                @JsonProperty("size") val size: Int?,
                @JsonProperty("youtube") val youtube: String?,
            )
            normalSafeApiCall {
                val youtubeId = data.anime.trailer?.takeIf { it.isNotBlank() }?.let { runCatching { mapper.readValue<List<TrailerObject?>?>(it) }.getOrNull() }
                    ?.firstOrNull { it?.youtube != null }?.youtube

                fv<android.widget.ImageView>(R.id.banner_play_btt)?.isVisible = youtubeId != null
                if (youtubeId != null) {
                    fv<android.view.View>(R.id.spacer)?.setOnClickListener {
                        val appIntent = Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:$youtubeId"))
                        val webIntent = Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://www.youtube.com/watch?v=$youtubeId")
                        )
                        try {
                            context?.startActivity(appIntent)
                        } catch (ex: ActivityNotFoundException) {
                            context?.startActivity(webIntent)
                        }
                    }
                }
            }
            context.notNull { context ->
                val settingsManager = PreferenceManager.getDefaultSharedPreferences(context)
                val savingData = settingsManager.getBoolean("data_saving", false)
                fv<android.widget.ImageView>(R.id.title_background)?.let {
                    GlideApp.with(context)
                        .load(glideUrl)
                        .transition(DrawableTransitionOptions.withCrossFade(200))
                        .onlyRetrieveFromCache(savingData)
                        .into(it)
                }
                fv<android.widget.ImageView>(R.id.results_banner)?.let {
                    GlideApp.with(context)
                        .load(getFullUrlCdn(data.anime.banner))
                        .transform(com.lagradost.shiro.utils.BlurTransform())
                        .transition(DrawableTransitionOptions.withCrossFade(200))
                        .onlyRetrieveFromCache(savingData)
                        .into(it)
                }

            }

            // Kuro-style translucent banner: the title card lets the blurred backdrop show through.
            fv<androidx.cardview.widget.CardView>(R.id.site_data_card_view)?.setCardBackgroundColor(
                com.jaredrummler.cyanea.Cyanea.instance.backgroundColor and 0x00FFFFFF or (0xD9 shl 24)
            )

            if (data.episodes.isNotEmpty()) {
                fv<android.widget.ImageView>(R.id.title_background)?.setOnClickListener {
                    activity?.let {
                        val lastNormal = it.getLatestSeenEpisode(data.dubbify(false))
                        val lastDubbed = it.getLatestSeenEpisode(data.dubbify(true))
                        val isEpisodeDubbed = lastDubbed.episodeIndex >= lastNormal.episodeIndex
                        val episode = if (isEpisodeDubbed) lastDubbed else lastNormal

                        val episodePos = it.getViewPosDur(data.anime.slug, episode.episodeIndex)
                        /** Removed because I think it's better to just always start when you last left,
                         * that way you don't need to go back and you can also re-watch to remember
                         */
                        /*val next = canPlayNextEpisode(data, episode.episodeIndex)
                        if (next.isFound && episodePos.viewstate) {
                            val pos = it.getViewPosDur(data.slug, episode.episodeIndex)
                            Toast.makeText(
                                it,
                                "Playing episode ${next.episodeIndex + 1 + episodeOffset}",
                                Toast.LENGTH_SHORT
                            )
                                .show()
                            it.loadPlayer(
                                next.episodeIndex,
                                pos.pos,
                                data,
                                resultViewModel?.currentAniListId?.value,
                                resultViewModel?.currentMalId?.value,
                                fillerEpisodes
                            )
                        } else {*/
                        Toast.makeText(
                            it,
                            "Playing episode ${episode.episodeIndex + 1 + episodeOffset}",
                            Toast.LENGTH_SHORT
                        )
                            .show()
                        it.loadPlayer(
                            episode.episodeIndex,
                            episodePos.pos,
                            data,
                            resultViewModel?.getAnilistId(),
                            resultViewModel?.getMalId(),
                            fillerEpisodes
                        )
                        //}
                    }
                }
            } else {
//                next_episode_btt?.visibility = GONE
            }


            val textColor = Integer.toHexString(getCurrentActivity()!!.getTextColor()).substring(2)
            val textColorGrey =
                Integer.toHexString(getCurrentActivity()!!.getTextColor(true)).substring(2)
            fv<android.widget.TextView>(R.id.title_status)?.text =
                Html.fromHtml(
                    "<font color=#${textColorGrey}>Status:</font><font color=#${textColor}> ${
                        data.anime.status
                    }</font>"/*,
                        FROM_HTML_MODE_COMPACT*/
                )
            isBookmarked = context?.containsKey(BOOKMARK_KEY, data.anime.slug) == true
            toggleHeartVisual(isBookmarked)
            fv<android.widget.TextView>(R.id.title_episodes)?.text =
                Html.fromHtml(
                    "<font color=#${textColorGrey}>Episodes:</font><font color=#${textColor}> ${
                        if (data.episodes.isNotEmpty()) data.episodes.size.toString()
                        else data.anime.episodes.takeIf { it.isNotBlank() && it != "0" } ?: "?"
                    }</font>"/*,
                        FROM_HTML_MODE_COMPACT*/
                )

            if (data.anime.release_year != null) {
                fv<android.widget.TextView>(R.id.title_year)?.text =
                    Html.fromHtml(
                        "<font color=#${textColorGrey}>Year:</font><font color=#${textColor}> ${data.anime.release_year}</font>"/*,
                            FROM_HTML_MODE_COMPACT*/
                    )
            } else {
                fv<android.widget.TextView>(R.id.title_year)?.visibility = GONE
            }

            normalSafeApiCall {
                val a = data.anime
                val score = a.score.toDoubleOrNull()?.takeIf { it > 0 }?.let { if (it > 10) it / 10 else it }
                fv<android.widget.TextView>(R.id.title_rating)?.apply {
                    visibility = if (score == null) GONE else VISIBLE
                    text = score?.let { "★ %.1f / 10".format(it) } ?: ""
                }
                val rows = listOf(
                    "Format" to a.format,
                    "Duration" to a.episode_duration.takeIf { it.isNotBlank() && it != "0" }?.let { "$it min per episode" },
                    "Season" to listOf(a.season, a.release_year ?: "").filter { it.isNotBlank() }.joinToString(" "),
                    "Studio" to a.studios,
                    "Aired" to a.aired,
                ).filter { !it.second.isNullOrBlank() }
                fv<android.widget.TextView>(R.id.title_info)?.apply {
                    visibility = if (rows.isEmpty()) GONE else VISIBLE
                    text = Html.fromHtml(rows.joinToString("<br>") {
                        "<font color=#${textColorGrey}>${it.first}:</font><font color=#${textColor}> ${it.second}</font>"
                    })
                }
            }
            normalSafeApiCall {
                fv<android.widget.TextView>(R.id.title_genres)?.text =
                    Html.fromHtml(
                        "<font color=#${textColorGrey}>Genres:</font><font color=#${textColor}> ${
                            parseGenres(data.anime.genres)
                                .joinToString(prefix = "", postfix = "", separator = " • ")
                        }</font>"/*,
                            FROM_HTML_MODE_COMPACT*/
                    )
            }

            displayDate()

            fv<android.widget.TextView>(R.id.title_name)?.text = data.anime.title
            val fullDescription = data.anime.synopsis
                .replace("<br>", "")
                .replace("<i>", "")
                .replace("</i>", "")
                .replace("\n", " ")


            fv<android.widget.ImageView>(R.id.share_btt)?.setOnClickListener {
                val intent = Intent()
                intent.action = Intent.ACTION_SEND
                intent.putExtra(Intent.EXTRA_TEXT, "${data.anime.title}\nnekomo-play://play?slug=${data.anime.slug}")
                intent.type = "text/plain"
                startActivity(Intent.createChooser(intent, "Share To:"))
            }

            fv<android.widget.ImageView>(R.id.subscribe_btt)?.let { btt ->
                val slug = data.anime.slug
                btt.isVisible = true
                val subbedBookmark = context?.getKey<BookmarkedTitle>(SUBSCRIPTIONS_BOOKMARK_KEY, slug, null)
                val isSubbedOld = context?.getKey(SUBSCRIPTIONS_KEY, slug, false) == true
                val isSubbed = isSubbedOld || subbedBookmark != null

                val drawable =
                    if (isSubbed) R.drawable.ic_baseline_notifications_active_24 else R.drawable.ic_baseline_notifications_none_24
                btt.setImageResource(drawable)
                btt.setOnClickListener {
                    val subbedBookmark = context?.getKey<BookmarkedTitle>(SUBSCRIPTIONS_BOOKMARK_KEY, slug, null)
                    val isSubbedOld = context?.getKey(SUBSCRIPTIONS_KEY, slug, false) == true
                    val isSubbed = isSubbedOld || subbedBookmark != null

                    if (isSubbed) {
                        fv<android.widget.ImageView>(R.id.subscribe_btt)?.setImageResource(R.drawable.ic_baseline_notifications_none_24)
                        context?.removeKey(SUBSCRIPTIONS_BOOKMARK_KEY, slug)
                        context?.removeKey(SUBSCRIPTIONS_BOOKMARK_KEY, slug.replace("-dub", "-dubbed"))
                        context?.removeKey(SUBSCRIPTIONS_KEY, slug)
                        context?.removeKey(SUBSCRIPTIONS_KEY, slug.replace("-dub", "-dubbed"))
                        thread {
                            homeViewModel?.subscribed?.postValue(context?.getSubbed())
                        }
                        context?.let {
                            Toast.makeText(it, "Unsubscribed to ${data.anime.title}", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        fv<android.widget.ImageView>(R.id.subscribe_btt)?.setImageResource(R.drawable.ic_baseline_notifications_active_24)
                        context?.setKey(
                            SUBSCRIPTIONS_BOOKMARK_KEY, slug, BookmarkedTitle(
                                data.anime.title,
                                data.anime.poster,
                                data.anime.slug,
                                data.anime.poster
                            )
                        )
                        thread {
                            homeViewModel?.subscribed?.postValue(context?.getSubbed())
                        }
                        context?.let {
                            Toast.makeText(it, "Subscribed to ${data.anime.title}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                // Long-press on a subscribed show mutes/unmutes its episode alerts.
                btt.setOnLongClickListener {
                    val c = context ?: return@setOnLongClickListener true
                    val subscribed = c.getKey<BookmarkedTitle>(SUBSCRIPTIONS_BOOKMARK_KEY, slug, null) != null ||
                            c.getKey(SUBSCRIPTIONS_KEY, slug, false) == true
                    if (!subscribed) {
                        Toast.makeText(c, "Subscribe first, then long-press to mute alerts", Toast.LENGTH_SHORT).show()
                        return@setOnLongClickListener true
                    }
                    val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(c)
                    val muted = prefs.getStringSet("episode_notify_muted", emptySet())!!.toMutableSet()
                    val nowMuted = muted.add(slug).also { if (!it) muted.remove(slug) }
                    prefs.edit().putStringSet("episode_notify_muted", muted).apply()
                    Toast.makeText(
                        c,
                        if (nowMuted) "Episode alerts muted for ${data.anime.title}" else "Episode alerts on for ${data.anime.title}",
                        Toast.LENGTH_SHORT
                    ).show()
                    true
                }
            }
            fv<com.google.android.material.textview.MaterialTextView>(R.id.title_descript)?.text =
                fullDescription.substring(0, minOf(DESCRIPTION_LENGTH1 - 3, fullDescription.length)) + "..."
            fv<com.google.android.material.textview.MaterialTextView>(R.id.title_descript)?.setOnClickListener {
                val builder: AlertDialog.Builder =
                    AlertDialog.Builder(guaranteedContext(context), R.style.AlertDialogCustom)
                builder.setMessage(fullDescription).setTitle("Synopsis")
                    .show()
            }
        }

    }

    private fun displayDate() {
        val textColor = Integer.toHexString(getCurrentActivity()!!.getTextColor()).substring(2)
        val textColorGrey =
            Integer.toHexString(getCurrentActivity()!!.getTextColor(true)).substring(2)
        if (anilistPage?.nextAiringEpisode != null) {
            anilistPage?.nextAiringEpisode?.let { airingEpisode ->
                fv<android.widget.TextView>(R.id.title_day_of_week)?.visibility = VISIBLE
                fv<android.widget.TextView>(R.id.title_day_of_week)?.text =
                    Html.fromHtml(
                        "<font color=#${textColorGrey}>Schedule:</font><font color=#${textColor}> ${
                            secondsToReadable(airingEpisode.timeUntilAiring, "Now")
                        }</font>"/*,
                            FROM_HTML_MODE_COMPACT*/
                    )
            }
        } else {
            fv<android.widget.TextView>(R.id.title_day_of_week)?.visibility = GONE
        }
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        resultViewModel =
            ViewModelProvider(this).get(ResultsViewModel::class.java)
        publicResultViewModel = resultViewModel

        arguments?.getString(SLUG)?.let { primarySlug ->
            val isMalId = arguments?.getBoolean(IS_MAL_ID) == true
            resultViewModel?.loadData(context, primarySlug, isMalId)
        }
    }

    private fun toggleHeartVisual(_isBookmarked: Boolean) {
        if (_isBookmarked) {
            fv<android.widget.ImageView>(R.id.bookmark_btt)?.setImageResource(R.drawable.filled_heart)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                fv<android.widget.ImageView>(R.id.bookmark_btt)?.imageTintList = ColorStateList.valueOf(Cyanea.instance.primary)
            }
        } else {
            fv<android.widget.ImageView>(R.id.bookmark_btt)?.setImageResource(R.drawable.outlined_heart)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                fv<android.widget.ImageView>(R.id.bookmark_btt)?.imageTintList =
                    ColorStateList.valueOf(getCurrentActivity()!!.getColorFromAttr(R.attr.white))
            }
        }
    }

    private fun Context.toggleHeart(_isBookmarked: Boolean) {
        this@ResultFragment.isBookmarked = _isBookmarked
        toggleHeartVisual(_isBookmarked)
        val data = resultViewModel?.data?.value ?: return
        /*Saving the new bookmark in the database*/
        if (_isBookmarked) {
            setKey(
                BOOKMARK_KEY,
                data.anime.slug,
                BookmarkedTitle(
                    data.anime.title,
                    data.anime.poster,
                    data.anime.slug,
                    data.anime.title_english
                )
            )
        } else {
            removeKey(BOOKMARK_KEY, data.anime.slug)
            removeKey(BOOKMARK_KEY, data.anime.slug.replace("-dub", "-dubbed"))
        }
        thread {
            homeViewModel?.favorites?.postValue(getFav())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        if (savedInstanceState != null) {
//            slug = savedInstanceState.getString(SLUG)
//            isMalId = savedInstanceState.getBoolean(IS_MAL_ID)
//            resultViewModel?.slug?.postValue(slug)
        }
//        postponeEnterTransition()
//        sharedElementEnterTransition = TransitionInflater.from(context)
//            .inflateTransition(R.transition.shared_exit)
//        sharedElementReturnTransition = TransitionInflater.from(context)
//            .inflateTransition(R.transition.shared_exit)
        super.onCreate(savedInstanceState)
    }

    override fun onSaveInstanceState(outState: Bundle) {
//        outState.putString(SLUG, slug)
//        outState.putBoolean(IS_MAL_ID, isMalId == true)
        super.onSaveInstanceState(outState)
    }

    private fun handleAction(episodeClick: EpisodeClickEvent): Job = main {
        val index = episodeClick.data.episode
        val clickData = episodeClick.data.data ?: return@main
        if (clickData.episodes.getOrNull(index)?.ext == "meta") {
            showMetaEpisodeMenu(clickData, index, episodeClick.action == ACTION_CLICK_LONG)
            return@main
        }
        var currentLinks: List<ExtractorLink>? = null
        currentLoadingCount++


        suspend fun requireLinks(isCasting: Boolean): Boolean {
            val currentLinksTemp =
                if (allEpisodes.containsKey(episodeClick.data.id)) allEpisodes[episodeClick.data.id] else null
            if (currentLinksTemp != null && currentLinksTemp.size > 0) {
                currentLinks = currentLinksTemp
                return true
            }

            val currentLoad = currentLoadingCount
            val builder = AlertDialog.Builder(requireContext(), R.style.AlertDialogCustomTransparent)
            val customLayout = layoutInflater.inflate(R.layout.dialog_loading, null)
            builder.setView(customLayout)

            val loadingDialog = builder.create()
            loadingDialog.show()
            loadingDialog.setOnDismissListener {
                currentLoadingCount++
            }

            val episodes = clickData.episodes.getOrNull(index)?.sources?.let {
                mapper.readValue<List<NekomoApi.Companion.EpisodeObject?>?>(it)
            }
            val videoId = episodes?.firstOrNull { it?.slug == "gogostream" }
            val sources = safeApiCall {
                videoId?.source?.let { source ->
                    getVideoLink(
                        source, isCasting = isCasting
                    )
                }
            }
            loadingDialog.dismiss()

            when (sources) {
                is Resource.Success -> {
                    currentLinks = sources.value
                    return true
                }
                is Resource.Failure -> {
                    Toast.makeText(requireContext(), R.string.error_loading_links, Toast.LENGTH_SHORT).show()
                }
                else -> {

                }
            }

            if (currentLoadingCount != currentLoad) return false
            return false
        }

        fun startDownload(links: List<ExtractorLink>) {
            activity?.let {
                DownloadManager.downloadEpisodeUsingWorker(
                    it,
                    DownloadManager.DownloadInfo(
                        index,
                        clickData,
                        resultViewModel?.getAnilistId(),
                        resultViewModel?.getMalId(),
                        fillerEpisodes
                    ),
                    links
                )
            }
        }

        val isLoaded = when (episodeClick.action) {
            ACTION_PLAY_EPISODE_IN_PLAYER -> true
            ACTION_CLICK_DEFAULT -> true
            ACTION_CLICK_LONG -> true
            ACTION_CHROME_CAST_EPISODE -> requireLinks(true)
            ACTION_CHROME_CAST_MIRROR -> requireLinks(true)
            else -> requireLinks(false)
        }
        if (!isLoaded) return@main // CANT LOAD

        when (episodeClick.action) {
            ACTION_DOWNLOAD_EPISODE -> {
                if (settingsManager?.getBoolean("pick_downloads", false) == true) {
                    currentLinks?.filter { !it.isM3u8 }?.let {
                        if (it.isNullOrEmpty()) {
                            Toast.makeText(
                                guaranteedContext(context),
                                "No downloadable links found :(",
                                Toast.LENGTH_SHORT
                            ).show()
                            return@main
                        }
                        val arrayAdapter =
                            ArrayAdapter<String>(guaranteedContext(context), R.layout.bottom_single_choice)
                        val sourcesText = it.map { link -> link.name }
                        arrayAdapter.addAll(ArrayList(sourcesText))
                        val bottomSheetDialog =
                            BottomSheetDialog(guaranteedContext(context), R.style.AppBottomSheetDialogTheme)
                        bottomSheetDialog.setContentView(R.layout.bottom_sheet)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            bottomSheetDialog.fv<androidx.cardview.widget.CardView>(R.id.bottom_sheet_top_bar).backgroundTintList =
                                ColorStateList.valueOf(Cyanea.instance.backgroundColorDark)
                        }

                        val res = bottomSheetDialog.fv<android.widget.ListView>(R.id.sort_click)
                        res.choiceMode = CHOICE_MODE_SINGLE
                        res.adapter = arrayAdapter
                        res.setItemChecked(
                            0,
                            true
                        )
                        res.setOnItemClickListener { _, _, position, _ ->
                            startDownload(listOf(it[position]))
                            bottomSheetDialog.dismiss()
                        }
                        bottomSheetDialog.fv<android.widget.TextView>(R.id.main_text)?.text = "Select source"
                        bottomSheetDialog.setOnShowListener {
                            normalSafeApiCall {
                                BottomSheetBehavior.from(bottomSheetDialog.fv<android.widget.LinearLayout>(R.id.bottom_sheet_root).parent as View).peekHeight =
                                    bottomSheetDialog.fv<android.widget.LinearLayout>(R.id.bottom_sheet_root).height
                            }
                        }
                        bottomSheetDialog.show()

                    }


                } else {
                    startDownload(currentLinks ?: return@main)
                }

            }
            ACTION_CLICK_DEFAULT -> {
                if (guaranteedContext(context).isCastApiAvailable() &&
                    CastContext.getSharedInstance(guaranteedContext(context)).castState == CastState.CONNECTED
                ) {
                    handleAction(
                        EpisodeClickEvent(
                            ACTION_CHROME_CAST_EPISODE,
                            episodeClick.data,
                            episodeClick.adapterPosition
                        )
                    )
                } else {
                    handleAction(
                        EpisodeClickEvent(
                            ACTION_PLAY_EPISODE_IN_PLAYER,
                            episodeClick.data,
                            episodeClick.adapterPosition
                        )
                    )
                }
            }

            ACTION_CHROME_CAST_EPISODE -> {
                currentLinks?.let {
                    guaranteedContext(context).castEpisode(
                        it,
                        NekomoApi.CommonAnimePageData(
                            clickData.anime.title,
                            clickData.anime.poster,
                            clickData.anime.slug,
                            clickData.anime.title_english
                        ),
                        index
                    )
                }
            }

            ACTION_PLAY_EPISODE_IN_PLAYER -> {
                activity?.loadPlayer(
                    index,
                    0L,
                    clickData,
                    resultViewModel?.getAnilistId(),
                    resultViewModel?.getMalId(),
                    fillerEpisodes
                )
            }

            ACTION_CLICK_LONG -> {
                with(guaranteedContext(context)) {
                    val key = getViewKey(clickData.anime.slug, index)

                    val keyNormal = getViewKey(clickData.anime.slug.dubbify(false), index)
                    val keyDubbed = getViewKey(clickData.anime.slug.dubbify(true), index)

                    if (containsKey(VIEWSTATE_KEY, keyNormal) || containsKey(
                            VIEWSTATE_KEY,
                            keyDubbed
                        )
                    ) {
                        removeKey(VIEWSTATE_KEY, keyNormal)
                        removeKey(VIEWSTATE_KEY, keyDubbed)
                    } else {
                        setKey(VIEWSTATE_KEY, key, System.currentTimeMillis())
                    }
                    // Hack, but works
                    (activity?.findViewById<RecyclerView>(R.id.episodes_res_view)?.adapter as? MasterEpisodeAdapter)?.notifyItemChanged(
                        episodeClick.adapterPosition
                    )
                }
            }
        }
    }


    private fun loadSeason() {
        view.notNull {
            it.layoutParams = it.layoutParams.apply {
                height = it.rootView?.height ?: it.height
            }
        }
        settingsManager!!.getBoolean("save_history", true)
        val data = resultViewModel?.data?.value
        if (data?.episodes?.isNotEmpty() == true) {
            if (fv<androidx.recyclerview.widget.RecyclerView>(R.id.episodes_res_view)?.adapter == null) {
                val adapter: RecyclerView.Adapter<RecyclerView.ViewHolder>? = activity?.let {
                    MasterEpisodeAdapter(
                        it,
                        data,
                        fillerEpisodes,
                        { episodeClick ->
                            handleAction(episodeClick)
                        },
                        { downloadClickEvent ->
                            handleDownloadClick(
                                activity,
                                downloadClickEvent,
                                resultViewModel?.getAnilistId(),
                                resultViewModel?.getMalId()
                            )
                        }
                    )
                }
                fv<androidx.recyclerview.widget.RecyclerView>(R.id.episodes_res_view)?.adapter = adapter
                (fv<androidx.recyclerview.widget.RecyclerView>(R.id.episodes_res_view)?.adapter as? MasterEpisodeAdapter)?.notifyDataSetChanged()
            } else {
                (fv<androidx.recyclerview.widget.RecyclerView>(R.id.episodes_res_view)?.adapter as? MasterEpisodeAdapter)?.data = data
                (fv<androidx.recyclerview.widget.RecyclerView>(R.id.episodes_res_view)?.adapter as? MasterEpisodeAdapter)?.items =
                    context?.generateItems(data.episodes, data.anime.slug) ?: mutableListOf()
                (fv<androidx.recyclerview.widget.RecyclerView>(R.id.episodes_res_view)?.adapter as? MasterEpisodeAdapter)?.isFiller = fillerEpisodes
                (fv<androidx.recyclerview.widget.RecyclerView>(R.id.episodes_res_view)?.adapter as? MasterEpisodeAdapter)?.notifyDataSetChanged()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isInResults = false
        hasLoadedAnilist = false
        activity?.transparentStatusAndNavigation()
        onPlayerNavigated -= ::handleVideoPlayerNavigation
        // DownloadManager.downloadStartEvent -= ::onDownloadStarted
    }

    private fun handleVideoPlayerNavigation(hasEntered: Boolean) {
        if (hasEntered) {
            this.view?.visibility = GONE
        } else {
            this.view?.visibility = VISIBLE
            (fv<androidx.recyclerview.widget.RecyclerView>(R.id.episodes_res_view)?.adapter as? MasterEpisodeAdapter)?.notifyDataSetChanged()
        }
    }

    private fun onDownloadStarted(id: String) {
        activity?.runOnUiThread {
            // Cast/view-lookup failures when the fragment's view is already gone (e.g. user
            // navigated away while a download event fires) shouldn't crash the app.
            try {
                (fv<androidx.recyclerview.widget.RecyclerView>(R.id.episodes_res_view)?.adapter as? MasterEpisodeAdapter)?.notifyDataSetChanged()
            } catch (e: Exception) {
            }
        }
    }

    private var currentLoadingCount = 0 // THIS IS USED TO PREVENT LATE EVENTS, AFTER DISMISS WAS CLICKED
    private var allEpisodes: HashMap<Int, ArrayList<ExtractorLink>> = HashMap()

    // CastContext is a process-wide singleton; keep a reference to the listener we register on
    // it so it can be unregistered when the fragment's view is destroyed instead of leaking.
    private var castContext: CastContext? = null
    private var castStateListener: CastStateListener? = null

    override fun onDestroyView() {
        loadHandler.removeCallbacksAndMessages(null)
        castStateListener?.let { listener -> castContext?.removeCastStateListener(listener) }
        castStateListener = null
        castContext = null
        super.onDestroyView()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        startLoadingStatus()
        super.onViewCreated(view, savedInstanceState)
        // ORIENTATION_PORTRAIT not working for some reason so I chose this
        /*if (!settingsManager!!.getBoolean("force_landscape", false)) {
            val navBarSize = getCurrentActivity()!!.getNavigationBarSize()
            val min = minOf(navBarSize.y, navBarSize.x)
            fragments_new_nav_view?.setPadding(0, 0, 0, min)
        }*/
        activity?.showNavigation()

        fv<android.widget.ImageView>(R.id.go_back_btt)?.setOnClickListener {
            activity?.onBackPressed()
        }
//        val displayMetrics = DisplayMetrics()

//        Handler().postDelayed({
//            startPostponedEnterTransition()
//        }, 50L)
//        fragments_new_nav_view?.background = ColorDrawable(Cyanea.instance.backgroundColor)
//        result_poster_blur?.background = ColorDrawable(Cyanea.instance.backgroundColor)
        fv<android.widget.HorizontalScrollView>(R.id.fragment_results_toolbar)?.background = ColorDrawable(Cyanea.instance.backgroundColor)
        fv<androidx.core.widget.NestedScrollView>(R.id.fragment_results_nested_scrollview)?.background = ColorDrawable(Cyanea.instance.backgroundColor)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            fv<androidx.cardview.widget.CardView>(R.id.site_data_card_view)?.backgroundTintList = ColorStateList.valueOf(Cyanea.instance.backgroundColorDark)
            fv<androidx.cardview.widget.CardView>(R.id.title_holder)?.backgroundTintList = ColorStateList.valueOf(Cyanea.instance.backgroundColor)
            fv<androidx.core.widget.ContentLoadingProgressBar>(R.id.sync_progressbar)?.progressTintList = ColorStateList.valueOf(Cyanea.instance.accent)
        }
        fv<android.widget.FrameLayout>(R.id.loading_overlay)?.background = ColorDrawable(Cyanea.instance.backgroundColor)
        fv<androidx.cardview.widget.CardView>(R.id.episodes_res_view_holder)?.backgroundTintList = ColorStateList.valueOf(Cyanea.instance.backgroundColorDark)
        fv<android.widget.LinearLayout>(R.id.card_spacer)?.background = ColorDrawable(Cyanea.instance.backgroundColor)

        hideKeyboard()
        //title_duration.text = data!!.duration.toString() + "min"
        if (activity?.isCastApiAvailable() == true) {
            val mMediaRouteButton = view.findViewById<MediaRouteButton>(R.id.media_route_button)

            CastButtonFactory.setUpMediaRouteButton(requireContext(), mMediaRouteButton)
            val castContext = CastContext.getSharedInstance(requireActivity().applicationContext)

            if (castContext.castState != CastState.NO_DEVICES_AVAILABLE) fv<androidx.mediarouter.app.MediaRouteButton>(R.id.media_route_button)?.visibility = VISIBLE

            this.castContext = castContext
            castStateListener = CastStateListener { state ->
                // CastContext is a process-wide singleton, so this listener can still fire
                // after this fragment's view has been destroyed - guard against that instead
                // of crashing the whole app.
                try {
                    val button = fv<androidx.mediarouter.app.MediaRouteButton>(R.id.media_route_button) ?: return@CastStateListener
                    button.visibility = if (state == CastState.NO_DEVICES_AVAILABLE) GONE else VISIBLE
                } catch (e: Exception) {
                }
            }.also { castContext.addCastStateListener(it) }
        }



        fv<android.widget.TextView>(R.id.anilist_login_btt)?.setOnClickListener {
            guaranteedContext(context).authenticateAniList()
        }
        fv<android.widget.TextView>(R.id.mal_login_btt)?.setOnClickListener {
            guaranteedContext(context).authenticateMAL()
        }

        observe(resultViewModel!!.totalAniListId) { id ->
            fv<android.widget.TextView>(R.id.anilist_btt)?.isVisible = id != null
            fv<android.widget.TextView>(R.id.anilist_btt)?.setOnClickListener {
                activity?.openBrowser("https://anilist.co/anime/$it")
            }
        }
        observe(resultViewModel!!.totalMalId) { id ->
            fv<android.widget.TextView>(R.id.mal_btt)?.isVisible = id != null
            fv<android.widget.TextView>(R.id.mal_btt)?.setOnClickListener {
                activity?.openBrowser("https://myanimelist.net/anime/$id")
            }
        }

        fv<android.widget.ImageView>(R.id.language_btt)?.setOnClickListener { showGlobeMenu() }

        fv<android.widget.ImageView>(R.id.source_btt)?.setOnClickListener { showSourceChooser() }

        observe(resultViewModel!!.hasLoadedOther) {
            val transition: Transition = ChangeBounds()
            transition.duration = 100
            fv<android.widget.ImageView>(R.id.language_btt)?.isVisible = true
            fv<android.widget.HorizontalScrollView>(R.id.fragment_results_toolbar)?.let {
                TransitionManager.beginDelayedTransition(it, transition)
            }
        }

        fv<android.widget.TextView>(R.id.relatedTextView)?.isVisible = resultViewModel?.related?.value?.isNullOrEmpty() == false

        observe(resultViewModel!!.related) {
            val commonData = it?.map {
                NekomoApi.CommonAnimePageData(
                    it.title,
                    it.poster,
                    it.idMal.toString(),
                )
            }
            fv<android.widget.TextView>(R.id.relatedTextView)?.isVisible = commonData?.isNullOrEmpty() == false
            activity?.runOnUiThread {
                fv<com.lagradost.shiro.ui.AutofitRecyclerView>(R.id.relatedScrollView).spanCount = 3

                val adapter: RecyclerView.Adapter<RecyclerView.ViewHolder> =
                    ResAdapter(
                        ArrayList(commonData),
                        fv<com.lagradost.shiro.ui.AutofitRecyclerView>(R.id.relatedScrollView),
                        true,
                        forceDisableCompact = true
                    )

                fv<com.lagradost.shiro.ui.AutofitRecyclerView>(R.id.relatedScrollView)?.adapter = adapter
                fv<com.lagradost.shiro.ui.AutofitRecyclerView>(R.id.relatedScrollView)?.adapter?.notifyDataSetChanged()
            }
        }

        observe(resultViewModel!!.recommendations) {
            val commonData = it?.map {
                NekomoApi.CommonAnimePageData(
                    it.title,
                    it.poster,
                    it.idMal.toString(),
                )
            }
            activity?.runOnUiThread {
                fv<com.lagradost.shiro.ui.AutofitRecyclerView>(R.id.recommendationsScrollView).spanCount = 3

                val adapter: RecyclerView.Adapter<RecyclerView.ViewHolder> =
                    ResAdapter(
                        ArrayList(commonData),
                        fv<com.lagradost.shiro.ui.AutofitRecyclerView>(R.id.recommendationsScrollView),
                        true,
                        forceDisableCompact = true
                    )

                fv<com.lagradost.shiro.ui.AutofitRecyclerView>(R.id.recommendationsScrollView)?.adapter = adapter
                (fv<com.lagradost.shiro.ui.AutofitRecyclerView>(R.id.recommendationsScrollView)?.adapter as? ResAdapter)?.notifyDataSetChanged()
            }
        }

        fun getEpisodeMax(): Int {
            return if ((resultViewModel?.localData?.value?.episodes
                    ?: 0) == 0
            ) 10000 else resultViewModel?.localData?.value?.episodes ?: 10000
        }

        class SyncButtonClickListener(val buttonStatus: Int) : View.OnClickListener {
            override fun onClick(view: View?) {
                resultViewModel?.localData.notNull {
                    it.postValue(it.value?.apply {
                        status = buttonStatus
                    })
                }
            }
        }

        fv<android.widget.TextView>(R.id.sync_btt)?.setOnClickListener {
            val number =
                if (fv<com.google.android.material.textfield.TextInputEditText>(R.id.number_picker_episode_text)?.text.toString().toIntOrNull() == null
                ) 1 else minOf(
                    fv<com.google.android.material.textfield.TextInputEditText>(R.id.number_picker_episode_text)?.text.toString().toInt(),
                    getEpisodeMax()
                )
            val data = resultViewModel?.localData?.value?.apply {
                progress = number
            }
            resultViewModel?.syncLocalData(guaranteedContext(context), data)
        }

        fun postEpisode(episodeNum: Int) {
            resultViewModel?.localData.notNull {
                it.postValue(it.value?.apply {
                    status = when {
                        episodes != 0 && episodeNum != episodes && fromIntToAnimeStatus(status)
                                == AniListApi.Companion.AniListStatusType.Completed -> AniListApi.Companion.AniListStatusType.Watching.value
                        episodes != 0 && episodeNum == episodes && fromIntToAnimeStatus(status)
                                != AniListApi.Companion.AniListStatusType.Completed -> AniListApi.Companion.AniListStatusType.Completed.value
                        else -> status
                    }
                    progress = episodeNum
                })
            }
        }

        fv<android.widget.ImageView>(R.id.number_picker_episode_up)?.setOnClickListener {
            val number =
                if (fv<com.google.android.material.textfield.TextInputEditText>(R.id.number_picker_episode_text)?.text.toString().toIntOrNull() == null
                ) 1 else minOf(
                    fv<com.google.android.material.textfield.TextInputEditText>(R.id.number_picker_episode_text)?.text.toString().toInt() + 1,
                    getEpisodeMax()
                )
            postEpisode(number)
        }
        fv<android.widget.ImageView>(R.id.number_picker_episode_down).setOnClickListener {
            val number =
                if (fv<com.google.android.material.textfield.TextInputEditText>(R.id.number_picker_episode_text)?.text.toString().toIntOrNull() == null
                ) 0 else minOf(
                    maxOf(
                        fv<com.google.android.material.textfield.TextInputEditText>(R.id.number_picker_episode_text)?.text.toString().toInt() - 1,
                        0
                    ), getEpisodeMax()
                )
            postEpisode(number)
        }

        fv<com.google.android.material.button.MaterialButton>(R.id.watching_btt)?.setOnClickListener(SyncButtonClickListener(0))
        fv<com.google.android.material.button.MaterialButton>(R.id.completed_btt)?.setOnClickListener(SyncButtonClickListener(1))
        fv<com.google.android.material.button.MaterialButton>(R.id.on_hold_btt)?.setOnClickListener(SyncButtonClickListener(2))
        fv<com.google.android.material.button.MaterialButton>(R.id.dropped_btt)?.setOnClickListener(SyncButtonClickListener(3))
        fv<com.google.android.material.button.MaterialButton>(R.id.plan_to_watch_btt)?.setOnClickListener(SyncButtonClickListener(4))
        fv<com.google.android.material.button.MaterialButton>(R.id.rewatching_btt)?.setOnClickListener(SyncButtonClickListener(5))


        resultViewModel?.hasFailed?.observe(viewLifecycleOwner) {
            if (it == true) showLoadError("Couldn't load this title. It may be unavailable, or your connection or the metadata service is down.")
        }

        observe(resultViewModel!!.data) {
            onDataLoaded(it)
        }

        fun getScoreText(score: Int): String {
            return when (score) {
                0 -> "0 - Unset"
                1 -> getString(R.string.rating_1)
                2 -> getString(R.string.rating_2)
                3 -> getString(R.string.rating_3)
                4 -> getString(R.string.rating_4)
                5 -> getString(R.string.rating_5)
                6 -> getString(R.string.rating_6)
                7 -> getString(R.string.rating_7)
                8 -> getString(R.string.rating_8)
                9 -> getString(R.string.rating_9)
                10 -> getString(R.string.rating_10)
                else -> ""
            }
        }

        fv<com.google.android.material.slider.Slider>(R.id.sync_score_slider)?.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                resultViewModel?.localData.notNull {
                    it.postValue(it.value?.apply {
                        score = value.toInt()
                    })
                }
            }
        }
        fv<com.google.android.material.slider.Slider>(R.id.sync_score_slider)?.setLabelFormatter { value: Float ->
            getScoreText(value.toInt())
        }

        fv<com.google.android.material.button.MaterialButton>(R.id.rewatching_btt)?.isVisible = hasAniList
        fv<android.widget.LinearLayout>(R.id.sync_page)?.isVisible = hasAniList || hasMAL
        fv<android.widget.LinearLayout>(R.id.sync_page_failed)?.isVisible = !hasAniList && !hasMAL
        fv<android.widget.TextView>(R.id.local_library_btt)?.setOnClickListener {
            val ctx = context ?: return@setOnClickListener
            val d = resultViewModel?.data?.value?.anime ?: return@setOnClickListener
            val current = com.lagradost.shiro.utils.LocalLibrary.statusOf(ctx, d.slug)
            val options = com.lagradost.shiro.utils.LocalLibrary.STATUSES + (if (current != null) listOf("Remove from library") else emptyList())
            androidx.appcompat.app.AlertDialog.Builder(ctx, R.style.AlertDialogCustom)
                .setTitle(if (current != null) "Offline library: $current" else "Add to offline library")
                .setItems(options.toTypedArray()) { _, which ->
                    if (which < com.lagradost.shiro.utils.LocalLibrary.STATUSES.size) {
                        com.lagradost.shiro.utils.LocalLibrary.set(ctx, d.slug, d.title, d.poster, options[which])
                        fv<android.widget.TextView>(R.id.local_library_btt)?.text = "In offline library: ${options[which]}"
                    } else {
                        com.lagradost.shiro.utils.LocalLibrary.remove(ctx, d.slug)
                        fv<android.widget.TextView>(R.id.local_library_btt)?.text = "Save to offline library"
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        resultViewModel?.data?.observe(viewLifecycleOwner) { d ->
            val ctx = context
            val st = if (d != null && ctx != null) com.lagradost.shiro.utils.LocalLibrary.statusOf(ctx, d.anime.slug) else null
            fv<android.widget.TextView>(R.id.local_library_btt)?.text = if (st != null) "In offline library: $st" else "Save to offline library"
        }

        observe(resultViewModel!!.syncData) {
            if (it == null) return@observe
            fv<android.widget.TextView>(R.id.sync_title_)?.text = it.title
            fv<android.widget.TextView>(R.id.sync_status_text)?.text = fromIntToAnimeStatus(it.status).name
            fv<android.widget.TextView>(R.id.sync_score)?.text = getScoreText(it.score)
            fv<androidx.core.widget.ContentLoadingProgressBar>(R.id.sync_progressbar)?.max = it.episodes

            val realProgress =
                if (fromIntToAnimeStatus(it.status) == AniListApi.Companion.AniListStatusType.Completed) it.episodes else it.progress

            val realEpisodesText = if (it.episodes == 0) "???" else it.episodes

            fv<android.widget.TextView>(R.id.sync_progress_txt)?.text = "${realProgress}/$realEpisodesText"
            fv<androidx.core.widget.ContentLoadingProgressBar>(R.id.sync_progressbar)?.progress = realProgress
        }
        observe(resultViewModel!!.localData) {
            if (it == null) return@observe
            fv<com.google.android.material.slider.Slider>(R.id.sync_score_slider)?.value = it.score.toFloat()
            val buttons = listOf<MaterialButton?>(
                fv<com.google.android.material.button.MaterialButton>(R.id.watching_btt),
                fv<com.google.android.material.button.MaterialButton>(R.id.completed_btt),
                fv<com.google.android.material.button.MaterialButton>(R.id.plan_to_watch_btt),
                fv<com.google.android.material.button.MaterialButton>(R.id.on_hold_btt),
                fv<com.google.android.material.button.MaterialButton>(R.id.dropped_btt),
                fv<com.google.android.material.button.MaterialButton>(R.id.rewatching_btt)
            )

            val view = when (fromIntToAnimeStatus(it.status)) {
                AniListApi.Companion.AniListStatusType.None -> null
                AniListApi.Companion.AniListStatusType.Watching -> buttons[0]
                AniListApi.Companion.AniListStatusType.Completed -> buttons[1]
                AniListApi.Companion.AniListStatusType.Planning -> buttons[2]
                AniListApi.Companion.AniListStatusType.Paused -> buttons[3]
                AniListApi.Companion.AniListStatusType.Dropped -> buttons[4]
                AniListApi.Companion.AniListStatusType.Rewatching -> buttons[5]
            }

            buttons.forEach {
                it?.backgroundTintList =
                    ColorStateList.valueOf(ContextCompat.getColor(guaranteedContext(context), R.color.transparent))
            }

            view?.backgroundTintList = ColorStateList.valueOf(
                Cyanea.instance.primaryLight
            )

            val realProgress =
                if (fromIntToAnimeStatus(it.status) == AniListApi.Companion.AniListStatusType.Completed && it.episodes != 0) it.episodes else it.progress
            fv<com.google.android.material.textfield.TextInputEditText>(R.id.number_picker_episode_text)?.setText(realProgress.toString())
        }

//        if (settingsManager?.getBoolean("hide_open_website", false) != true) {
//            observe(resultViewModel!!.slug) { slug ->
//                fv<android.widget.Button>(R.id.open_website_btt)?.visibility = VISIBLE
//                fv<android.widget.Button>(R.id.open_website_btt)?.setOnClickListener {
//                    guaranteedContext(context).openBrowser("********/${slug}")
//                }
//            }
//        }

        fv<android.widget.ImageView>(R.id.edit_button)?.isVisible = hasAniList || hasMAL
        fv<android.widget.ImageView>(R.id.edit_button)?.setOnClickListener {
            activity?.let { context ->
                val bottomSheetDialog = BottomSheetDialog(context, R.style.AppBottomSheetDialogTheme)
                bottomSheetDialog.setContentView(R.layout.fragment_results_edit_mal_id)

                bottomSheetDialog.fv<com.google.android.material.textfield.TextInputLayout>(R.id.anilist_id_text_holder).hint =
                    resultViewModel?.currentAniListId?.value?.toString()?.let { "Anilist ID: $it" } ?: "Anilist ID"
                bottomSheetDialog.fv<com.google.android.material.textfield.TextInputLayout>(R.id.mal_id_text_holder).hint =
                    resultViewModel?.currentMalId?.value?.toString()?.let { "Mal ID: $it" } ?: "Mal ID"

                resultViewModel?.overrideMalId?.value?.let {
                    bottomSheetDialog.fv<com.google.android.material.textfield.TextInputLayout>(R.id.mal_id_text_holder).editText?.setText(it.toString())
                }
                resultViewModel?.overrideAniListId?.value?.let {
                    bottomSheetDialog.fv<com.google.android.material.textfield.TextInputLayout>(R.id.anilist_id_text_holder).editText?.setText(it.toString())
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    bottomSheetDialog.fv<androidx.cardview.widget.CardView>(R.id.bottom_sheet_top_bar_mal).backgroundTintList =
                        ColorStateList.valueOf(Cyanea.instance.backgroundColorDark)
                }

                bottomSheetDialog.fv<android.widget.LinearLayout>(R.id.mal_id_selector_root).background = ColorDrawable(Cyanea.instance.backgroundColor)
                bottomSheetDialog.fv<com.google.android.material.button.MaterialButton>(R.id.copy_slug_btt).setOnClickListener {
                    val clipboard: ClipboardManager? =
                        it.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager?
                    val clip = ClipData.newPlainText("Slug", resultViewModel?.data?.value?.anime?.slug ?: "")
                    clipboard?.setPrimaryClip(clip)
                    Toast.makeText(it.context, "Slug copied to clipboard", Toast.LENGTH_SHORT).show()
                }

                bottomSheetDialog.fv<com.google.android.material.button.MaterialButton>(R.id.anilist_save_btt).setOnClickListener {
                    val id = bottomSheetDialog.fv<com.google.android.material.textfield.TextInputLayout>(R.id.anilist_id_text_holder).editText?.text?.toString()
                    val idNum = id?.toIntOrNull()
                    // Allow removal
                    if (id == "") {
                        resultViewModel?.data?.value?.anime?.slug?.let { slug ->
                            resultViewModel?.setAnilistIdOverride(null)
                            it.context.removeKey(RESULTS_PAGE_OVERRIDE_ANILIST, slug.removeSuffix("-dub"))
                        }
                    } else if (idNum != null) {
                        resultViewModel?.setAnilistIdOverride(idNum)
                        resultViewModel?.data?.value?.anime?.slug?.let { slug ->
                            it.context.setKey(RESULTS_PAGE_OVERRIDE_ANILIST, slug.removeSuffix("-dub"), idNum)
                        }
                    }
                }

                bottomSheetDialog.fv<com.google.android.material.button.MaterialButton>(R.id.mal_save_btt).setOnClickListener {
                    val id = bottomSheetDialog.fv<com.google.android.material.textfield.TextInputLayout>(R.id.mal_id_text_holder).editText?.text?.toString()
                    val idNum = id?.toIntOrNull()
                    if (id == "") {
                        resultViewModel?.data?.value?.anime?.slug?.let { slug ->
                            resultViewModel?.setMalIdOverride(null)
                            it.context.removeKey(RESULTS_PAGE_OVERRIDE_MAL, slug.removeSuffix("-dub"))
                        }
                    } else if (idNum != null) {
                        resultViewModel?.setMalIdOverride(idNum)
                        resultViewModel?.data?.value?.anime?.slug?.let { slug ->
                            it.context.setKey(RESULTS_PAGE_OVERRIDE_MAL, slug.removeSuffix("-dub"), idNum)
                        }
                    }
                }

                bottomSheetDialog.show()
            }
        }

        view.notNull {
            it.layoutParams = it.layoutParams.apply {
                height = it.rootView?.height ?: it.height
            }
        }

        fv<android.widget.ImageView>(R.id.bookmark_btt)?.setOnClickListener {
            context?.toggleHeart(!isBookmarked)
        }

        val localName = arguments?.getString(NAME)
        if (localName != null && settingsManager?.getBoolean("search_for_filler_episodes", true) == true) {
            thread {
                fillerEpisodes =
                    FillerEpisodeCheck.getFillerEpisodes(localName)
                activity?.runOnUiThread {
                    try {
                        if (fv<androidx.recyclerview.widget.RecyclerView>(R.id.episodes_res_view)?.adapter != null) {
                            (fv<androidx.recyclerview.widget.RecyclerView>(R.id.episodes_res_view).adapter as MasterEpisodeAdapter).isFiller = fillerEpisodes
                            (fv<androidx.recyclerview.widget.RecyclerView>(R.id.episodes_res_view).adapter as MasterEpisodeAdapter).notifyDataSetChanged()
                        }
                    } catch (e: java.lang.NullPointerException) {
                        e.printStackTrace()
                    }
                }
            }
        }
    }

    override fun onGestureRegionsUpdate(gestureRegions: List<Rect>) {
        fv<com.discord.panels.OverlappingPanelsLayout>(R.id.overlapping_panels)?.setChildGestureRegions(gestureRegions)
    }
}
