package com.lagradost.shiro.ui.tv

/*
 * Copyright 2019 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


import ANILIST_SHOULD_UPDATE_LIST
import ANILIST_TOKEN_KEY
import DataStore.getKey
import DataStore.mapper
import DataStore.removeKey
import DataStore.setKey
import MAL_SHOULD_UPDATE_LIST
import MAL_TOKEN_KEY
import RESIZE_MODE_KEY
import VIEW_DUR_KEY
import VIEW_POS_KEY
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PorterDuff
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.support.v4.media.session.MediaSessionCompat
import android.util.Log
import android.view.*
import android.widget.*
import android.widget.AbsListView.CHOICE_MODE_SINGLE
import android.widget.Toast.LENGTH_LONG
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.core.view.children
import androidx.leanback.app.VideoSupportFragment
import androidx.leanback.app.VideoSupportFragmentGlueHost
import androidx.leanback.media.PlaybackGlue
import androidx.leanback.media.PlaybackTransportControlGlue
import androidx.leanback.widget.Action
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.PlaybackControlsRow
import androidx.leanback.widget.SeekBar
import androidx.lifecycle.ViewModelProvider
import androidx.preference.PreferenceManager
import com.fasterxml.jackson.module.kotlin.readValue
import com.google.android.exoplayer2.*
import com.google.android.exoplayer2.database.ExoDatabaseProvider
import com.google.android.exoplayer2.ext.leanback.LeanbackPlayerAdapter
import com.google.android.exoplayer2.ext.mediasession.MediaSessionConnector
import com.google.android.exoplayer2.source.DefaultMediaSourceFactory
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector
import com.google.android.exoplayer2.upstream.DataSource
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource
import com.google.android.exoplayer2.upstream.cache.CacheDataSource
import com.google.android.exoplayer2.upstream.cache.LeastRecentlyUsedCacheEvictor
import com.google.android.exoplayer2.upstream.cache.SimpleCache
import com.google.android.exoplayer2.util.MimeTypes
import com.google.android.exoplayer2.video.VideoSize
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.jaredrummler.cyanea.Cyanea
import com.lagradost.shiro.R
import com.lagradost.shiro.ui.MainActivity.Companion.masterViewModel
import com.lagradost.shiro.ui.library.LibraryFragment.Companion.libraryViewModel
import com.lagradost.shiro.ui.player.PlayerData
import com.lagradost.shiro.ui.player.PlayerFragment.Companion.onPlayerNavigated
import com.lagradost.shiro.ui.player.PlayerViewModel
import com.lagradost.shiro.ui.player.SSLTrustManager
import com.lagradost.shiro.ui.player.STATE_RESUME_POSITION
import com.lagradost.shiro.ui.tv.MainFragment.Companion.hasBeenInPlayer
import com.lagradost.shiro.utils.*
import com.lagradost.shiro.utils.AniListApi.Companion.getDataAboutId
import com.lagradost.shiro.utils.AniListApi.Companion.postDataAboutId
import com.lagradost.shiro.utils.AppUtils.getCurrentActivity
import com.lagradost.shiro.utils.AppUtils.getCurrentContext
import com.lagradost.shiro.utils.AppUtils.getTextColor
import com.lagradost.shiro.utils.AppUtils.getViewKey
import com.lagradost.shiro.utils.AppUtils.getViewPosDur
import com.lagradost.shiro.utils.AppUtils.guaranteedContext
import com.lagradost.shiro.utils.AppUtils.setAspectRatio
import com.lagradost.shiro.utils.AppUtils.setViewPosDur
import com.lagradost.shiro.utils.Coroutines.main
import com.lagradost.shiro.utils.MALApi.Companion.getDataAboutMalId
import com.lagradost.shiro.utils.MALApi.Companion.malStatusAsString
import com.lagradost.shiro.utils.MALApi.Companion.setScoreRequest
import com.lagradost.shiro.utils.ShiroApi.Companion.USER_AGENT
import com.lagradost.shiro.utils.ShiroApi.Companion.fmod
import com.lagradost.shiro.utils.ShiroApi.Companion.loadLinks
import com.lagradost.shiro.utils.mvvm.normalSafeApiCall
import kotlinx.android.synthetic.main.bottom_sheet.*
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSession
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min


/** A fragment representing the current metadata item being played */
class PlayerFragmentTv : VideoSupportFragment() {

    /** AndroidX navigation arguments */

    //private lateinit var player: SimpleExoPlayer
    //private lateinit var database: TvMediaDatabase

    /** Allows interaction with transport controls, volume keys, media buttons  */
    private lateinit var mediaSession: MediaSessionCompat
    private var playbackPosition: Long = 0
    private val checkProgressAction = Runnable { checkProgress() }
    private var lastSyncedEpisode = -1
    private val handler = Handler()
    private var hasAddedSources = false
    private var playerViewModel: PlayerViewModel? = null

    // To show episode 0
    private var episodeOffset = 0

    /** Glue layer between the player and our UI */
    private lateinit var playerGlue: MediaPlayerGlue
    private lateinit var exoPlayer: SimpleExoPlayer

