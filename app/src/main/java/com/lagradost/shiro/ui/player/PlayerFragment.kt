package com.lagradost.shiro.ui.player

import ANILIST_SHOULD_UPDATE_LIST
import ANILIST_TOKEN_KEY
import DataStore.getKey
import DataStore.removeKey
import DataStore.setKey
import MAL_SHOULD_UPDATE_LIST
import MAL_TOKEN_KEY
import PLAYBACK_SPEED_KEY
import RESIZE_MODE_KEY
import VIEW_DUR_KEY
import VIEW_POS_KEY
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.app.Dialog
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Context.AUDIO_SERVICE
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.database.ContentObserver
import android.graphics.Color
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.view.*
import android.view.View.*
import android.view.WindowManager.LayoutParams.*
import android.view.animation.*
import android.widget.AbsListView.CHOICE_MODE_SINGLE
import android.widget.ArrayAdapter
import android.widget.ProgressBar
import android.widget.Toast
import android.widget.Toast.LENGTH_LONG
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.TextViewCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.preference.PreferenceManager
import androidx.transition.Fade
import androidx.transition.Transition
import androidx.transition.TransitionManager
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import com.google.android.exoplayer2.*
import com.google.android.exoplayer2.C.TIME_UNSET
import com.google.android.exoplayer2.database.ExoDatabaseProvider
import com.google.android.exoplayer2.source.DefaultMediaSourceFactory
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector
import com.google.android.exoplayer2.ui.AspectRatioFrameLayout
import com.google.android.exoplayer2.ui.TimeBar
import com.google.android.exoplayer2.upstream.DataSource
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource
import com.google.android.exoplayer2.upstream.cache.CacheDataSource
import com.google.android.exoplayer2.upstream.cache.LeastRecentlyUsedCacheEvictor
import com.google.android.exoplayer2.upstream.cache.SimpleCache
import com.google.android.exoplayer2.util.MimeTypes
import com.google.android.exoplayer2.util.Util
import com.google.android.exoplayer2.video.VideoSize
import com.jaredrummler.cyanea.Cyanea
import com.lagradost.shiro.R
import com.lagradost.shiro.ui.MainActivity
import com.lagradost.shiro.ui.MainActivity.Companion.focusRequest
import com.lagradost.shiro.ui.MainActivity.Companion.isInPIPMode
import com.lagradost.shiro.ui.MainActivity.Companion.masterViewModel
import com.lagradost.shiro.ui.downloads.DownloadFragmentChild.Companion.getSortedEpisodes
import com.lagradost.shiro.ui.library.LibraryFragment.Companion.libraryViewModel
import com.lagradost.shiro.ui.player.PlayerActivity.Companion.playerActivity
import com.lagradost.shiro.ui.result.ResultFragment.Companion.publicResultViewModel
import com.lagradost.shiro.ui.toPx
import com.lagradost.shiro.utils.*
import com.lagradost.shiro.utils.AniListApi.Companion.fromIntToAnimeStatus
import com.lagradost.shiro.utils.AniListApi.Companion.getDataAboutId
import com.lagradost.shiro.utils.AniListApi.Companion.postDataAboutId
import com.lagradost.shiro.utils.AppUtils.getCurrentActivity
import com.lagradost.shiro.utils.AppUtils.getCurrentContext
import com.lagradost.shiro.utils.AppUtils.getNavigationBarHeight
import com.lagradost.shiro.utils.AppUtils.getStatusBarHeight
import com.lagradost.shiro.utils.AppUtils.getVideoContentUri
import com.lagradost.shiro.utils.AppUtils.getViewKey
import com.lagradost.shiro.utils.AppUtils.getViewPosDur
import com.lagradost.shiro.utils.AppUtils.guaranteedContext
import com.lagradost.shiro.utils.AppUtils.hideKeyboard
import com.lagradost.shiro.utils.AppUtils.hideSystemUI
import com.lagradost.shiro.utils.AppUtils.notNull
import com.lagradost.shiro.utils.AppUtils.requestAudioFocus
import com.lagradost.shiro.utils.AppUtils.setViewPosDur
import com.lagradost.shiro.utils.AppUtils.showSystemUI
import com.lagradost.shiro.utils.Coroutines.main
import com.lagradost.shiro.utils.MALApi.Companion.getDataAboutMalId
import com.lagradost.shiro.utils.MALApi.Companion.malStatusAsString
import com.lagradost.shiro.utils.MALApi.Companion.setScoreRequest
import com.lagradost.shiro.utils.ShiroApi.Companion.USER_AGENT
import com.lagradost.shiro.utils.ShiroApi.Companion.fmod
import com.lagradost.shiro.utils.ShiroApi.Companion.loadLinks
import com.lagradost.shiro.utils.VideoDownloadManager.isScopedStorage
import com.lagradost.shiro.utils.mvvm.logError
import com.lagradost.shiro.utils.mvvm.normalSafeApiCall
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.android.synthetic.main.bottom_sheet.*
import kotlinx.android.synthetic.main.player.*
import kotlinx.android.synthetic.main.player_custom_layout.*
import kotlinx.android.synthetic.main.yt_overlay.*
import java.io.File
import java.security.SecureRandom
import java.util.*
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSession
import kotlin.concurrent.fixedRateTimer
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.properties.Delegates

const val STATE_RESUME_WINDOW = "resumeWindow"
const val STATE_RESUME_POSITION = "resumePosition"
const val STATE_PLAYER_FULLSCREEN = "playerFullscreen"
const val STATE_PLAYER_PLAYING = "playerOnPlay"
const val ACTION_MEDIA_CONTROL = "media_control"
const val EXTRA_CONTROL_TYPE = "control_type"
const val PLAYBACK_SPEED = "playback_speed"
const val PLAYER_FRAGMENT_TAG = "PLAYER_FRAGMENT_TAG"

// TITLE AND URL OR CARD MUST BE PROVIDED
// EPISODE AND SEASON SHOULD START AT 0
data class PlayerData(
    @JsonProperty("title") var title: String?,
    @JsonProperty("url") var url: String?,
    @JsonProperty("episodeIndex") var episodeIndex: Int?,
    @JsonProperty("seasonIndex") var seasonIndex: Int?,
    @JsonProperty("card") val card: ShiroApi.Companion.AnimePageNewData?,
    @JsonProperty("startAt") val startAt: Long?,
    @JsonProperty("slug") val slug: String,
    @JsonProperty("anilistID") val anilistID: Int? = null,
    @JsonProperty("malID") val malID: Int? = null,
    @JsonProperty("fillerEpisodes") val fillerEpisodes: HashMap<Int, Boolean>? = null
)

enum class PlayerEventType(val value: Int) {
    Stop(-1),
    Pause(0),
    Play(1),
    SeekForward(2),
    SeekBack(3),
    SkipCurrentChapter(4),
    NextEpisode(5),
    PlayPauseToggle(6)
}

