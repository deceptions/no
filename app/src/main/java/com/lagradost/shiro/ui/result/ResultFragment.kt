package com.lagradost.shiro.ui.result

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
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.firebase.ktx.Firebase
import com.google.firebase.messaging.ktx.messaging
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
import com.lagradost.shiro.utils.ShiroApi.Companion.getFav
import com.lagradost.shiro.utils.ShiroApi.Companion.getFullUrlCdn
import com.lagradost.shiro.utils.ShiroApi.Companion.getSubbed
import com.lagradost.shiro.utils.ShiroApi.Companion.getVideoLink
import com.lagradost.shiro.utils.mvvm.Resource
import com.lagradost.shiro.utils.mvvm.normalSafeApiCall
import com.lagradost.shiro.utils.mvvm.safeApiCall
import kotlinx.android.synthetic.main.bottom_sheet.*
import kotlinx.android.synthetic.main.fragment_results_2.*
import kotlinx.android.synthetic.main.fragment_results_edit_mal_id.*
import kotlinx.android.synthetic.main.fragment_results_sync_page.*
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
        relatedScrollView?.adapter?.notifyDataSetChanged()
        recommendationsScrollView?.adapter?.notifyDataSetChanged()
        super.onConfigurationChanged(newConfig)
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

    private fun onDataLoaded(data: ShiroApi.Companion.AnimePageNewData?) {
        episodeOffset = if (data?.episodes?.filter { it.episode == "0" }.isNullOrEmpty()) 0 else -1

        main {
            if (data == null) {
                Toast.makeText(activity, "Error loading anime page!", Toast.LENGTH_LONG).show()
                activity?.onBackPressed()
                return@main
            }
            val fadeAnimation = AlphaAnimation(1f, 0f)
            fadeAnimation.duration = 100
            fadeAnimation.isFillEnabled = true
            fadeAnimation.fillAfter = true
            loading_overlay?.startAnimation(fadeAnimation)
            open_website_btt?.isEnabled = false
            loadSeason()

            data class IdObject(
                @JsonProperty("mal") val mal: String?,
            )

            if (!hasLoadedAnilist && (hasAniList || hasMAL)) {
                thread {
                    hasLoadedAnilist = true
                    val idMal = mapper.readValue<IdObject?>(data.anime.ids)?.mal

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
                        loading_overlay?.alpha = 0f
                        open_website_btt?.isEnabled = false
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
                val youtubeId = mapper.readValue<List<TrailerObject?>?>(data.anime.trailer)
                    ?.firstOrNull { it?.youtube != null }?.youtube

                banner_play_btt?.isVisible = youtubeId != null
                if (youtubeId != null) {
                    spacer?.setOnClickListener {
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
                title_background?.let {
                    GlideApp.with(context)
                        .load(glideUrl)
                        .transition(DrawableTransitionOptions.withCrossFade(200))
                        .onlyRetrieveFromCache(savingData)
                        .into(it)
                }
                results_banner?.let {
                    GlideApp.with(context)
                        .load(getFullUrlCdn(data.anime.banner))
                        .transition(DrawableTransitionOptions.withCrossFade(200))
//                            .apply(bitmapTransform(BlurTransformation(100, 3)))
                        .onlyRetrieveFromCache(savingData)
                        .into(it)
                }

            }

            if (data.episodes.isNotEmpty()) {
                title_background?.setOnClickListener {
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
            title_status?.text =
                Html.fromHtml(
                    "<font color=#${textColorGrey}>Status:</font><font color=#${textColor}> ${
                        data.anime.status
                    }</font>"/*,
                        FROM_HTML_MODE_COMPACT*/
                )
            isBookmarked = context?.containsKey(BOOKMARK_KEY, data.anime.slug) == true
            toggleHeartVisual(isBookmarked)
            title_episodes?.text =
                Html.fromHtml(
                    "<font color=#${textColorGrey}>Episodes:</font><font color=#${textColor}> ${data.episodes.size}</font>"/*,
                        FROM_HTML_MODE_COMPACT*/
                )

            if (data.anime.release_year != null) {
                title_year?.text =
                    Html.fromHtml(
                        "<font color=#${textColorGrey}>Year:</font><font color=#${textColor}> ${data.anime.release_year}</font>"/*,
                            FROM_HTML_MODE_COMPACT*/
                    )
            } else {
                title_year?.visibility = GONE
            }

            normalSafeApiCall {
                title_genres?.text =
                    Html.fromHtml(
                        "<font color=#${textColorGrey}>Genres:</font><font color=#${textColor}> ${
                            mapper.readValue<List<String>>(data.anime.genres)
                                .joinToString(prefix = "", postfix = "", separator = " • ")
                        }</font>"/*,
                            FROM_HTML_MODE_COMPACT*/
                    )
            }

            displayDate()

            title_name?.text = data.anime.title
            val fullDescription = data.anime.synopsis
                .replace("<br>", "")
                .replace("<i>", "")
                .replace("</i>", "")
                .replace("\n", " ")


            share_btt?.setOnClickListener {
                val intent = Intent()
                intent.action = Intent.ACTION_SEND
                intent.putExtra(Intent.EXTRA_TEXT, "********/${data.anime.slug}")
                intent.type = "text/plain"
                startActivity(Intent.createChooser(intent, "Share To:"))
            }

            subscribe_btt?.let { btt ->
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
                        Firebase.messaging.unsubscribeFromTopic(slug)
                            .addOnCompleteListener { task ->
                                if (task.isSuccessful) {
                                    subscribe_btt?.setImageResource(R.drawable.ic_baseline_notifications_none_24)
                                    context?.removeKey(SUBSCRIPTIONS_BOOKMARK_KEY, slug)
                                    context?.removeKey(SUBSCRIPTIONS_BOOKMARK_KEY, slug.replace("-dub", "-dubbed"))
                                    context?.removeKey(SUBSCRIPTIONS_KEY, slug)
                                    context?.removeKey(SUBSCRIPTIONS_KEY, slug.replace("-dub", "-dubbed"))
                                }
                                var msg = "Unsubscribed to ${data.anime.title}"//getString(R.string.msg_subscribed)
                                if (!task.isSuccessful) {
                                    msg = "Unsubscribing failed :("//getString(R.string.msg_subscribe_failed)
                                }
                                thread {
                                    homeViewModel?.subscribed?.postValue(context?.getSubbed())
                                }
                                //Log.d(TAG, msg)
                                context?.let {
                                    Toast.makeText(it, msg, Toast.LENGTH_SHORT).show()
                                }
                            }
                    } else {
                        Firebase.messaging.subscribeToTopic(slug)
                            .addOnCompleteListener { task ->
                                if (task.isSuccessful) {
                                    subscribe_btt?.setImageResource(R.drawable.ic_baseline_notifications_active_24)
                                    context?.setKey(
                                        SUBSCRIPTIONS_BOOKMARK_KEY, slug, BookmarkedTitle(
                                            data.anime.title,
                                            data.anime.poster,
                                            data.anime.slug,
                                            data.anime.poster
                                        )
                                    )
                                }
                                var msg = "Subscribed to ${data.anime.title}"//getString(R.string.msg_subscribed)
                                if (!task.isSuccessful) {
                                    msg = "Subscription failed :("//getString(R.string.msg_subscribe_failed)
                                }
                                thread {
                                    homeViewModel?.subscribed?.postValue(context?.getSubbed())
                                }
                                //Log.d(TAG, msg)
                                context?.let {
                                    Toast.makeText(it, msg, Toast.LENGTH_SHORT).show()
                                }
                            }
                    }
                }
            }
            title_descript?.text =
                fullDescription.substring(0, minOf(DESCRIPTION_LENGTH1 - 3, fullDescription.length)) + "..."
            title_descript?.setOnClickListener {
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
                title_day_of_week?.visibility = VISIBLE
                title_day_of_week?.text =
                    Html.fromHtml(
                        "<font color=#${textColorGrey}>Schedule:</font><font color=#${textColor}> ${
                            secondsToReadable(airingEpisode.timeUntilAiring, "Now")
                        }</font>"/*,
                            FROM_HTML_MODE_COMPACT*/
                    )
            }
        } else {
            title_day_of_week?.visibility = GONE
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
            bookmark_btt?.setImageResource(R.drawable.filled_heart)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                bookmark_btt?.imageTintList = ColorStateList.valueOf(Cyanea.instance.primary)
            }
        } else {
            bookmark_btt?.setImageResource(R.drawable.outlined_heart)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                bookmark_btt?.imageTintList =
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
                mapper.readValue<List<ShiroApi.Companion.EpisodeObject?>?>(it)
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
                            bottomSheetDialog.bottom_sheet_top_bar.backgroundTintList =
                                ColorStateList.valueOf(Cyanea.instance.backgroundColorDark)
                        }

                        val res = bottomSheetDialog.sort_click
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
                        bottomSheetDialog.main_text?.text = "Select source"
                        bottomSheetDialog.setOnShowListener {
                            normalSafeApiCall {
                                BottomSheetBehavior.from(bottomSheetDialog.bottom_sheet_root.parent as View).peekHeight =
                                    bottomSheetDialog.bottom_sheet_root.height
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
                        ShiroApi.CommonAnimePageData(
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
            if (episodes_res_view?.adapter == null) {
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
                episodes_res_view?.adapter = adapter
                (episodes_res_view?.adapter as? MasterEpisodeAdapter)?.notifyDataSetChanged()
            } else {
                (episodes_res_view?.adapter as? MasterEpisodeAdapter)?.data = data
                (episodes_res_view?.adapter as? MasterEpisodeAdapter)?.items =
                    context?.generateItems(data.episodes, data.anime.slug) ?: mutableListOf()
                (episodes_res_view?.adapter as? MasterEpisodeAdapter)?.isFiller = fillerEpisodes
                (episodes_res_view?.adapter as? MasterEpisodeAdapter)?.notifyDataSetChanged()
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
            (episodes_res_view?.adapter as? MasterEpisodeAdapter)?.notifyDataSetChanged()
        }
    }

    private fun onDownloadStarted(id: String) {
        activity?.runOnUiThread {
            // Cast failure when going out of the page, making it catch to fully stop any of those crashes
            try {
                (episodes_res_view.adapter as MasterEpisodeAdapter).notifyDataSetChanged()
            } catch (e: java.lang.NullPointerException) {
            }
        }
    }

    private var currentLoadingCount = 0 // THIS IS USED TO PREVENT LATE EVENTS, AFTER DISMISS WAS CLICKED
    private var allEpisodes: HashMap<Int, ArrayList<ExtractorLink>> = HashMap()

    @SuppressLint("ClickableViewAccessibility")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // ORIENTATION_PORTRAIT not working for some reason so I chose this
        /*if (!settingsManager!!.getBoolean("force_landscape", false)) {
            val navBarSize = getCurrentActivity()!!.getNavigationBarSize()
            val min = minOf(navBarSize.y, navBarSize.x)
            fragments_new_nav_view?.setPadding(0, 0, 0, min)
        }*/
        activity?.showNavigation()

        go_back_btt?.setOnClickListener {
            activity?.onBackPressed()
        }
//        val displayMetrics = DisplayMetrics()

//        Handler().postDelayed({
//            startPostponedEnterTransition()
//        }, 50L)
//        fragments_new_nav_view?.background = ColorDrawable(Cyanea.instance.backgroundColor)
//        result_poster_blur?.background = ColorDrawable(Cyanea.instance.backgroundColor)
        fragment_results_toolbar?.background = ColorDrawable(Cyanea.instance.backgroundColor)
        fragment_results_nested_scrollview?.background = ColorDrawable(Cyanea.instance.backgroundColor)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            site_data_card_view?.backgroundTintList = ColorStateList.valueOf(Cyanea.instance.backgroundColorDark)
            title_holder?.backgroundTintList = ColorStateList.valueOf(Cyanea.instance.backgroundColor)
            sync_progressbar?.progressTintList = ColorStateList.valueOf(Cyanea.instance.accent)
        }
        loading_overlay?.background = ColorDrawable(Cyanea.instance.backgroundColor)
        episodes_res_view_holder?.backgroundTintList = ColorStateList.valueOf(Cyanea.instance.backgroundColorDark)
        card_spacer?.background = ColorDrawable(Cyanea.instance.backgroundColor)

        hideKeyboard()
        //title_duration.text = data!!.duration.toString() + "min"
        if (activity?.isCastApiAvailable() == true) {
            val mMediaRouteButton = view.findViewById<MediaRouteButton>(R.id.media_route_button)

            CastButtonFactory.setUpMediaRouteButton(activity, mMediaRouteButton)
            val castContext = CastContext.getSharedInstance(requireActivity().applicationContext)

            if (castContext.castState != CastState.NO_DEVICES_AVAILABLE) media_route_button?.visibility = VISIBLE
            castContext.addCastStateListener { state ->
                if (media_route_button != null) {
                    if (state == CastState.NO_DEVICES_AVAILABLE) media_route_button.visibility = GONE else {
                        if (media_route_button.visibility == GONE) media_route_button.visibility = VISIBLE
                    }
                }
            }
        }



        anilist_login_btt?.setOnClickListener {
            guaranteedContext(context).authenticateAniList()
        }
        mal_login_btt?.setOnClickListener {
            guaranteedContext(context).authenticateMAL()
        }

        observe(resultViewModel!!.totalAniListId) { id ->
            anilist_btt?.isVisible = id != null
            anilist_btt?.setOnClickListener {
                activity?.openBrowser("https://anilist.co/anime/$it")
            }
        }
        observe(resultViewModel!!.totalMalId) { id ->
            mal_btt?.isVisible = id != null
            mal_btt?.setOnClickListener {
                activity?.openBrowser("https://myanimelist.net/anime/$id")
            }
        }

        language_btt?.setOnClickListener {
            resultViewModel?.swapData()
        }

        observe(resultViewModel!!.hasLoadedOther) {
            val transition: Transition = ChangeBounds()
            transition.duration = 100
            language_btt?.isVisible = it
            fragment_results_toolbar?.let {
                TransitionManager.beginDelayedTransition(it, transition)
            }
        }

        relatedTextView?.isVisible = resultViewModel?.related?.value?.isNullOrEmpty() == false

        observe(resultViewModel!!.related) {
            val commonData = it?.map {
                ShiroApi.CommonAnimePageData(
                    it.title,
                    it.poster,
                    it.idMal.toString(),
                )
            }
            relatedTextView?.isVisible = commonData?.isNullOrEmpty() == false
            activity?.runOnUiThread {
                relatedScrollView.spanCount = 3

                val adapter: RecyclerView.Adapter<RecyclerView.ViewHolder> =
                    ResAdapter(
                        ArrayList(commonData),
                        relatedScrollView,
                        true,
                        forceDisableCompact = true
                    )

                relatedScrollView?.adapter = adapter
                relatedScrollView?.adapter?.notifyDataSetChanged()
            }
        }

        observe(resultViewModel!!.recommendations) {
            val commonData = it?.map {
                ShiroApi.CommonAnimePageData(
                    it.title,
                    it.poster,
                    it.idMal.toString(),
                )
            }
            activity?.runOnUiThread {
                recommendationsScrollView.spanCount = 3

                val adapter: RecyclerView.Adapter<RecyclerView.ViewHolder> =
                    ResAdapter(
                        ArrayList(commonData),
                        recommendationsScrollView,
                        true,
                        forceDisableCompact = true
                    )

                recommendationsScrollView?.adapter = adapter
                (recommendationsScrollView?.adapter as? ResAdapter)?.notifyDataSetChanged()
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

        sync_btt?.setOnClickListener {
            val number =
                if (number_picker_episode_text?.text.toString().toIntOrNull() == null
                ) 1 else minOf(
                    number_picker_episode_text?.text.toString().toInt(),
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

        number_picker_episode_up?.setOnClickListener {
            val number =
                if (number_picker_episode_text?.text.toString().toIntOrNull() == null
                ) 1 else minOf(
                    number_picker_episode_text?.text.toString().toInt() + 1,
                    getEpisodeMax()
                )
            postEpisode(number)
        }
        number_picker_episode_down.setOnClickListener {
            val number =
                if (number_picker_episode_text?.text.toString().toIntOrNull() == null
                ) 0 else minOf(
                    maxOf(
                        number_picker_episode_text?.text.toString().toInt() - 1,
                        0
                    ), getEpisodeMax()
                )
            postEpisode(number)
        }

        watching_btt?.setOnClickListener(SyncButtonClickListener(0))
        completed_btt?.setOnClickListener(SyncButtonClickListener(1))
        on_hold_btt?.setOnClickListener(SyncButtonClickListener(2))
        dropped_btt?.setOnClickListener(SyncButtonClickListener(3))
        plan_to_watch_btt?.setOnClickListener(SyncButtonClickListener(4))
        rewatching_btt?.setOnClickListener(SyncButtonClickListener(5))


        resultViewModel?.hasFailed?.observe(viewLifecycleOwner) {
            if (it == true) {
                Toast.makeText(activity, "Error loading anime page :(", Toast.LENGTH_LONG).show()
                activity?.onBackPressed()
            }
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

        sync_score_slider?.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                resultViewModel?.localData.notNull {
                    it.postValue(it.value?.apply {
                        score = value.toInt()
                    })
                }
            }
        }
        sync_score_slider?.setLabelFormatter { value: Float ->
            getScoreText(value.toInt())
        }

        rewatching_btt?.isVisible = hasAniList
        sync_page?.isVisible = hasAniList || hasMAL
        sync_page_failed?.isVisible = !hasAniList && !hasMAL

        observe(resultViewModel!!.syncData) {
            if (it == null) return@observe
            sync_title_?.text = it.title
            sync_status_text?.text = fromIntToAnimeStatus(it.status).name
            sync_score?.text = getScoreText(it.score)
            sync_progressbar?.max = it.episodes

            val realProgress =
                if (fromIntToAnimeStatus(it.status) == AniListApi.Companion.AniListStatusType.Completed) it.episodes else it.progress

            val realEpisodesText = if (it.episodes == 0) "???" else it.episodes

            sync_progress_txt?.text = "${realProgress}/$realEpisodesText"
            sync_progressbar?.progress = realProgress
        }
        observe(resultViewModel!!.localData) {
            if (it == null) return@observe
            sync_score_slider?.value = it.score.toFloat()
            val buttons = listOf<MaterialButton?>(
                watching_btt,
                completed_btt,
                plan_to_watch_btt,
                on_hold_btt,
                dropped_btt,
                rewatching_btt
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
            number_picker_episode_text?.setText(realProgress.toString())
        }

//        if (settingsManager?.getBoolean("hide_open_website", false) != true) {
//            observe(resultViewModel!!.slug) { slug ->
//                open_website_btt?.visibility = VISIBLE
//                open_website_btt?.setOnClickListener {
//                    guaranteedContext(context).openBrowser("********/${slug}")
//                }
//            }
//        }

        edit_button?.isVisible = hasAniList || hasMAL
        edit_button?.setOnClickListener {
            activity?.let { context ->
                val bottomSheetDialog = BottomSheetDialog(context, R.style.AppBottomSheetDialogTheme)
                bottomSheetDialog.setContentView(R.layout.fragment_results_edit_mal_id)

                bottomSheetDialog.anilist_id_text_holder.hint =
                    resultViewModel?.currentAniListId?.value?.toString()?.let { "Anilist ID: $it" } ?: "Anilist ID"
                bottomSheetDialog.mal_id_text_holder.hint =
                    resultViewModel?.currentMalId?.value?.toString()?.let { "Mal ID: $it" } ?: "Mal ID"

                resultViewModel?.overrideMalId?.value?.let {
                    bottomSheetDialog.mal_id_text_holder.editText?.setText(it.toString())
                }
                resultViewModel?.overrideAniListId?.value?.let {
                    bottomSheetDialog.anilist_id_text_holder.editText?.setText(it.toString())
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    bottomSheetDialog.bottom_sheet_top_bar_mal.backgroundTintList =
                        ColorStateList.valueOf(Cyanea.instance.backgroundColorDark)
                }

                bottomSheetDialog.mal_id_selector_root.background = ColorDrawable(Cyanea.instance.backgroundColor)
                bottomSheetDialog.copy_slug_btt.setOnClickListener {
                    val clipboard: ClipboardManager? =
                        it.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager?
                    val clip = ClipData.newPlainText("Slug", resultViewModel?.data?.value?.anime?.slug ?: "")
                    clipboard?.setPrimaryClip(clip)
                    Toast.makeText(it.context, "Slug copied to clipboard", Toast.LENGTH_SHORT).show()
                }

                bottomSheetDialog.anilist_save_btt.setOnClickListener {
                    val id = bottomSheetDialog.anilist_id_text_holder.editText?.text?.toString()
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

                bottomSheetDialog.mal_save_btt.setOnClickListener {
                    val id = bottomSheetDialog.mal_id_text_holder.editText?.text?.toString()
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

        bookmark_btt?.setOnClickListener {
            context?.toggleHeart(!isBookmarked)
        }

        val localName = arguments?.getString(NAME)
        if (localName != null && settingsManager?.getBoolean("search_for_filler_episodes", true) == true) {
            thread {
                fillerEpisodes =
                    FillerEpisodeCheck.getFillerEpisodes(localName)
                activity?.runOnUiThread {
                    try {
                        if (episodes_res_view?.adapter != null) {
                            (episodes_res_view.adapter as MasterEpisodeAdapter).isFiller = fillerEpisodes
                            (episodes_res_view.adapter as MasterEpisodeAdapter).notifyDataSetChanged()
                        }
                    } catch (e: java.lang.NullPointerException) {
                        e.printStackTrace()
                    }
                }
            }
        }
    }

    override fun onGestureRegionsUpdate(gestureRegions: List<Rect>) {
        overlapping_panels?.setChildGestureRegions(gestureRegions)
    }
}