    /** Settings */
    private val settingsManager = AppUtils.settingsManager ?: (getCurrentContext() ?: context)?.let {
        PreferenceManager.getDefaultSharedPreferences(it)
    }
    private val fastForwardTime = settingsManager!!.getInt("fast_forward_button_time", 10)
    private val fastForwardTimeMillis: Long = TimeUnit.SECONDS.toMillis(fastForwardTime.toLong())
    private val autoPlayEnabled = settingsManager!!.getBoolean("autoplay_enabled", true)
    private val skipFillers = settingsManager!!.getBoolean("skip_fillers", false)

    private val resizeModes = listOf(
        AppUtils.AspectRatioTV.RESIZE_MODE_FIT,
        AppUtils.AspectRatioTV.RESIZE_MODE_STRETCH,
        AppUtils.AspectRatioTV.RESIZE_MODE_ZOOM,
        AppUtils.AspectRatioTV.RESIZE_MODE_4_3,
    )
    private var resizeMode = (getCurrentContext() ?: context)?.getKey(RESIZE_MODE_KEY, 0) ?: 0

    /*
    private val resizeModes = listOf(
        VIDEO_SCALING_MODE_DEFAULT,
        VIDEO_SCALING_MODE_SCALE_TO_FIT,
        VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING,
    )
    private var resizeMode = DataStore.getKey(RESIZE_MODE_KEY, 0) ?: 0*/

    // To prevent watching everything while sleeping
    private var episodesSinceInteraction = 0

    var data: PlayerData? = null

    /** Link loading */
    private var selectedSource: ExtractorLink? = null
    private var sources: Pair<Int?, List<ExtractorLink>?> = Pair(null, null)
    private val extractorLinks = mutableListOf<ExtractorLink>()

    // Prevent clicking next episode button multiple times
    private var isLoadingNextEpisode = false
    private var isCurrentlyPlaying: Boolean = false

    private val cacheSize = 100L * 1024L * 1024L // 100 mb
    private var simpleCache: SimpleCache? = null

    /**
     * Connects a [MediaSessionCompat] to a [Player] so transport controls are handled automatically
     */
    private lateinit var mediaSessionConnector: MediaSessionConnector