class PlayerFragment : Fragment() {
    var data: PlayerData? = null
    private val mapper = JsonMapper.builder().addModule(KotlinModule())
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false).build()

    private class SettingsContentObserver(handler: Handler?) : ContentObserver(handler) {
        private val audioManager = activity?.getSystemService(AUDIO_SERVICE) as? AudioManager
        override fun onChange(selfChange: Boolean) {
            val currentVolume = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC)
            val maxVolume = audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val progressBarRight = activity?.findViewById<ProgressBar>(R.id.progressBarRight)
            if (currentVolume != null && maxVolume != null) {
                progressBarRight?.progress = currentVolume * 100 / maxVolume
            }
        }
    }

    private val volumeObserver = SettingsContentObserver(
        Handler(
            Looper.getMainLooper()
        )
    )

    companion object {
        var isInPlayer: Boolean = false
        var onPlayerNavigated = Event<Boolean>()
        val activity = getCurrentActivity()

        /*fun newInstance(data: PlayerData) =
            PlayerFragment().apply {
                arguments = Bundle().apply {
                    //println(data)
                    putString("data", mapper.writeValueAsString(data))
                }
            }*/

//        fun newInstance() =
//            PlayerFragment().apply {
//                arguments = Bundle().apply {
//                    masterViewModel?.playerData?.value?.let {
//                        putString("data", mapper.writeValueAsString(it))
//                    }
//                }
//            }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onAttach(context: Context) {
        super.onAttach(context)

        masterViewModel?.playerData?.value?.let {
            data = it
            episodeOffset = if (data?.card?.episodes?.filter { it.episode == "0" }.isNullOrEmpty()) 0 else -1
        }
    }

    var isLocked = false
    private var isShowing = true
    private lateinit var exoPlayer: SimpleExoPlayer

    //private val extractorLinks = mutableListOf<ExtractorLink>()
    private val extractorLinks = mutableListOf<ExtractorLink>()

    // private val url = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4"
    private var currentWindow = 0
    private var playbackPosition: Long = 0
    private var isFullscreen = false
    private var isPlayerPlaying = true
    private var currentX = 0F
    private var currentY = 0F
    private var isMovingStartTime = 0L
    private var skipTime = 0L
    private var hasPassedSkipLimit = false
    private var preventHorizontalSwipe = false
    private var hasPassedVerticalSwipeThreshold = false
    private var cachedVolume = 0f

    private var playerViewModel: PlayerViewModel? = null

    private var isCurrentlyPlaying: Boolean = false
    private var playbackSpeed: Float? = getCurrentActivity()!!.getKey(PLAYBACK_SPEED_KEY, 1f)

    private val settingsManager = PreferenceManager.getDefaultSharedPreferences(getCurrentContext()!!)!!
    private val swipeEnabled = settingsManager.getBoolean("swipe_enabled", true)
    private val swipeVerticalEnabled = settingsManager.getBoolean("swipe_vertical_enabled", true)
    private val skipOpEnabled = true//settingsManager!!.getBoolean("skip_op_enabled", false)
    val doubleTapEnabled = settingsManager.getBoolean("double_tap_enabled", false)
    private val playBackSpeedEnabled = true//settingsManager!!.getBoolean("playback_speed_enabled", false)
    private val playerResizeEnabled = true//settingsManager!!.getBoolean("player_resize_enabled", false)
    private val doubleTapTime = settingsManager.getInt("dobule_tap_time", 10)
    private val fastForwardTime = settingsManager.getInt("fast_forward_button_time", 10)
    private val autoPlayEnabled = settingsManager.getBoolean("autoplay_enabled", true)
    private val fullscreenNotch = settingsManager.getBoolean("fullscreen_notch", true)
    private val hidePlayerFFWD = settingsManager.getBoolean("hide_player_ffwd", false)
    private val skipFillers = settingsManager.getBoolean("skip_fillers", false)
    private val hidePrevButton = settingsManager.getBoolean("hide_prev_episode_button", false)

    private val cacheSize = 100L * 1024L * 1024L // 100 mb
    private var simpleCache: SimpleCache? = null
    private var statusBarHeight by Delegates.notNull<Int>()
    private var navigationBarHeight by Delegates.notNull<Int>()

    private var sources: Pair<Int?, List<ExtractorLink>?> = Pair(null, null)

    private var lastSyncedEpisode = -1

    // To prevent watching everything while sleeping
    private var episodesSinceInteraction = 0

    // SSL
    private val ignoreSSL = settingsManager.getBoolean("ignore_ssl", false)
    private val defaultVerifier = HttpsURLConnection.getDefaultHostnameVerifier()
    private val defaultFactory = HttpsURLConnection.getDefaultSSLSocketFactory()

    // Auto hide
    private var hideAtMs by Delegates.notNull<Long>()
    private val handler = Handler()
    private val showTimeoutMs = 3000L
    private val hideAction = Runnable { hide() }
    private val nextEpisodeAction = Runnable { playNextEpisode() }
    private val checkProgressAction = Runnable { checkProgress() }

    // To show episode 0
    private var episodeOffset = 0

    //private val restoreLockClickable = Runnable { video_lock?.isClickable = true }
    private var timer: Timer? = null
    //private val linkLoadedEvent = Event<ExtractorLink>()

    private val resizeModes = listOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT,
        AspectRatioFrameLayout.RESIZE_MODE_FILL,
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
    )
    private var resizeMode = getCurrentContext()!!.getKey(RESIZE_MODE_KEY, 0) ?: 0

    // Made getters because this can change if user is in a split view for example
    val width: Int
        get() {
            //Resources.getSystem().displayMetrics.heightPixels
            return player_view?.width ?: 0
        }

    val height: Int
        get() {
            //Resources.getSystem().displayMetrics.widthPixels
            return player_view?.height ?: 0
        }

    private var prevDiffX = 0.0

    // Prevent clicking next episode button multiple times
    private var isLoadingNextEpisode = false

    private fun canPlayEpisode(next: Boolean): Boolean {
        if (data?.card == null || data?.episodeIndex == null) {
            return false
        }
        return try {
            if (next) data!!.card!!.episodes.size > data!!.episodeIndex!! + 1 else data!!.episodeIndex!! - 1 >= 0
            //MainActivity.canPlayNextEpisode(data?.card!!, data?.seasonIndex!!, data?.episodeIndex!!).isFound
        } catch (e: NullPointerException) {
            logError(e)
            false
        }
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
        // to prevent changing when doing distinctBy or sortedBy leading to ConcurrentModificationException
        val safeLinks = extractorLinks.toTypedArray()

        if (
        // Prevent editing the text post-player
            !isCurrentlyPlaying &&
            // Add the link post url check
            !link.name.startsWith("Shiro")
        ) {
            main {
                links_loaded_text?.text = "${safeLinks.distinctBy { it.url }.size} - Loaded ${link.name}"
                quickstart_btt?.visibility = VISIBLE
            }
        }
        sources = Pair(data?.episodeIndex, safeLinks.sortedBy { -it.quality }.distinctBy { it.url })

        // Quickstart
        if (link.quality == Qualities.UHD.value || link.quality == Qualities.FullHd.value  /*Shiro().name*/) {
            main {
                initPlayerIfPossible(link)
            }
        }
    }

    private fun getCurrentUrl(): ExtractorLink? {
        if (data?.url != null) return ExtractorLink(
            "Downloaded",
            data?.url!!.removePrefix("file://").replace("%20", " "),
            "",
            Qualities.Unknown.value
        )
        val index = maxOf(sources.second?.indexOf(playerViewModel?.selectedSource?.value) ?: -1, 0)
        return sources.second?.getOrNull(index)
    }

    private fun getCurrentTitle(): String {
        val postTitle = playerViewModel?.videoSize?.value?.let { videoSize ->
            "\n${videoSize.width}x${videoSize.height}${getCurrentUrl()?.name?.let { " - $it" } ?: ""}"
        } ?: ""
        val fillerInfo =
            if (data?.fillerEpisodes?.get((data?.episodeIndex ?: -1) + 1) == true) " (Filler) " else ""
        if (data?.title != null) return data?.title!! + fillerInfo + postTitle

        val isMovie: Boolean =
            data?.card?.episodes?.size == 1 && data?.card?.anime?.status?.lowercase() == "finished airing"
        // data?.card!!.cdndata?.seasons.size == 1 && data?.card!!.cdndata?.seasons[0].episodes.size == 1
        var preTitle = ""
        if (!isMovie) {
            preTitle = data?.episodeIndex?.let {
                "Episode ${it + 1 + episodeOffset} · "
            } ?: ""
        }

        // Replaces with "" if it's null
        return preTitle + (data?.card?.anime?.title ?: "") + fillerInfo + postTitle
    }

    private fun savePos() {
        if (this::exoPlayer.isInitialized) {
            if (((data?.slug != null
                        && data?.seasonIndex != null
                        && data?.episodeIndex != null) || data?.card != null)
                && exoPlayer.duration > 0 && exoPlayer.currentPosition > 0
            ) {
                context?.setViewPosDur(data!!, exoPlayer.currentPosition, exoPlayer.duration)
            }
        }
    }

    override fun onDestroy() {
        savePos()
        releasePlayer()

        // DON'T SAVE DATA OF TRAILERS
        isInPlayer = false
        onPlayerNavigated.invoke(false)
        activity?.showSystemUI()
        MainActivity.onPlayerEvent -= ::handlePlayerEvent
        MainActivity.onAudioFocusEvent -= ::handleAudioFocusEvent
        activity?.contentResolver?.unregisterContentObserver(volumeObserver)

        // Restores SSL
        if (ignoreSSL) {
            HttpsURLConnection.setDefaultHostnameVerifier(defaultVerifier)
            HttpsURLConnection.setDefaultSSLSocketFactory(defaultFactory)
        }

        // restoring screen brightness
        val lp = activity?.window?.attributes
        lp?.screenBrightness = BRIGHTNESS_OVERRIDE_NONE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lp?.layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
        }
        activity?.window?.attributes = lp
        handler.removeCallbacks(checkProgressAction)

        super.onDestroy()
        //MainActivity.showSystemUI()
    }

    private fun updateLock() {
        video_locked_img.setImageResource(if (isLocked) R.drawable.video_locked else R.drawable.video_unlocked)
        video_locked_img.setColorFilter(
            if (isLocked && activity != null) Cyanea.instance.primary
            else Color.WHITE
        )

        setIsClickable(!isLocked)
        //println("UPDATED LOCK $isClick")

        val fadeTo = if (!isLocked) 1f else 0f
        val fadeAnimation = AlphaAnimation(1f - fadeTo, fadeTo)

        fadeAnimation.duration = 100
        fadeAnimation.fillAfter = true

        shadow_overlay.startAnimation(fadeAnimation)
    }

    private fun setIsClickable(isClickable: Boolean) {
        main {
            exo_play?.isClickable = isClickable
            exo_pause?.isClickable = isClickable
            exo_ffwd?.isClickable = isClickable
            exo_rew?.isClickable = isClickable
            exo_prev?.isClickable = isClickable
            video_go_back?.isClickable = isClickable
            prev_episode_btt?.isClickable = isClickable
            next_episode_btt?.isClickable = isClickable
            playback_speed_btt?.isClickable = isClickable
            skip_op?.isClickable = isClickable
            resize_player?.isClickable = isClickable
            sources_btt?.isClickable = isClickable

            // Clickable doesn't seem to work on com.google.android.exoplayer2.ui.DefaultTimeBar
            //exo_progress.isClickable = isClick
            exo_progress?.isEnabled = isClickable

        }
    }

    private fun changePlayerTextVisibility(visible: Boolean) {
        player_speed_text?.isVisible = visible
        sources_text?.isVisible = visible
        resize_text?.isVisible = visible
        skip_op_text?.isVisible = visible
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        val isInMultiWindow = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            activity?.isInMultiWindowMode ?: false
        } else false

        handler.postDelayed({
            unFuckLayout()
        }, 200)
        handler.postDelayed({
            unFuckLayout()
        }, 1000)
        handler.postDelayed({
            unFuckLayout()
        }, 5000)

        changePlayerTextVisibility(newConfig.orientation != SCREEN_ORIENTATION_PORTRAIT && !isInMultiWindow)
        super.onConfigurationChanged(newConfig)
    }

    private var receiver: BroadcastReceiver? = null
    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean) {
        isInPIPMode = isInPictureInPictureMode

        if (isInPictureInPictureMode) {
            // Hide the full-screen UI (controls, etc.) while in picture-in-picture mode.
            player_holder?.alpha = 0f
            receiver = object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context,
                    intent: Intent,
                ) {
                    if (ACTION_MEDIA_CONTROL != intent.action) {
                        return
                    }
                    handlePlayerEvent(intent.getIntExtra(EXTRA_CONTROL_TYPE, 0))
                }
            }
            nav_view?.visibility = GONE
            val filter = IntentFilter()
            filter.addAction(
                ACTION_MEDIA_CONTROL
            )
            activity?.registerReceiver(receiver, filter)
            updatePIPModeActions()
        } else {
            // Restore the full-screen UI.
            player_holder?.alpha = 1f
            receiver?.let {
                activity?.unregisterReceiver(it)
            }
            nav_view?.visibility = VISIBLE

            activity?.hideSystemUI()
            view?.hideKeyboard()
        }
        unFuckLayout()

    }

    private fun getPen(code: PlayerEventType): PendingIntent {
        return getPen(code.value)
    }

    private fun getPen(code: Int): PendingIntent {
        return PendingIntent.getBroadcast(
            activity,
            code,
            Intent("media_control").putExtra("control_type", code),
            0
        )
    }

    @SuppressLint("NewApi")
    private fun getRemoteAction(id: Int, title: String, event: PlayerEventType): RemoteAction {
        return RemoteAction(
            Icon.createWithResource(activity, id),
            title,
            title,
            getPen(event)
        )
    }

    @SuppressLint("NewApi")
    private fun updatePIPModeActions() {
        if (!MainActivity.isInPIPMode || !this::exoPlayer.isInitialized) return

        val actions: ArrayList<RemoteAction> = ArrayList()

        actions.add(getRemoteAction(R.drawable.netflix_skip_back, "Go Back", PlayerEventType.SeekBack))

        if (exoPlayer.isPlaying) {
            actions.add(getRemoteAction(R.drawable.exo_controls_pause, "Pause", PlayerEventType.Pause))
        } else {
            actions.add(getRemoteAction(R.drawable.exo_controls_play, "Play", PlayerEventType.Play))
        }

        actions.add(getRemoteAction(R.drawable.netflix_skip_forward, "Go Forward", PlayerEventType.SeekForward))
        activity?.setPictureInPictureParams(PictureInPictureParams.Builder().setActions(actions).build())
    }

    private fun onClickChange() {
        isShowing = !isShowing
        if (isShowing) {
            hideAfterTimeout()
        }
        setIsClickable(isShowing && !isLocked)

        val titleMove = if (isShowing) 0f else -50.toPx.toFloat()
        video_title?.let {
            ObjectAnimator.ofFloat(it, "translationY", titleMove).apply {
                duration = 200
                start()
            }
        }
        val playerBarMove = if (isShowing) 0f else 50.toPx.toFloat()
        bottom_player_bar?.let {
            ObjectAnimator.ofFloat(it, "translationY", playerBarMove).apply {
                duration = 200
                start()
            }
        }

        val fadeTo = if (isShowing) 1f else 0f
        val fadeAnimation = AlphaAnimation(1f - fadeTo, fadeTo)

        val time = 100L
        fadeAnimation.duration = time
        fadeAnimation.fillAfter = true

        video_lock_holder?.startAnimation(fadeAnimation)
        // To prevent UI bug when clicking twice when animating
        video_lock?.isClickable = isShowing
        //handler.postDelayed(restoreLockClickable, time + 50L)

        bottom_player_bar?.startAnimation(fadeAnimation)
        if (!isLocked || video_holder?.alpha != 1.0f || shadow_overlay?.alpha != 1.0f || bottom_player_bar_button_holder?.alpha != 1.0f) {
            video_holder?.startAnimation(fadeAnimation)
            bottom_player_bar_button_holder?.startAnimation(fadeAnimation)
            shadow_overlay?.startAnimation(fadeAnimation)
        }
    }

    private fun handleAudioFocusEvent(event: Boolean) {
        if (this::exoPlayer.isInitialized) {
            if (!event) exoPlayer.pause()
        }
    }

    private fun handlePlayerEvent(event: PlayerEventType) {
        if (this::exoPlayer.isInitialized) {
            handlePlayerEvent(event.value)
        }
    }

    private fun handlePlayerEvent(event: Int) {
        when (event) {
            PlayerEventType.Play.value -> exoPlayer.play()
            PlayerEventType.Pause.value -> exoPlayer.pause()
            PlayerEventType.SeekBack.value -> seekTime(-fastForwardTime * 1000L)
            PlayerEventType.SeekForward.value -> seekTime(fastForwardTime * 1000L)
        }
    }

    private fun forceLetters(inp: Int, letters: Int = 2): String {
        val added: Int = letters - inp.toString().length
        return if (added > 0) {
            "0".repeat(added) + inp.toString()
        } else {
            inp.toString()
        }
    }

    private fun convertTimeToString(time: Double): String {
        val sec = time.toInt()
        val rsec = sec % 60
        val min = ceil((sec - rsec) / 60.0).toInt()
        val rmin = min % 60
        val h = ceil((min - rmin) / 60.0).toInt()
        //int rh = h;// h % 24;
        return (if (h > 0) forceLetters(h) + ":" else "") + (if (rmin >= 0 || h >= 0) forceLetters(rmin) + ":" else "") + forceLetters(
            rsec
        )
    }

    // To prevent swiping gesture when using statusbar
    private var isValidTouch = false

    private fun handleMotionEvent(motionEvent: MotionEvent) {
        // No swiping on unloaded
        // https://exoplayer.dev/doc/reference/constant-values.html
        if (isLocked || exoPlayer.duration == TIME_UNSET || (!swipeEnabled && !swipeVerticalEnabled)) return
        val audioManager = activity?.getSystemService(AUDIO_SERVICE) as? AudioManager

        fun recordCoordinates() {
            currentX = motionEvent.rawX
            currentY = motionEvent.rawY
            //println("DOWN: " + currentX)
            isMovingStartTime = exoPlayer.currentPosition
        }

        when (motionEvent.action) {
            MotionEvent.ACTION_DOWN -> {
                // SO YOU CAN PULL DOWN STATUSBAR OR NAVBAR
                if (motionEvent.rawY > statusBarHeight && motionEvent.rawX < width - navigationBarHeight) {
                    isValidTouch = true
                    recordCoordinates()
                    //println("DOWN: " + currentX)
                } else {
                    isValidTouch = false
                }

            }
            MotionEvent.ACTION_MOVE -> {
                if (!isValidTouch) return
                if (swipeVerticalEnabled) {
                    if (currentY == 0f && currentX == 0f) {
                        recordCoordinates()
                    }
                    val distanceMultiplierY = 2F
                    val distanceY = if (currentY != 0f) (motionEvent.rawY - currentY) * distanceMultiplierY else 0f
                    val diffY = distanceY * 2.0 / height

                    // Forces 'smooth' moving preventing a bug where you
                    // can make it think it moved half a screen in a frame

                    if (abs(diffY) >= 0.2 && !hasPassedSkipLimit) {
                        hasPassedVerticalSwipeThreshold = true
                        preventHorizontalSwipe = true
                    }
                    if (hasPassedVerticalSwipeThreshold && abs(diffY) <= 0.8) {
                        if (currentX > width * 0.5) {
                            if (audioManager != null && progressBarLeftHolder != null) {
                                val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                                val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

                                if (progressBarLeftHolder.alpha <= 0f) {
                                    cachedVolume = currentVolume.toFloat() / maxVolume.toFloat()
                                }

                                progressBarLeftHolder?.alpha = 1f
                                val vol = minOf(
                                    1f,
                                    cachedVolume - diffY.toFloat() * 0.5f
                                ) // 0.05f *if (diffY > 0) 1 else -1
                                cachedVolume = vol
                                //progressBarRight?.progress = ((1f - alpha) * 100).toInt()

                                progressBarLeft?.max = 100 * 100
                                progressBarLeft?.progress = ((vol) * 100 * 100).toInt()

                                if (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                                        audioManager.isVolumeFixed
                                    } else {
                                        false
                                    }
                                ) {
                                    exoPlayer.volume = minOf(1f, maxOf(vol, 0f))
                                } else {
                                    // audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, vol*, 0)
                                    val desiredVol = (vol * maxVolume).toInt()
                                    if (desiredVol != currentVolume) {
                                        val newVolumeAdjusted =
                                            if (desiredVol < currentVolume) AudioManager.ADJUST_LOWER else AudioManager.ADJUST_RAISE

                                        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, newVolumeAdjusted, 0)
                                    }
                                    //audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVolume, 0)
                                }
                                currentY = motionEvent.rawY
                            }
                        } else if (progressBarRightHolder != null) {
                            progressBarRightHolder?.alpha = 1f
                            // https://developer.android.com/reference/android/view/WindowManager.LayoutParams#screenBrightness
                            val lp = activity?.window?.attributes
                            val currentBrightness = if (lp?.screenBrightness ?: -1.0f <= 0f) (Settings.System.getInt(
                                context?.contentResolver,
                                Settings.System.SCREEN_BRIGHTNESS
                            ).toFloat() / 255)
                            else lp?.screenBrightness!!

                            val alpha = minOf(
                                maxOf(
                                    0.005f, // BRIGHTNESS_OVERRIDE_OFF doesn't seem to work
                                    currentBrightness - diffY.toFloat() * 0.5f
                                ), 1.0f
                            )// 0.05f *if (diffY > 0) 1 else -1
                            lp?.screenBrightness = alpha
                            //println(alpha)
                            activity?.window?.attributes = lp
                            //brightness_overlay?.alpha = alpha

                            //progressBarRight?.max = 100 * 100
                            progressBarRight?.progress = (alpha * 100 * 100).toInt()

                            currentY = motionEvent.rawY
                        }
                    }
                }

                if (swipeEnabled) {
                    if (currentY == 0f && currentX == 0f) {
                        recordCoordinates()
                    }
                    val distanceMultiplierX = 2F
                    val distanceX = if (currentX != 0f) (motionEvent.rawX - currentX) * distanceMultiplierX else 0f
                    val diffX = distanceX * 2.0 / width
                    if (abs(diffX - prevDiffX) > 0.5) {
                        return
                    }
                    prevDiffX = diffX

                    skipTime = ((exoPlayer.duration * (diffX * diffX) / 10) * (if (diffX < 0) -1 else 1)).toLong()
                    if (isMovingStartTime + skipTime < 0) {
                        skipTime = -isMovingStartTime
                    } else if (isMovingStartTime + skipTime > exoPlayer.duration) {
                        skipTime = exoPlayer.duration - isMovingStartTime
                    }
                    if ((abs(skipTime) > 3000 || hasPassedSkipLimit) && !preventHorizontalSwipe) {
                        hasPassedSkipLimit = true
                        val timeString =
                            "${convertTimeToString((isMovingStartTime + skipTime) / 1000.0)} [${(if (abs(skipTime) < 1000) "" else (if (skipTime > 0) "+" else "-"))}${
                                convertTimeToString(abs(skipTime / 1000.0))
                            }]"
                        timeText?.alpha = 1f
                        timeText?.text = timeString
                    } else {
                        timeText?.alpha = 0f
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!isValidTouch) return
                currentX = 0f
                currentY = 0f
                val transition: Transition = Fade()
                episodesSinceInteraction = 0
                transition.duration = 1000

                player_holder?.let {
                    TransitionManager.beginDelayedTransition(it, transition)
                }

                if (abs(skipTime) > 3000 && !preventHorizontalSwipe && swipeEnabled) {
                    exoPlayer.seekTo(maxOf(minOf(skipTime + isMovingStartTime, exoPlayer.duration), 0))
                }
                hasPassedSkipLimit = false
                hasPassedVerticalSwipeThreshold = false
                preventHorizontalSwipe = false
                prevDiffX = 0.0
                skipTime = 0

                timeText?.animate()?.alpha(0f)?.setDuration(200)
                    ?.setInterpolator(AccelerateInterpolator())?.start()
                progressBarRightHolder?.animate()?.alpha(0f)?.setDuration(200)
                    ?.setInterpolator(AccelerateInterpolator())?.start()
                progressBarLeftHolder?.animate()?.alpha(0f)?.setDuration(200)
                    ?.setInterpolator(AccelerateInterpolator())?.start()
                //val fadeAnimation = AlphaAnimation(1f, 0f)
                //fadeAnimation.duration = 100
                //fadeAnimation.fillAfter = true
                //progressBarLeftHolder.startAnimation(fadeAnimation)
                //progressBarRightHolder.startAnimation(fadeAnimation)
                //timeText.startAnimation(fadeAnimation)

            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        playerViewModel!!.videoSize.observe(viewLifecycleOwner) {
            video_title?.text = getCurrentTitle()
        }

        playerViewModel!!.selectedSource.observe(viewLifecycleOwner) {
            playerViewModel?.videoSize?.postValue(null)
        }

        navigationBarHeight = guaranteedContext(context).getNavigationBarHeight()
        statusBarHeight = guaranteedContext(context).getStatusBarHeight()

        shadow_overlay?.isVisible = !settingsManager.getBoolean("disable_player_shadow", false)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            next_episode_progressbar?.progressTintList = ColorStateList.valueOf(Cyanea.instance.primary)
            progressBarLeft.progressTintList = ColorStateList.valueOf(Cyanea.instance.primary)
            progressBarRight.progressTintList = ColorStateList.valueOf(Cyanea.instance.primary)
        }