    /** Custom implementation of [PlaybackTransportControlGlue] */
    private inner class MediaPlayerGlue(context: Context, adapter: LeanbackPlayerAdapter) :
        PlaybackTransportControlGlue<LeanbackPlayerAdapter>(context, adapter) {

        private val actionRewind = PlaybackControlsRow.RewindAction(context)
        private val actionFastForward = PlaybackControlsRow.FastForwardAction(context)
        private val actionSkipOp = PlaybackControlsRow.FastForwardAction(context)
        private val actionNextEpisode = PlaybackControlsRow.SkipNextAction(context)
        private val actionSources = PlaybackControlsRow.MoreActions(context)
        private val actionResize = PlaybackControlsRow.MoreActions(context)

        // private val actionResize = PlaybackControlsRow.MoreActions(context)
        //private val actionClosedCaptions = PlaybackControlsRow.ClosedCaptioningAction(context)

        fun skipForward(millis: Long = fastForwardTimeMillis) =
            // Ensures we don't advance past the content duration (if set)
            exoPlayer.seekTo(
                if (exoPlayer.contentDuration > 0) {
                    min(exoPlayer.contentDuration, exoPlayer.currentPosition + millis)
                } else {
                    exoPlayer.currentPosition + millis
                }
            )

        fun skipBackward(millis: Long = fastForwardTimeMillis) =
            // Ensures we don't go below zero position
            exoPlayer.seekTo(max(0, exoPlayer.currentPosition - millis))

        override fun onCreatePrimaryActions(adapter: ArrayObjectAdapter) {
            super.onCreatePrimaryActions(adapter)

            fun Action.setIcon(drawable: Int) {
                this.icon = AppCompatResources.getDrawable(context, drawable).apply {
                    this?.mutate()
                        ?.setColorFilter(
                            ContextCompat.getColor(getCurrentContext()!!, R.color.white),
                            PorterDuff.Mode.SRC_IN
                        )
                }
            }
            actionRewind.setIcon(R.drawable.netflix_skip_back)
            actionFastForward.setIcon(R.drawable.netflix_skip_forward)
            actionSkipOp.setIcon(R.drawable.ic_baseline_fast_forward_24)
            actionNextEpisode.setIcon(R.drawable.exo_controls_next)
            actionSources.setIcon(R.drawable.ic_baseline_playlist_play_24)
            actionResize.setIcon(R.drawable.ic_baseline_aspect_ratio_24)
            // actionResize.setIcon(R.drawable.ic_baseline_aspect_ratio_24)

            // Append rewind and fast forward actions to our player, keeping the play/pause actions
            // created by default by the glue
            // adapter.add(actionRewind)
            // adapter.add(actionFastForward)
            adapter.add(actionSkipOp)
            // adapter.add(actionResize)

            if (sources.second?.size ?: 0 > 1) {
                adapter.add(actionSources)
                hasAddedSources = true
            } else {
                hasAddedSources = false
            }
            adapter.add(actionResize)

            if (data?.episodeIndex!! + 1 < data?.card?.episodes?.size!!) {
                adapter.add(actionNextEpisode)
            }
            //adapter.add(actionClosedCaptions)
        }

        override fun onActionClicked(action: Action) {
            episodesSinceInteraction = 0
            when (action) {
                actionRewind -> skipBackward()
                actionFastForward -> skipForward()
                actionSkipOp -> skipForward(SKIP_OP_MILLIS)
                actionNextEpisode -> {
                    if (data?.episodeIndex != null && !isLoadingNextEpisode) {
                        playNextEpisode()
                    } else {
                    }
                }
                actionSources -> {
                    activity?.let { activity ->
                        sources.second?.let {
                            val arrayAdapter = ArrayAdapter<String>(activity, R.layout.bottom_single_choice)
                            val sourcesText = it.map { link -> link.name }
                            val index = maxOf(sources.second?.indexOf(selectedSource) ?: -1, 0)
                            arrayAdapter.addAll(ArrayList(sourcesText))

                            val bottomSheetDialog = BottomSheetDialog(activity, R.style.AppBottomSheetDialogTheme)
                            bottomSheetDialog.setContentView(R.layout.bottom_sheet)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                                bottomSheetDialog.bottom_sheet_top_bar.backgroundTintList =
                                    ColorStateList.valueOf(Cyanea.instance.backgroundColorDark)
                            }

                            val res = bottomSheetDialog.findViewById<ListView>(R.id.sort_click)!!
                            res.choiceMode = CHOICE_MODE_SINGLE
                            res.adapter = arrayAdapter
                            res.setItemChecked(
                                index,
                                true
                            )
                            res.setOnItemClickListener { _, _, position, _ ->
                                selectedSource = it[position]
                                activity.savePos()
                                releasePlayer()
                                loadAndPlay()

                                bottomSheetDialog.dismiss()
                            }
                            bottomSheetDialog.main_text?.text = "Source"
                            // Full expansion for TV
                            bottomSheetDialog.setOnShowListener {
                                normalSafeApiCall {
                                    BottomSheetBehavior.from(bottomSheetDialog.bottom_sheet_root.parent as View).peekHeight =
                                        bottomSheetDialog.bottom_sheet_root.height
                                }
                            }
                            bottomSheetDialog.show()

                        }
                    }
                    // Do not remove
                    Unit

                    /*sources.second?.let {
                        val index = maxOf(sources.second?.indexOf(selectedSource) ?: -1, 0)
                        selectedSource = it[(index + 1) % it.size]
                        //val speed = speedsText[which]
                        savePos()
                        releasePlayer()
                        loadAndPlay()
                    }
                    val sourcesTxt =
                        sources.second!!.mapIndexed { index, extractorLink -> if (extractorLink == selectedSource) "✦ ${extractorLink.name} selected" else extractorLink.name }
                    Toast.makeText(
                        requireContext(),
                        "${sourcesTxt.joinToString(separator = "\n")}",
                        Toast.LENGTH_SHORT
                    ).show()*/

                }
                /*actionResize -> {
                    if (this@PlayerFragmentTv::exoPlayer.isInitialized) {
                        resizeMode = (resizeMode + 1).fmod(resizeModes.size)
                        DataStore.setKey(RESIZE_MODE_KEY, resizeMode)
                        println("HERE $resizeMode")
                    }
                }*/
                actionResize -> resize()
                else -> super.onActionClicked(action)
            }
        }

    }

    private fun resize() {
        playerViewModel?.videoSize?.value?.let {
            resizeMode = (resizeMode + 1).fmod(resizeModes.size)
            context?.setKey(RESIZE_MODE_KEY, resizeMode)
            surfaceView?.setAspectRatio(resizeModes[resizeMode], it)
        }
    }

    private fun playNextEpisode() {

        handler.removeCallbacks(checkProgressAction)
        context?.savePos()
        playerGlue.host.hideControlsOverlay(false)
        isLoadingNextEpisode = true
        playerViewModel?.selectedSource?.postValue(null)

        val key = getViewKey(
            data?.card!!.anime.slug,
            data!!.episodeIndex!! + 1
        )
        context?.removeKey(VIEW_POS_KEY, key)
        context?.removeKey(VIEW_DUR_KEY, key)

        val next =
            if (skipFillers) data?.fillerEpisodes?.filterKeys { it > data!!.episodeIndex!! + 1 }
                ?.filterValues { !it }?.keys?.minByOrNull { it }?.minus(1) else null
        next?.let {
            if (it - data!!.episodeIndex!! - 1 > 0) {
                Toast.makeText(
                    context,
                    "Skipped ${it - data!!.episodeIndex!! - 1} filler episodes",
                    LENGTH_LONG
                ).show()
            }
        }

        data?.episodeIndex = next ?: minOf(data?.episodeIndex!! + 1, data?.card?.episodes?.size!! - 1)
        selectedSource = null
        extractorLinks.clear()
        releasePlayer()
        loadAndPlay()
        handler.postDelayed(checkProgressAction, 5000L)
    }

    private fun releasePlayer() {
        isCurrentlyPlaying = false
        simpleCache?.release()
        playerViewModel?.videoSize?.postValue(null)

        if (this::exoPlayer.isInitialized) {
            exoPlayer.stop()
            exoPlayer.release()
        }
    }

    private fun checkProgress() {
        val time = 5000L

        // Disabled if it's 0
        val setPercentage: Float = (settingsManager?.getInt("completed_percentage", 80)?.toFloat() ?: 80F) / 100
        val saveHistory: Boolean = settingsManager?.getBoolean("save_history", true) ?: true

        if (this::exoPlayer.isInitialized && setPercentage != 0.0f && saveHistory) {
            val currentPercentage = exoPlayer.currentPosition.toFloat() / exoPlayer.duration.toFloat()
            if (currentPercentage > setPercentage && lastSyncedEpisode < data?.episodeIndex!!) {
                lastSyncedEpisode = data?.episodeIndex!!
                thread {
                    context?.updateProgress()
                }
            } else {
                if (data?.anilistID != null || data?.malID != null) handler.postDelayed(
                    checkProgressAction,
                    time
                )
            }
        } else if (data?.anilistID != null || data?.malID != null && setPercentage != 0.0f && saveHistory) {
            handler.postDelayed(
                checkProgressAction,
                time
            )
        }
    }

    private fun Context.updateProgress() {
        val hasAniList = getKey<String>(
            ANILIST_TOKEN_KEY,
            ANILIST_ACCOUNT_ID,
            null
        ) != null
        val hasMAL = getKey<String>(MAL_TOKEN_KEY, MAL_ACCOUNT_ID, null) != null

        val malHolder = if (hasMAL) data?.malID?.let { getDataAboutMalId(it) } else null
        val holder = if (hasAniList && malHolder == null) data?.anilistID?.let {
            activity?.getDataAboutId(
                it
            )
        } else null

        val progress = holder?.progress ?: malHolder?.my_list_status?.num_episodes_watched ?: 0
        val score = holder?.score ?: malHolder?.my_list_status?.score ?: 0

        var type = if (holder != null) {
            val type =
                if (holder.type == AniListApi.Companion.AniListStatusType.None) AniListApi.Companion.AniListStatusType.Watching else holder.type
            AniListApi.fromIntToAnimeStatus(type.value)
        } else {
            var type =
                AniListApi.fromIntToAnimeStatus(
                    malStatusAsString.indexOf(
                        malHolder?.my_list_status?.status ?: "watching"
                    )
                )
            type =
                if (type.value == MALApi.Companion.MalStatusType.None.value) AniListApi.Companion.AniListStatusType.Watching else type
            type
        }

        val currentEpisodeProgress = data?.episodeIndex!! + 1 + episodeOffset

        if (currentEpisodeProgress == holder?.episodes ?: data?.card?.episodes?.size?.plus(episodeOffset)
            && type.value != AniListApi.Companion.AniListStatusType.Completed.value
            && data?.card?.anime?.status?.lowercase() == "finished airing"
        ) {
            type = AniListApi.Companion.AniListStatusType.Completed
        }


        if (progress < currentEpisodeProgress && holder ?: malHolder != null) {
            val anilistPost =
                if (hasAniList) data?.anilistID?.let {
                    activity?.postDataAboutId(
                        it,
                        type,
                        score,
                        currentEpisodeProgress
                    )
                } ?: false else true
            val malPost = if (hasMAL)
                data?.malID?.let {
                    setScoreRequest(
                        it,
                        MALApi.fromIntToAnimeStatus(type.value),
                        score,
                        currentEpisodeProgress
                    )
                } ?: false else true
            if (!anilistPost || !malPost) {
                main {
                    Toast.makeText(
                        guaranteedContext(context),
                        "Error updating episode progress",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } else {
//                publicResultViewModel?.visibleEpisodeProgress?.postValue(currentEpisodeProgress)
                main {
                    Toast.makeText(
                        guaranteedContext(context),
                        "Marked episode $currentEpisodeProgress as seen",
                        Toast.LENGTH_LONG
                    ).show()
                }
                setKey(MAL_SHOULD_UPDATE_LIST, true)
                setKey(ANILIST_SHOULD_UPDATE_LIST, true)
                libraryViewModel?.requestMalList(this)
                libraryViewModel?.requestAnilistList(this)
            }
        }
    }


    private fun Context.savePos() {
        println("Savepos")
        if (this@PlayerFragmentTv::exoPlayer.isInitialized) {
            if (((data != null
                        && data?.episodeIndex != null) || data?.card?.episodes != null)
                && exoPlayer.duration > 0 && exoPlayer.currentPosition > 0
            ) {
                //println("SAVED POS $playbackPosition ${exoPlayer.currentPosition}")
                setViewPosDur(data!!, exoPlayer.currentPosition, exoPlayer.duration)
            }
        }
    }

    private fun generateGlue(updateTitle: Boolean) {
        if (this::exoPlayer.isInitialized) {
            activity?.let { activity ->
                main {
                    val fillerInfo =
                        if (data?.fillerEpisodes?.get(
                                (data?.episodeIndex ?: -1) + 1
                            ) == true
                        ) " (Filler) " else ""

                    val postTitle = playerViewModel?.videoSize?.value?.let { videoSize ->
                        "\n${videoSize.width}x${videoSize.height}${getCurrentUrl()?.name?.let { " - $it" } ?: ""}"
                    } ?: ""
                    val title = "Episode ${data?.episodeIndex!! + 1 + episodeOffset}" + fillerInfo
                    val subtitle = (data?.card?.anime?.title ?: "") + postTitle

                    // Links our video player with this Leanback video playback fragment
                    val playerAdapter = LeanbackPlayerAdapter(
                        activity, exoPlayer, PLAYER_UPDATE_INTERVAL_MILLIS
                    )

                    activity.findViewById<LinearLayout?>(R.id.transport_row)?.children?.forEach {
                        if (it is TextView) {
                            it.setTextColor(activity.getTextColor())
                        }
                    }

                    // Enables pass-through of transport controls to our player instance
                    playerGlue = if (updateTitle) {
                        playerGlue.apply {
                            this.title = title
                            this.subtitle = subtitle
                        }
                    } else {
                        MediaPlayerGlue(activity, playerAdapter).apply {
                            host = VideoSupportFragmentGlueHost(this@PlayerFragmentTv)
                            this.title = title
                            this.subtitle = subtitle

                            // Adds playback state listeners
                            addPlayerCallback(object : PlaybackGlue.PlayerCallback() {
                                override fun onPreparedStateChanged(glue: PlaybackGlue?) {
                                    super.onPreparedStateChanged(glue)
                                    if (glue?.isPrepared == true) {
                                        // When playback is ready, skip to last known position
                                        val startingPosition = 0L//metadata.playbackPositionMillis ?: 0
                                        Log.d(TAG, "Setting starting playback position to $startingPosition")
                                        seekTo(startingPosition)
                                    }
                                }

                                override fun onPlayCompleted(glue: PlaybackGlue?) {
                                    super.onPlayCompleted(glue)

                                    // Don't forget to remove irrelevant content from the continue watching row
                                    //TvLauncherUtils.removeFromWatchNext(requireContext(), args.metadata)

                                }
                            })
                            // Begins playback automatically
                            playWhenPrepared()
                            activity.savePos()

                            // Adds key listeners
                            host.setOnKeyInterceptListener { view, keyCode, event ->
                                episodesSinceInteraction = 0
                                val playbackProgress = view.findViewById<SeekBar?>(R.id.playback_progress)
                                    ?: return@setOnKeyInterceptListener false
                                playbackProgress.isFocusable = playerGlue.host.isControlsOverlayVisible
                                // Early exit: if the controls overlay is visible, don't intercept any keys
                                if (playerGlue.host.isControlsOverlayVisible && !playbackProgress.isFocused) return@setOnKeyInterceptListener false

                                //  This workaround is necessary for navigation library to work with
                                //  Leanback's [PlaybackSupportFragment]
                                if (!playerGlue.host.isControlsOverlayVisible &&
                                    keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_DOWN
                                ) {
                                    /*val navController = Navigation.findNavController(
                                            requireActivity(), R.id.fragment_container)
                                    navController.currentDestination?.id?.let { navController.popBackStack(it, true) }*/
                                    activity.savePos()
                                    activity.onBackPressed()
                                    return@setOnKeyInterceptListener true
                                }

                                // Skips ahead when user presses DPAD_RIGHT
                                if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && event.action == KeyEvent.ACTION_DOWN) {
                                    playerGlue.skipForward()
                                    if (!playbackProgress.isFocused) {
                                        preventControlsOverlay(playerGlue)
                                        playbackProgress.isFocusable = false
                                    }
                                    return@setOnKeyInterceptListener true
                                }

                                // Rewinds when user presses DPAD_LEFT
                                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && event.action == KeyEvent.ACTION_DOWN) {
                                    playerGlue.skipBackward()
                                    if (!playbackProgress.isFocused) {
                                        preventControlsOverlay(playerGlue)
                                        playbackProgress.isFocusable = false
                                    }
                                    return@setOnKeyInterceptListener true
                                }

                                false
                            }
                            // Displays the current item's metadata
                            //setMetadata(metadata)
                        }
                    }
                    // Setup the fragment adapter with our player glue presenter
                    adapter = ArrayObjectAdapter(playerGlue.playbackRowPresenter).apply {
                        add(playerGlue.controlsRow)
                    }
                }
            }
        }
    }

    private fun initPlayer(inputUrl: ExtractorLink? = null) {
        isCurrentlyPlaying = true
        thread {
            val currentUrl = inputUrl ?: getCurrentUrl()
            if (currentUrl == null) {
                activity?.let {
                    it.runOnUiThread {
                        Toast.makeText(it, "No links found", LENGTH_LONG).show()
                        it.onBackPressed()
                    }
                }
                return@thread
            }
            val isOnline =
                currentUrl.url.startsWith("https://") || currentUrl.url.startsWith("http://")
            //database = TvMediaDatabase.getInstance(requireContext())
            //val metadata = args.metadata

            // Adds this program to the continue watching row, in case the user leaves before finishing
            /*val programUri = TvLauncherUtils.upsertWatchNext(requireContext(), metadata)
            if (programUri != null) lifecycleScope.launch(Dispatchers.IO) {
                database.metadata().update(metadata.apply { watchNext = true })
            }*/

            val mimeType = if (currentUrl.isM3u8) MimeTypes.APPLICATION_M3U8 else MimeTypes.APPLICATION_MP4
            val mediaItemBuilder = MediaItem.Builder()
                //Replace needed for android 6.0.0  https://github.com/google/ExoPlayer/issues/5983
                .setMimeType(mimeType)

            fun newDataSourceFactory(): DataSource.Factory {
                return if (isOnline) {
                    DefaultHttpDataSource.Factory().apply {
                        val headers = mapOf("Referer" to currentUrl.referer)
                        setDefaultRequestProperties(headers)
                        setUserAgent(USER_AGENT)
                    }
                } else {
                    DefaultDataSourceFactory(getCurrentContext()!!, USER_AGENT)
                }
            }

//            class CustomFactory : DataSource.Factory {
//                override fun createDataSource(): DataSource {
//                    return if (isOnline) {
//                        val dataSource = DefaultHttpDataSourceFactory(USER_AGENT).createDataSource()
//                        /*FastAniApi.currentHeaders?.forEach {
//                            dataSource.setRequestProperty(it.key, it.value)
//                        }*/
//                        currentUrl.referer.let { dataSource.setRequestProperty("Referer", it) }
//                        dataSource
//                    } else {
//                        DefaultDataSourceFactory(getCurrentActivity()!!, USER_AGENT).createDataSource()
//                    }
//                }
//            }

            if (isOnline) {
                mediaItemBuilder.setUri(currentUrl.url)
            } else {
                currentUrl.let {
                    mediaItemBuilder.setUri(Uri.fromFile(File(it.url)))
                }
            }


            val mediaItem = mediaItemBuilder.build()
            val exoPlayerBuilder = context?.let {
                val trackSelector = DefaultTrackSelector(it)
                // Disable subtitles
                trackSelector.parameters = DefaultTrackSelector.ParametersBuilder(it)
                    .setRendererDisabled(C.TRACK_TYPE_VIDEO, true)
                    .setRendererDisabled(C.TRACK_TYPE_TEXT, true)
                    .setDisabledTextTrackSelectionFlags(C.TRACK_TYPE_TEXT)
                    .clearSelectionOverrides()
                    .build()
                SimpleExoPlayer.Builder(it)
                    .setTrackSelector(trackSelector)
            } ?: SimpleExoPlayer.Builder(getCurrentContext()!!)

//            exoPlayerBuilder.setMediaSourceFactory(DefaultMediaSourceFactory(CustomFactory()))

            activity?.runOnUiThread {
                if (data?.card != null || (data?.slug != null && data?.episodeIndex != null && data?.seasonIndex != null)) {
                    val pro = context?.getViewPosDur(
                        if (data?.card != null) data?.card!!.anime.slug else data?.slug!!,
                        data?.episodeIndex!!
                    )
                    if (pro != null) {
                        playbackPosition =
                            if (pro.pos > 0 && pro.dur > 0 && (pro.pos * 100 / pro.dur) < 95) { // UNDER 95% RESUME
                                pro.pos
                            } else {
                                0L
                            }
                    }
                } else if (data?.startAt != null) {
                    playbackPosition = data?.startAt ?: 0
                }

                val factory = newDataSourceFactory()
                val dbProvider = ExoDatabaseProvider(getCurrentContext()!!)
                normalSafeApiCall {
                    simpleCache = SimpleCache(
                        File(getCurrentContext()!!.filesDir, "exoplayer"),
                        LeastRecentlyUsedCacheEvictor(cacheSize),
                        dbProvider
                    )
                }
                val cacheFactory = CacheDataSource.Factory().apply {
                    simpleCache?.let { setCache(it) }
                    setUpstreamDataSourceFactory(factory)
                }

                exoPlayer = exoPlayerBuilder.build().apply {
                    //playWhenReady = isPlayerPlaying
                    //seekTo(currentWindow, playbackPosition)
                    seekTo(0, playbackPosition)
//                    setMediaItem(mediaItem, false)
                    setMediaSource(
                        DefaultMediaSourceFactory(cacheFactory).createMediaSource(mediaItem),
                        playbackPosition
                    )
                    prepare()
                }

                exoPlayer.addListener(object : Player.Listener {
                    override fun onPlayerStateChanged(playWhenReady: Boolean, playbackState: Int) {
                        // episodesSinceInteraction is to prevent watching while sleeping
                        if (playbackState == Player.STATE_ENDED && episodesSinceInteraction <= 3 && data?.episodeIndex != null && !isLoadingNextEpisode) {
                            if (autoPlayEnabled) playNextEpisode()
                            episodesSinceInteraction++
                        }
                    }

                    override fun onVideoSizeChanged(videoSize: VideoSize) {
                        playerViewModel?.videoSize?.postValue(videoSize)

                        handler.postDelayed({
                            surfaceView?.setAspectRatio(resizeModes[resizeMode], videoSize)
                        }, 200L)

                        super.onVideoSizeChanged(videoSize)
                    }

                    override fun onPlayerError(error: ExoPlaybackException) {
                        // Lets pray this doesn't spam Toasts :)
                        when (error.type) {
                            ExoPlaybackException.TYPE_SOURCE -> {
                                if (currentUrl.url != "") {
                                    Toast.makeText(
                                        activity,
                                        "Source error\n" + error.sourceException.message,
                                        LENGTH_LONG
                                    )
                                        .show()
                                }
                            }
                            ExoPlaybackException.TYPE_REMOTE -> {
                                Toast.makeText(activity, "Remote error", LENGTH_LONG)
                                    .show()
                            }
                            ExoPlaybackException.TYPE_RENDERER -> {
                                Toast.makeText(
                                    activity,
                                    "Renderer error\n" + error.rendererException.message,
                                    LENGTH_LONG
                                )
                                    .show()
                            }
                            ExoPlaybackException.TYPE_UNEXPECTED -> {
                                Toast.makeText(
                                    activity,
                                    "Unexpected player error\n" + error.unexpectedException.message,
                                    LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                })

                // Initializes the video player
                //player = ExoPlayerFactory.newSimpleInstance(requireContext())
                generateGlue(false)

                // Listen to media session events. This is necessary for things like closed captions which
                // can be triggered by things outside of our app, for example via Google Assistant


            }
        }
        isLoadingNextEpisode = false
    }

    private fun getCurrentEpisode(): ShiroApi.Companion.AnimePageNewEpisodes? {
        return data?.card?.episodes?.getOrNull(data?.episodeIndex!!)//data?.card!!.cdnData.seasons.getOrNull(data?.seasonIndex!!)?.episodes?.get(data?.episodeIndex!!)
    }

    private fun loadAndPlay() {
        // Cached, first is index, second is links
        thread {
            if (!(sources.first == data?.episodeIndex && data?.episodeIndex != null)) {
                val episodes = getCurrentEpisode()?.sources?.let {
                    mapper.readValue<List<ShiroApi.Companion.EpisodeObject?>?>(it)
                }
                val videoId = episodes?.firstOrNull { it?.slug == "gogostream" }
                videoId?.source?.let {
                    loadLinks(
                        it,
                        false,
                        callback = ::linkLoaded
                    )
                }
            }
            main {
                initPlayerIfPossible()
            }
        }
    }

    private fun linkLoaded(link: ExtractorLink) {
        extractorLinks.add(link)
        val safeLinks = extractorLinks.toTypedArray()
        if (!hasAddedSources) generateGlue(false)
        sources = Pair(data?.episodeIndex, safeLinks.sortedBy { -it.quality }.distinctBy { it.url })

        // Prevent concurrentModification
        // Quickstart provided shiro is loaded and one other link, because I haven't figured out how to refresh the glue
        if (safeLinks.size > 1 && (link.quality == Qualities.UHD.value || link.quality == Qualities.FullHd.value)) {
            //if (link.name == "Shiro") {
            main {
                initPlayerIfPossible(link)
            }
        }
    }

    private fun initPlayerIfPossible(link: ExtractorLink? = null) {
        if (!isCurrentlyPlaying) {
            initPlayer(link)
        }
    }

    private fun getCurrentUrl(): ExtractorLink? {
        val index = maxOf(sources.second?.indexOf(selectedSource) ?: -1, 0)
        return sources.second?.getOrNull(index)
    }

    @SuppressLint("RestrictedApi")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        getCurrentContext()?.theme?.applyStyle(R.style.normalText, true)
        //playbackPosition = activity?.intent?.getSerializableExtra(DetailsActivityTV.PLAYERPOS) as? Long ?: 0L
        //data = mapper.readValue<ShiroApi.AnimePageData>(dataString!!)
        mediaSession = getCurrentContext()?.let {
            MediaSessionCompat(it, getString(R.string.app_name))
        } ?: MediaSessionCompat(requireContext(), getString(R.string.app_name))
        mediaSessionConnector = MediaSessionConnector(mediaSession)
    }


    // This is needed to get normal text in light mode
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
//        backgroundType = PlaybackSupportFragment.BG_NONE
        playerViewModel =
            playerViewModel ?: getCurrentActivity()?.let {
                ViewModelProvider(it).get(PlayerViewModel::class.java)
            } ?: ViewModelProvider(requireActivity()).get(PlayerViewModel::class.java)
        return super.onCreateView(inflater, container, savedInstanceState)
        // return super.onCreateView(inflater, container, savedInstanceState)
    }

    /** Workaround used to prevent controls overlay from showing and taking focus */
    private fun preventControlsOverlay(playerGlue: MediaPlayerGlue) = view?.postDelayed({
        playerGlue.host.showControlsOverlay(false)
        playerGlue.host.hideControlsOverlay(false)
    }, 10)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.setBackgroundColor(Color.BLACK)
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        playerViewModel!!.videoSize.observe(viewLifecycleOwner) {
            generateGlue(true)
        }

        playerViewModel!!.selectedSource.observe(viewLifecycleOwner) {
            playerViewModel?.videoSize?.postValue(null)
        }

        if (savedInstanceState != null) {
            playbackPosition = savedInstanceState.getLong(STATE_RESUME_POSITION)
            resizeMode = savedInstanceState.getInt(RESIZE_MODE_KEY)
        }

//        surfaceView?.addOnLayoutChangeListener { view, i, i2, i3, i4, i5, i6, i7, i8 ->
//            println("LAYOUT CHANGED!")
//        }

    }


    override fun onStart() {
        super.onStart()
        if (data != null && data?.episodeIndex != null) {
            val pro = getCurrentContext()!!.getViewPosDur(data!!.slug, data?.episodeIndex!!)
            if (pro.pos > 0 && pro.dur > 0 && (pro.pos * 100 / pro.dur) < 95) { // UNDER 95% RESUME
                playbackPosition = pro.pos
            }
        }
    }

    override fun onResume() {
        super.onResume()

        // Disables ssl check
        val sslContext: SSLContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf(SSLTrustManager()), SecureRandom())
        sslContext.createSSLEngine()
        HttpsURLConnection.setDefaultHostnameVerifier { _: String, _: SSLSession ->
            true
        }
        HttpsURLConnection.setDefaultSSLSocketFactory(sslContext.socketFactory)

        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
        if (this::exoPlayer.isInitialized) {
            mediaSessionConnector.setPlayer(exoPlayer)
        }
        mediaSession.isActive = true
        hasBeenInPlayer = true
        isInPlayer = true
        onPlayerNavigated.invoke(true)
        loadAndPlay()
        handler.postDelayed(checkProgressAction, 5000L)
        // Kick off metadata update task which runs periodically in the main thread
        //view?.postDelayed(updateMetadataTask, METADATA_UPDATE_INTERVAL_MILLIS)
    }

    /**
     * Deactivates and removes callbacks from [MediaSessionCompat] since the [Player] instance is
     * destroyed in onStop and required metadata could be missing.
     */
    override fun onPause() {
        super.onPause()

        if (this::playerGlue.isInitialized) {
            playerGlue.pause()
        }
        mediaSession.isActive = false
        mediaSessionConnector.setPlayer(null)

        /*view?.post {
            // Launch metadata update task one more time as the fragment becomes paused to ensure
            //  that we have the most up-to-date information
            updateMetadataTask.run()

            // Cancel all future metadata update tasks
            view?.removeCallbacks(updateMetadataTask)
        }*/
    }

    /** Do all final cleanup in onDestroy */
    override fun onDestroy() {
        getCurrentContext()?.theme?.applyStyle(R.style.customText, true)
        super.onDestroy()
        getCurrentContext()?.savePos()
        releasePlayer()
        mediaSession.release()
        onPlayerNavigated.invoke(false)
        isInPlayer = false
        handler.removeCallbacks(checkProgressAction)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        getCurrentContext()?.savePos()

        if (this::exoPlayer.isInitialized) {
            outState.putLong(STATE_RESUME_POSITION, exoPlayer.currentPosition)
        }
        outState.putInt(RESIZE_MODE_KEY, resizeMode)
        super.onSaveInstanceState(outState)

    }

    override fun onAttach(context: Context) {
        super.onAttach(context)

        masterViewModel?.playerData?.value?.let {
            data = it
            episodeOffset = if (data?.card?.episodes?.filter { it.episode == "0" }.isNullOrEmpty()) 0 else -1
        }

//        arguments?.getString(DATA)?.let {
//            data = it.toKotlinObject()
//            episodeOffset = if (data?.card?.episodes?.filter { it.episode == "0" }.isNullOrEmpty()) 0 else -1
//        }
    }

    companion object {
        const val DATA = "data"

        private val TAG = PlayerFragmentTv::class.java.simpleName

        /** How often the player refreshes its views in milliseconds */
        private const val PLAYER_UPDATE_INTERVAL_MILLIS: Int = 100

        /** Default time used when skipping playback in milliseconds */

        private val SKIP_OP_MILLIS: Long = TimeUnit.SECONDS.toMillis(85)
        var isInPlayer: Boolean = false

        /*fun newInstance(data: PlayerData) =
            PlayerFragmentTv().apply {
                arguments = Bundle().apply {
                    //println(data)
                    putString(DATA, mapper.writeValueAsString(data))
                }
            }*/

//        fun newInstance() =
//            PlayerFragmentTv().apply {
//                arguments = Bundle().apply {
//                    masterViewModel?.playerData?.value?.let {
//                        putString(DATA, mapper.writeValueAsString(it))
//                    }
//                }
//            }
    }
}