//        val isInMultiWindow = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
//            activity?.isInMultiWindowMode ?: false
//        } else false
//
//        changePlayerTextVisibility(resources.configuration.orientation != SCREEN_ORIENTATION_PORTRAIT && !isInMultiWindow)

        guaranteedContext(context).contentResolver
            ?.registerContentObserver(
                Settings.System.CONTENT_URI, true, volumeObserver
            )

        exo_progress.setPlayedColor(Cyanea.instance.primary)

        MainActivity.onPlayerEvent += ::handlePlayerEvent
        MainActivity.onAudioFocusEvent += ::handleAudioFocusEvent

        hideKeyboard()

        updateLock()

        handler.postDelayed({
            unFuckLayout()
        }, 200)

        video_lock.setOnClickListener {
            updateHideTime()
            isLocked = !isLocked
            val fadeTo = if (isLocked) 0f else 1f

            val fadeAnimation = AlphaAnimation(1f - fadeTo, fadeTo)
            fadeAnimation.duration = 100
            //   fadeAnimation.startOffset = 100
            fadeAnimation.fillAfter = true
            video_holder.startAnimation(fadeAnimation)
            bottom_player_bar_button_holder?.startAnimation(fadeAnimation)

            updateLock()
        }
        cancel_next.setOnClickListener {
            cancelNextEpisode()
        }

        // Lazy hack
        play_next.setOnClickListener {
            next_episode_btt.performClick()
        }

        exo_progress.addListener(object : TimeBar.OnScrubListener {
            override fun onScrubStart(timeBar: TimeBar, position: Long) {
                updateHideTime(true)
                cancelNextEpisode()
            }

            override fun onScrubMove(timeBar: TimeBar, position: Long) {
                updateHideTime(true)
            }

            override fun onScrubStop(timeBar: TimeBar, position: Long, canceled: Boolean) {
                updateHideTime()
            }

        })

        ffwd_holder?.isVisible = !hidePlayerFFWD
        rew_holder?.isVisible = !hidePlayerFFWD

        /*
        player_holder.setOnTouchListener(OnTouchListener { v, event -> // ignore all touch events
            !isShowing
        })*/
        //println("RESIZE $resizeMode")
        player_view?.resizeMode = resizeModes[resizeMode]
        if (playerResizeEnabled) {
            resize_player.visibility = VISIBLE
            resize_player.setOnClickListener {
                updateHideTime()
                resizeMode = (resizeMode + 1).fmod(resizeModes.size)
                //println("RESIZE $resizeMode")
                context?.setKey(RESIZE_MODE_KEY, resizeMode)
                player_view.resizeMode = resizeModes[resizeMode]
                //exoPlayer.videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
            }
        } else {
            resize_player.visibility = GONE
        }
        quickstart_btt.setOnClickListener {
            initPlayerIfPossible()
        }


        class Listener : DoubleTapGestureListener(this) {
            val rootLayout = root_constraint_layout
            val secondsView = seconds_view
            val circleClipTapView = circle_clip_tap_view

            init {
                secondsView.isForward = true
                secondsView.changeConstraints(true, rootLayout, secondsView)

                circleClipTapView.arcSize =
                    getCurrentActivity()!!.resources.getDimensionPixelSize(R.dimen.dtpv_yt_arc_size).toFloat()
                circleClipTapView.circleColor =
                    ContextCompat.getColor(getCurrentActivity()!!, R.color.dtpv_yt_tap_circle_color)
                circleClipTapView.circleBackgroundColor =
                    ContextCompat.getColor(getCurrentActivity()!!, R.color.dtpv_yt_background_circle_color)
                circleClipTapView.animationDuration = 650
                secondsView.cycleDuration = 750
                TextViewCompat.setTextAppearance(secondsView.textView, R.style.YTOSecondsTextAppearance)

                circleClipTapView?.performAtEnd = {
                    /*val seekAnimation = AlphaAnimation(1f, 0f)
                    seekAnimation.duration = 200
                    seekAnimation.fillAfter = true
                    circleClipTapView?.alpha = 1f
                    circleClipTapView?.startAnimation(seekAnimation)*/
                    circleClipTapView?.animate()?.alpha(0f)?.setDuration(200)
                        ?.setInterpolator(AccelerateInterpolator())?.start()
                    secondsView?.visibility = INVISIBLE
                    secondsView?.seconds = 0
                    secondsView?.stop()
                }

            }

            private fun forwarding() {
                secondsView.seconds += doubleTapTime
                seekTime(doubleTapTime * 1000L)
            }

            private fun rewinding() {
                secondsView.seconds += doubleTapTime
                seekTime(doubleTapTime * -1000L)
            }


            override fun onDoubleClickRight(clicks: Int, posX: Float, posY: Float) {
                if (!isLocked) {
                    main {
                        circleClipTapView?.alpha = 1f
                        secondsView?.visibility = VISIBLE
                        secondsView?.start()
                        // First time tap or switched
                        //if (!secondsView.isForward) {
                        secondsView.changeConstraints(true, rootLayout, secondsView)
                        secondsView.apply {
                            isForward = true
                            seconds = 0
                        }
                        //}

                        // Cancel ripple and start new without triggering overlay disappearance
                        // (resetting instead of ending)
                        circleClipTapView.resetAnimation {
                            circleClipTapView.updatePosition(posX, posY, false)
                        }
                        forwarding()
                    }
                }
            }

            override fun onDoubleClickLeft(clicks: Int, posX: Float, posY: Float) {
                if (!isLocked) {
                    main {
                        circleClipTapView?.alpha = 1f
                        secondsView.visibility = VISIBLE
                        secondsView.start()

                        // First time tap or switched
                        //if (secondsView.isForward) {
                        secondsView.changeConstraints(false, rootLayout, secondsView)
                        secondsView.apply {
                            isForward = false
                            seconds = 0
                        }
                        //}

                        // Cancel ripple and start new without triggering overlay disappearance
                        // (resetting instead of ending)
                        circleClipTapView.resetAnimation {
                            circleClipTapView.updatePosition(posX, posY, true)
                        }
                        rewinding()
                    }
                }
            }

            override fun onSingleClick() {
                main {
                    onClickChange()
                }
                activity?.hideSystemUI()
            }
        }

        val detector = GestureDetector(this.context, Listener())
        val detectorListener = OnTouchListener { _, event ->
            handleMotionEvent(event)
            detector.onTouchEvent(event)
            return@OnTouchListener true
        }


        player_holder.setOnTouchListener(
            detectorListener
        )

        isInPlayer = true
        retainInstance = true // OTHERWISE IT WILL CAUSE A CRASH

        video_go_back.setOnClickListener {
            // Local player
            if (data?.title != null && data?.title == data?.url) {
                playerActivity?.finish()
                activity?.onBackPressed()
            } else {
                activity?.onBackPressed()
            }
        }
        video_go_back_holder.setOnClickListener {
            // Local player
            if (data?.title != null && data?.title == data?.url) {
                playerActivity?.finish()
                activity?.onBackPressed()
            } else {
                activity?.onBackPressed()
            }
        }
        exo_rew_text?.text = fastForwardTime.toString()
        exo_ffwd_text?.text = fastForwardTime.toString()

        exo_rew_text?.text = fastForwardTime.toString()
        exo_ffwd_text?.text = fastForwardTime.toString()
        exo_rew.setOnClickListener {
            updateHideTime()
            val rotateLeft = AnimationUtils.loadAnimation(context, R.anim.rotate_left)
            exo_rew.startAnimation(rotateLeft)

            val goLeft = AnimationUtils.loadAnimation(context, R.anim.go_left)
            goLeft.setAnimationListener(object : Animation.AnimationListener {
                override fun onAnimationStart(animation: Animation?) {
                }

                override fun onAnimationRepeat(animation: Animation?) {

                }

                override fun onAnimationEnd(animation: Animation?) {
                    exo_rew_text?.post { exo_rew_text?.text = "$fastForwardTime" }
                }
            })
            exo_rew_text?.startAnimation(goLeft)
            exo_rew_text?.text = "-$fastForwardTime"
            seekTime(fastForwardTime * -1000L)

        }
        exo_play.setOnClickListener {
            exoPlayer.play()
            updateHideTime()
            cancelNextEpisode()
        }
        exo_pause.setOnClickListener {
            exoPlayer.pause()
            updateHideTime()
            cancelNextEpisode()
        }
        exo_ffwd.setOnClickListener {
            updateHideTime()
            val rotateRight = AnimationUtils.loadAnimation(context, R.anim.rotate_right)
            exo_ffwd.startAnimation(rotateRight)

            val goRight = AnimationUtils.loadAnimation(context, R.anim.go_right)
            goRight.setAnimationListener(object : Animation.AnimationListener {
                override fun onAnimationStart(animation: Animation?) {
                }

                override fun onAnimationRepeat(animation: Animation?) {

                }

                override fun onAnimationEnd(animation: Animation?) {
                    exo_ffwd_text?.post { exo_ffwd_text?.text = "$fastForwardTime" }
                }
            })
            exo_ffwd_text?.startAnimation(goRight)
            exo_ffwd_text?.text = "+$fastForwardTime"
            seekTime(fastForwardTime * 1000L)
        }

        playback_speed_btt.visibility = if (playBackSpeedEnabled) VISIBLE else GONE
        playback_speed_btt.setOnClickListener {
            updateHideTime()
            // Lmao kind bad
            val dialog = Dialog(it.context, R.style.AlertDialogCustom)
            val speedsText = arrayOf("0.5x", "0.75x", "1x", "1.25x", "1.5x", "1.75x", "2x")
            val speedsNumbers = arrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

            //dialog = builder.create()
            dialog.setContentView(R.layout.bottom_sheet)
            dialog.bottom_sheet_top_bar?.visibility = GONE
            val res = dialog.sort_click

            res.choiceMode = CHOICE_MODE_SINGLE
            val arrayAdapter = ArrayAdapter<String>(it.context, R.layout.bottom_single_choice)
            arrayAdapter.addAll(ArrayList(speedsText.toList()))
            res.adapter = arrayAdapter
            res.setItemChecked(
                speedsNumbers.indexOf(playbackSpeed),
                true
            )
            res.setOnItemClickListener { _, _, which, _ ->
                playbackSpeed = speedsNumbers[which]
                context?.setKey(PLAYBACK_SPEED_KEY, playbackSpeed)
                val param = PlaybackParameters(playbackSpeed!!)
                exoPlayer.playbackParameters = param
                player_speed_text.text = "Speed (${playbackSpeed}x)".replace(".0x", "x")
                dialog.dismiss()
            }
            dialog.window?.setSoftInputMode(SOFT_INPUT_STATE_HIDDEN)
            dialog.show()

        }

        sources_btt.setOnClickListener {
            updateHideTime()
            sources.second?.let {
                val sourcesText = it.map { link -> link.name }
                val dialog = Dialog(guaranteedContext(context), R.style.AlertDialogCustom)

                val index = maxOf(sources.second?.indexOf(playerViewModel?.selectedSource?.value) ?: -1, 0)
                //dialog = builder.create()
                dialog.setContentView(R.layout.bottom_sheet)
                dialog.bottom_sheet_top_bar?.visibility = GONE
                val res = dialog.sort_click

                res.choiceMode = CHOICE_MODE_SINGLE
                val arrayAdapter = ArrayAdapter<String>(guaranteedContext(context), R.layout.bottom_single_choice)
                arrayAdapter.addAll(ArrayList(sourcesText))
                res.adapter = arrayAdapter
                res.setItemChecked(
                    index,
                    true
                )
                res.setOnItemClickListener { _, _, which, _ ->
                    playerViewModel?.selectedSource?.postValue(it[which])
                    savePos()
                    releasePlayer()
                    loadAndPlay()

                    dialog.dismiss()
                }
                dialog.window?.setSoftInputMode(SOFT_INPUT_STATE_HIDDEN)
                dialog.show()
            }
        }


        if (skipOpEnabled) {
            skip_op?.visibility = VISIBLE
            skip_op?.setOnClickListener {
                updateHideTime()
                seekTime(85000L)
            }
        }

        if (savedInstanceState != null) {
            currentWindow = savedInstanceState.getInt(STATE_RESUME_WINDOW)
            playbackPosition = savedInstanceState.getLong(STATE_RESUME_POSITION)
            isFullscreen = savedInstanceState.getBoolean(STATE_PLAYER_FULLSCREEN)
            isPlayerPlaying = savedInstanceState.getBoolean(STATE_PLAYER_PLAYING)
            resizeMode = savedInstanceState.getInt(RESIZE_MODE_KEY)
            playbackSpeed = savedInstanceState.getFloat(PLAYBACK_SPEED)
        }
    }

    private fun updateHideTime(neverHide: Boolean = false, interaction: Boolean = true) {
        if (interaction) episodesSinceInteraction = 0
        handler.removeCallbacks(hideAction)
        hideAtMs = SystemClock.uptimeMillis() + showTimeoutMs
        if (!neverHide) {
            handler.postDelayed(hideAction, showTimeoutMs)
        }
    }

    private fun hideAfterTimeout() {
        handler.removeCallbacks(hideAction)
        if (showTimeoutMs > 0) {
            hideAtMs = SystemClock.uptimeMillis() + showTimeoutMs
            handler.postDelayed(hideAction, showTimeoutMs)
        } else {
            hideAtMs = TIME_UNSET
        }
    }

    fun hide() {
        if (isShowing) {
            onClickChange()
            handler.removeCallbacks(hideAction)
            hideAtMs = TIME_UNSET
        }
    }

    fun queueNextEpisode() {
        if (episodesSinceInteraction <= 3) {
            activity?.let {
                main {
                    val time = 5000L
                    next_episode_overlay?.visibility = VISIBLE
                    next_episode_progressbar?.progress = 0
                    next_episode_progressbar?.let { progressBar ->
                        val animation =
                            ObjectAnimator.ofInt(
                                progressBar,
                                "progress",
                                progressBar.progress,
                                100
                            )
                        val animScale =
                            Settings.Global.getFloat(it.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
                        animation.duration = (time / animScale).toLong()
                        animation.setAutoCancel(true)
                        animation.interpolator = LinearInterpolator()
                        animation.start()
                    }

                    handler.postDelayed(nextEpisodeAction, time)
                    var timeLeft = time
                    timer = fixedRateTimer("timer", false, 0L, 1000) {
                        if (timeLeft < 0) this.cancel()
                        main {
                            next_episode_time_text?.text = "Next episode in ${(timeLeft / 1000).toInt()}..."
                            timeLeft -= 1000L
                        }
                    }
                }
            }
        }
    }

    private fun playNextEpisode() {
        // Hack
        episodesSinceInteraction++
        main {
            next_episode_btt?.performClick()
        }
    }

    private fun cancelNextEpisode() {
        if (autoPlayEnabled) {
            handler.removeCallbacks(nextEpisodeAction)
            timer?.cancel()
            next_episode_time_text?.text = ""
            next_episode_overlay?.visibility = GONE
        }
    }

    /*fun show() {
        if (!isShowing) {
            onClickChange()
        }
        // Call hideAfterTimeout even if already visible to reset the timeout.
        hideAfterTimeout()
    }*/


    private fun seekTime(time: Long) {
        exoPlayer.seekTo(maxOf(minOf(exoPlayer.currentPosition + time, exoPlayer.duration), 0))
    }

    private fun releasePlayer() {
        thread {
            simpleCache?.release()
        }
        main {
            if (this::exoPlayer.isInitialized) {
                isPlayerPlaying = exoPlayer.playWhenReady
                playbackPosition = exoPlayer.currentPosition
                currentWindow = exoPlayer.currentWindowIndex
                exoPlayer.release()
                println("RELEASED PLAYER")
            }
            // Because otherwise the height and width are fucked (especially from PiP), I don't know why
            // Placing these in some places just fixes it
            unFuckLayout()
            val alphaAnimation = AlphaAnimation(0f, 1f)
            alphaAnimation.duration = 100
            alphaAnimation.fillAfter = true
            loading_overlay?.startAnimation(alphaAnimation)
            video_go_back_holder?.visibility = VISIBLE
            playerViewModel?.videoSize?.postValue(null)
            isCurrentlyPlaying = false
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (this::exoPlayer.isInitialized) {
            outState.putInt(STATE_RESUME_WINDOW, exoPlayer.currentWindowIndex)
            outState.putLong(STATE_RESUME_POSITION, exoPlayer.currentPosition)
        }
        outState.putBoolean(STATE_PLAYER_FULLSCREEN, isFullscreen)
        outState.putBoolean(STATE_PLAYER_PLAYING, isPlayerPlaying)
        outState.putInt(RESIZE_MODE_KEY, resizeMode)
        outState.putFloat(PLAYBACK_SPEED, playbackSpeed!!)
        savePos()
        super.onSaveInstanceState(outState)
    }

    private fun initPlayerIfPossible(link: ExtractorLink? = null) {
        if (!isCurrentlyPlaying) {
            initPlayer(link)
        }
    }

    private fun checkProgress() {
        val time = 5000L

        // Disabled if it's 0
        val setPercentage: Float = settingsManager.getInt("completed_percentage", 80).toFloat() / 100
        val saveHistory: Boolean = settingsManager.getBoolean("save_history", true)

        if (this::exoPlayer.isInitialized && setPercentage != 0.0f && saveHistory) {
            val currentPos = exoPlayer.currentPosition
            val currentDur = exoPlayer.duration
            val currentPercentage = currentPos.toFloat() / currentDur.toFloat()
            if (currentPercentage > setPercentage && lastSyncedEpisode < data?.episodeIndex!!
                && currentDur != TIME_UNSET
                && !isLoadingNextEpisode
            ) {
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
        val currentEpisodeProgress = data?.episodeIndex!! + 1 + episodeOffset

        val hasAniList = getKey<String>(
            ANILIST_TOKEN_KEY,
            ANILIST_ACCOUNT_ID,
            null
        ) != null

        val hasMAL = getKey<String>(MAL_TOKEN_KEY, MAL_ACCOUNT_ID, null) != null
        val holder = if (hasAniList) data?.anilistID?.let {
            getDataAboutId(
                it
            )
        } else null
        val malHolder =
            if (hasMAL && holder == null) data?.malID?.let {
                getDataAboutMalId(
                    it
                )
            } else null

        val progress = holder?.progress ?: malHolder?.my_list_status?.num_episodes_watched ?: 0
        val score = holder?.score ?: malHolder?.my_list_status?.score ?: 0

        var type = if (holder != null) {
            val type =
                if (holder.type == AniListApi.Companion.AniListStatusType.None || holder.type == AniListApi.Companion.AniListStatusType.Planning) AniListApi.Companion.AniListStatusType.Watching else holder.type
            AniListApi.fromIntToAnimeStatus(type.value)
        } else {
            var type =
                AniListApi.fromIntToAnimeStatus(
                    malStatusAsString.indexOf(
                        malHolder?.my_list_status?.status ?: "watching"
                    )
                )
            type =
                if (type.value == MALApi.Companion.MalStatusType.None.value || type.value == MALApi.Companion.MalStatusType.PlanToWatch.value) AniListApi.Companion.AniListStatusType.Watching else type
            type
        }

        if (currentEpisodeProgress == holder?.episodes ?: data?.card?.episodes?.size?.plus(episodeOffset)
            && type.value != AniListApi.Companion.AniListStatusType.Completed.value
            && data?.card?.anime?.status?.lowercase() == "finished airing"
        ) {
            type = AniListApi.Companion.AniListStatusType.Completed
        }

        if (progress < currentEpisodeProgress && (holder ?: malHolder) != null) {
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
                        getCurrentActivity()!!,
                        "Error updating episode progress",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } else {

                publicResultViewModel?.localData.notNull {
                    it.postValue(it.value?.apply {
                        status = when {
                            episodes != 0 && currentEpisodeProgress != episodes && fromIntToAnimeStatus(status)
                                    == AniListApi.Companion.AniListStatusType.Completed -> AniListApi.Companion.AniListStatusType.Watching.value
                            episodes != 0 && currentEpisodeProgress == episodes && fromIntToAnimeStatus(status)
                                    != AniListApi.Companion.AniListStatusType.Completed -> AniListApi.Companion.AniListStatusType.Completed.value
                            else -> status
                        }
                        this.progress = currentEpisodeProgress
                    })
                }


                main {
                    val toast = Toast.makeText(
                        getCurrentActivity()!!,
                        "Marked episode $currentEpisodeProgress as seen",
                        LENGTH_LONG
                    )
                    toast.setGravity(Gravity.TOP, 0, 60.toPx)
                    toast.show()
                }
                setKey(MAL_SHOULD_UPDATE_LIST, true)
                setKey(ANILIST_SHOULD_UPDATE_LIST, true)
                libraryViewModel?.requestMalList(this)
                libraryViewModel?.requestAnilistList(this)
            }
        }
    }

    // Layout somehow has more height than it's supposed to
    private fun unFuckLayout() {
        view.notNull {
            it.layoutParams = it.layoutParams.apply {
                if (isInPIPMode) {
                    height = MATCH_PARENT
                    width = MATCH_PARENT
                } else {
                    height = it.rootView?.height ?: it.height
                }
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun initPlayer(inputUrl: ExtractorLink? = null) {
        unFuckLayout()
        isCurrentlyPlaying = true
        view?.setOnTouchListener { _, _ ->
            return@setOnTouchListener true
        } // VERY IMPORTANT https://stackoverflow.com/questions/28818926/prevent-clicking-on-a-button-in-an-activity-while-showing-a-fragment
        thread {
            val currentUrl = inputUrl ?: getCurrentUrl()
            println("CURRENT URL: $currentUrl")
            if (currentUrl == null) {
                main {
                    Toast.makeText(getCurrentContext() ?: context, "No links found", LENGTH_LONG).show()
                    //MainActivity.popCurrentPage()
                }
            } else {
                try {
                    // main{} here starts playing the video in background
                    activity?.runOnUiThread {
                        val isOnline =
                            currentUrl.url.startsWith("https://") || currentUrl.url.startsWith("http://")

                        if (ignoreSSL) {
                            // Disables ssl check
                            val sslContext: SSLContext = SSLContext.getInstance("TLS")
                            sslContext.init(null, arrayOf(SSLTrustManager()), SecureRandom())
                            sslContext.createSSLEngine()
                            HttpsURLConnection.setDefaultHostnameVerifier { _: String, _: SSLSession ->
                                true
                            }
                            HttpsURLConnection.setDefaultSSLSocketFactory(sslContext.socketFactory)
                        }

                        fun newDataSourceFactory(): DataSource.Factory {
                            return if (isOnline) {
                                DefaultHttpDataSource.Factory().apply {
                                    val headers = mapOf("Referer" to currentUrl.referer)
                                    setDefaultRequestProperties(headers)
                                    setUserAgent(USER_AGENT)
                                }
                            } else {
                                DefaultDataSourceFactory(getCurrentActivity()!!, USER_AGENT)
                            }
                        }

//                        class CustomFactory : DataSource.Factory {
//                            override fun createDataSource(): DataSource {
//                                return if (isOnline) {
//                                    val dataSource = DefaultHttpDataSourceFactory(USER_AGENT).createDataSource()
//                                    /*FastAniApi.currentHeaders?.forEach {
//                                        dataSource.setRequestProperty(it.key, it.value)
//                                    }*/
//                                    dataSource.setRequestProperty("Referer", currentUrl.referer)
//                                    dataSource
//                                } else {
//                                    DefaultDataSourceFactory(getCurrentActivity()!!, USER_AGENT).createDataSource()
//                                }
//                            }
//                        }

                        if (data?.card != null || (data?.slug != null && data?.episodeIndex != null && data?.seasonIndex != null)) {
                            val pro = context?.getViewPosDur(
                                data?.card?.anime?.slug ?: data?.slug!!,
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
                        video_title?.text = getCurrentTitle()

                        // removes sources button if downloaded file
                        quickstart_btt?.visibility = GONE

                        if (currentUrl.name == "Downloaded" && data != null) {
                            data?.slug?.let { slug ->
                                val episodes = context?.getSortedEpisodes(slug)
                                val prevEpisode = episodes?.filter { it.episodeIndex == data!!.episodeIndex!! - 1 }
                                val nextEpisode = episodes?.filter { it.episodeIndex == data!!.episodeIndex!! + 1 }

                                if (!nextEpisode.isNullOrEmpty()) {
                                    val fileInfo = VideoDownloadManager.getDownloadFileInfoAndUpdateSettings(
                                        guaranteedContext(context),
                                        nextEpisode[0].internalId
                                    )
                                    if (fileInfo != null) {
                                        next_episode_btt?.visibility = VISIBLE
                                        next_episode_btt?.setOnClickListener {
                                            handler.removeCallbacks(checkProgressAction)
                                            cancelNextEpisode()
                                            if (isLoadingNextEpisode) return@setOnClickListener
                                            updateHideTime(interaction = false)
                                            isLoadingNextEpisode = true
                                            savePos()
                                            val key = getViewKey(
                                                slug,
                                                data!!.episodeIndex!! + 1
                                            )
                                            context?.removeKey(VIEW_POS_KEY, key)
                                            context?.removeKey(VIEW_DUR_KEY, key)

                                            releasePlayer()
                                            loadAndPlay()
                                            handler.postDelayed(checkProgressAction, 5000L)

                                            data!!.title =
                                                "Episode ${nextEpisode[0].episodeIndex + 1 + episodeOffset} · ${nextEpisode[0]!!.videoTitle}"
                                            data?.url = fileInfo.path.toString()
                                            data?.episodeIndex = data!!.episodeIndex!! + 1
                                        }
                                    }
                                } else {
                                    // Invisible because of layout issues with prev button
                                    next_episode_btt?.visibility = INVISIBLE
                                }
                                if (!prevEpisode.isNullOrEmpty()) {
                                    val fileInfo = VideoDownloadManager.getDownloadFileInfoAndUpdateSettings(
                                        guaranteedContext(context),
                                        prevEpisode[0].internalId
                                    )
                                    if (fileInfo != null && !hidePrevButton) {
                                        prev_episode_btt?.visibility = VISIBLE
                                        prev_episode_btt?.setOnClickListener {
                                            handler.removeCallbacks(checkProgressAction)
                                            cancelNextEpisode()
                                            if (isLoadingNextEpisode) return@setOnClickListener
                                            updateHideTime(interaction = false)
                                            isLoadingNextEpisode = true
                                            savePos()
                                            val key = getViewKey(
                                                slug,
                                                data!!.episodeIndex!! - 1
                                            )
                                            context?.removeKey(VIEW_POS_KEY, key)
                                            context?.removeKey(VIEW_DUR_KEY, key)

                                            releasePlayer()
                                            loadAndPlay()
                                            handler.postDelayed(checkProgressAction, 5000L)

                                            data!!.title =
                                                "Episode ${prevEpisode[0].episodeIndex + 1 + episodeOffset} · ${prevEpisode[0].videoTitle}"
                                            data?.url = fileInfo.path.toString()
                                            data?.episodeIndex = data!!.episodeIndex!! - 1
                                        }
                                    }
                                } else {
                                    prev_episode_btt?.visibility = GONE
                                }
                            }
                            sources_btt?.visibility = GONE
                        } else {
                            if (canPlayEpisode(true)) {
                                next_episode_btt?.visibility = VISIBLE
                                next_episode_btt?.setOnClickListener {
                                    handler.removeCallbacks(checkProgressAction)
                                    cancelNextEpisode()
                                    if (isLoadingNextEpisode) return@setOnClickListener
                                    updateHideTime(interaction = false)
                                    playerViewModel?.selectedSource?.postValue(null)
                                    extractorLinks.clear()
                                    isLoadingNextEpisode = true
                                    savePos()
                                    /*val next =
                                        data!!.card!!.episodes!!.size > data!!.episodeIndex!! + 1*/
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

                                    data?.seasonIndex = 0
                                    data?.episodeIndex = next ?: data!!.episodeIndex!! + 1
                                    releasePlayer()
                                    loadAndPlay()
                                    handler.postDelayed(checkProgressAction, 5000L)
                                }
                            } else {
                                next_episode_btt?.visibility = INVISIBLE
                            }

                            if (canPlayEpisode(false) && !hidePrevButton) {
                                prev_episode_btt?.visibility = VISIBLE
                                prev_episode_btt?.isVisible = true
                                prev_episode_btt?.setOnClickListener {
                                    handler.removeCallbacks(checkProgressAction)
                                    cancelNextEpisode()
                                    if (isLoadingNextEpisode) return@setOnClickListener
                                    updateHideTime(interaction = false)
                                    playerViewModel?.selectedSource?.postValue(null)
                                    extractorLinks.clear()
                                    isLoadingNextEpisode = true
                                    savePos()
                                    /*val next =
                                        data!!.card!!.episodes!!.size > data!!.episodeIndex!! + 1*/
                                    val key = getViewKey(
                                        data?.card!!.anime.slug,
                                        data!!.episodeIndex!! - 1
                                    )
                                    context?.removeKey(VIEW_POS_KEY, key)
                                    context?.removeKey(VIEW_DUR_KEY, key)

                                    data?.seasonIndex = 0
                                    data?.episodeIndex = data!!.episodeIndex!! - 1
                                    releasePlayer()
                                    loadAndPlay()
                                    handler.postDelayed(checkProgressAction, 5000L)
                                }
                            } else {
                                prev_episode_btt?.visibility = GONE
                            }
                        }

                        val mimeType =
                            if (currentUrl.isM3u8) MimeTypes.APPLICATION_M3U8 else MimeTypes.APPLICATION_MP4

                        val mediaItemBuilder = MediaItem.Builder()
                            //Replace needed for android 6.0.0  https://github.com/google/ExoPlayer/issues/5983
                            .setMimeType(mimeType)

                        if (isOnline) {
                            mediaItemBuilder.setUri(currentUrl.url)
                            //video_title?.text = currentUrl.url
                        } else {
                            if (isScopedStorage() && !currentUrl.url.startsWith(getCurrentActivity()!!.filesDir.toString())) {
                                val uriPrimary = Uri.parse(currentUrl.url)
                                if (uriPrimary.scheme == "content") {
                                    mediaItemBuilder.setUri(uriPrimary)
                                    //      video_title?.text = uriPrimary.toString()
                                } else {
                                    //mediaItemBuilder.setUri(Uri.parse(currentUrl.url))
                                    val uri = getVideoContentUri(getCurrentActivity()!!, currentUrl.url)
                                    //    video_title?.text = uri.toString()
                                    mediaItemBuilder.setUri(uri)
                                }
                            } else {
                                //video_title?.text = Uri.fromFile(File(currentUrl.url)).toString()
                                mediaItemBuilder.setUri(Uri.fromFile(File(currentUrl.url)))
                            }
                        }

                        val mediaItem = mediaItemBuilder.build()
                        val trackSelector = DefaultTrackSelector(getCurrentActivity()!!)
                        // Disable subtitles
                        trackSelector.parameters = DefaultTrackSelector.ParametersBuilder(getCurrentActivity()!!)
                            .setRendererDisabled(C.TRACK_TYPE_VIDEO, true)
                            .setRendererDisabled(C.TRACK_TYPE_TEXT, true)
                            .setDisabledTextTrackSelectionFlags(C.TRACK_TYPE_TEXT)
                            .clearSelectionOverrides()
                            .build()

                        val exoPlayerBuilder =
                            SimpleExoPlayer.Builder(getCurrentActivity()!!)
                                .setTrackSelector(trackSelector)

                        val factory = newDataSourceFactory()
                        val dbProvider = ExoDatabaseProvider(getCurrentActivity()!!)
                        normalSafeApiCall {
                            simpleCache = SimpleCache(
                                File(
                                    getCurrentContext()!!.filesDir, "exoplayer"
                                ),
                                LeastRecentlyUsedCacheEvictor(cacheSize),
                                dbProvider
                            )
                        }
                        val cacheFactory = CacheDataSource.Factory().apply {
                            simpleCache?.let { setCache(it) }
                            setUpstreamDataSourceFactory(factory)
                        }

                        exoPlayer = exoPlayerBuilder.build().apply {
                            playWhenReady = isPlayerPlaying
                            seekTo(currentWindow, playbackPosition)
                            setMediaSource(
                                DefaultMediaSourceFactory(cacheFactory).createMediaSource(mediaItem),
                                playbackPosition
                            )
//                            setMediaItem(mediaItem, false)
                            prepare()
                        }

                        val alphaAnimation = AlphaAnimation(1f, 0f)
                        alphaAnimation.duration = 300
                        alphaAnimation.fillAfter = true
                        loading_overlay?.startAnimation(alphaAnimation)
                        video_go_back_holder?.visibility = GONE
                        links_loaded_text?.text = ""

                        exoPlayer.setHandleAudioBecomingNoisy(true) // WHEN HEADPHONES ARE PLUGGED OUT https://github.com/google/ExoPlayer/issues/7288
                        player_view?.player = exoPlayer

                        // Sets the speed
                        exoPlayer.playbackParameters = PlaybackParameters(playbackSpeed!!)
                        player_speed_text?.text = "Speed (${playbackSpeed}x)".replace(".0x", "x")

                        //https://stackoverflow.com/questions/47731779/detect-pause-resume-in-exoplayer
                        exoPlayer.addListener(object : Player.Listener {
                            @SuppressLint("NewApi")
                            override fun onPlayerStateChanged(playWhenReady: Boolean, playbackState: Int) {
                                updatePIPModeActions()
                                if (playWhenReady && playbackState == Player.STATE_READY) {
                                    focusRequest?.let { activity?.requestAudioFocus(it) }
                                }
                                if (playbackState == Player.STATE_ENDED && next_episode_btt?.visibility == VISIBLE) {
                                    if (autoPlayEnabled) queueNextEpisode()
                                } else {
                                    cancelNextEpisode()
                                }
                            }

                            override fun onVideoSizeChanged(videoSize: VideoSize) {
                                playerViewModel?.videoSize?.postValue(videoSize)
                                super.onVideoSizeChanged(videoSize)
                            }

                            override fun onPlayerError(error: ExoPlaybackException) {
                                // Lets pray this doesn't spam Toasts :)
                                when (error.type) {
                                    ExoPlaybackException.TYPE_SOURCE -> {
                                        if (currentUrl.url != "") {
                                            Toast.makeText(
                                                getCurrentContext() ?: context,
                                                "Source error\n" + error.sourceException.message,
                                                LENGTH_LONG
                                            )
                                                .show()
                                        }
                                    }
                                    ExoPlaybackException.TYPE_REMOTE -> {
                                        Toast.makeText(getCurrentContext() ?: context, "Remote error", LENGTH_LONG)
                                            .show()
                                    }
                                    ExoPlaybackException.TYPE_RENDERER -> {
                                        Toast.makeText(
                                            getCurrentContext() ?: context,
                                            "Renderer error\n" + error.rendererException.message,
                                            LENGTH_LONG
                                        )
                                            .show()
                                    }
                                    ExoPlaybackException.TYPE_UNEXPECTED -> {
                                        Toast.makeText(
                                            getCurrentContext() ?: context,
                                            "Unexpected player error\n" + error.unexpectedException.message,
                                            LENGTH_LONG
                                        ).show()
                                    }
                                }
                            }
                        })
                    }
                } catch (e: java.lang.IllegalStateException) {
                    println("Warning: Illegal state exception in PlayerFragment")
                }
            }

        }
        isLoadingNextEpisode = false
    }

    override fun onStart() {
        super.onStart()
        activity?.hideSystemUI()
        if (data?.card != null && getCurrentActivity() != null) {
            val pro = getCurrentActivity()!!.getViewPosDur(data?.card?.anime?.slug ?: data!!.slug, data?.episodeIndex!!)
            if (pro.pos > 0 && pro.dur > 0 && (pro.pos * 100 / pro.dur) < 95) { // UNDER 95% RESUME
                playbackPosition = pro.pos
            }
        }
        thread {
            if (Util.SDK_INT > 23) {
                loadAndPlay()
                if (player_view != null) player_view.onResume()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        handler.postDelayed(checkProgressAction, 5000L)

        // When restarting activity the rotation is ensured :)
        if (settingsManager?.getBoolean("allow_player_rotation", false) == true) {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER
        } else {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && fullscreenNotch) {
            val params = getCurrentActivity()!!.window.attributes
            params.layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            getCurrentActivity()!!.window.attributes = params
        }
        onPlayerNavigated.invoke(true)

        // https://github.com/Blatzar/shiro-app/issues/48
        timeTextLeft?.alpha = 0f
        timeTextRight?.alpha = 0f

        if (Util.SDK_INT <= 23) {
            loadAndPlay()
            if (player_view != null) player_view.onResume()
        }
    }

    override fun onPause() {
        super.onPause()
        if (Util.SDK_INT <= 23) {
            if (player_view != null) player_view.onPause()
            releasePlayer()
        }
    }

    override fun onStop() {
        super.onStop()
        if (Util.SDK_INT > 23) {
            if (player_view != null) player_view.onPause()
            releasePlayer()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? {
        // Inflate the layout for this fragment
        /*try {
            val sslContext: SSLContext = SSLContext.getInstance("TLSv1.2")
            sslContext.init(null, null, null)
            sslContext.createSSLEngine()
        } catch (e: Exception) {
            println("ERROR IN SSL")
        }*/
        playerViewModel =
            playerViewModel ?: ViewModelProvider(getCurrentActivity()!!).get(PlayerViewModel::class.java)
        return inflater.inflate(R.layout.player, container, false)
    }
}