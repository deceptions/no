package com.lagradost.shiro.ui.player
import com.lagradost.shiro.utils.fv

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
import com.google.android.exoplayer2.source.MediaSource
import com.lagradost.shiro.utils.integrations.IntegrationPrefs
import com.lagradost.shiro.utils.skip.SkipSegment
import com.lagradost.shiro.utils.skip.SkipTimes
import com.lagradost.shiro.utils.subs.SubtitleProviders
import com.lagradost.shiro.utils.subs.SubtitleResult
import com.lagradost.shiro.utils.cs3.CsBridge
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector
import com.google.android.exoplayer2.ui.AspectRatioFrameLayout
import com.google.android.exoplayer2.ui.TimeBar
import com.google.android.exoplayer2.upstream.DataSource
import com.google.android.exoplayer2.upstream.DefaultDataSource
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
import com.lagradost.shiro.utils.integrations.TrackerSync
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
import com.lagradost.shiro.utils.NekomoApi.Companion.USER_AGENT
import com.lagradost.shiro.utils.NekomoApi.Companion.fmod
import com.lagradost.shiro.utils.NekomoApi.Companion.loadLinks
import com.lagradost.shiro.utils.VideoDownloadManager.isScopedStorage
import com.lagradost.shiro.utils.mvvm.logError
import com.lagradost.shiro.utils.mvvm.normalSafeApiCall
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
    @JsonProperty("card") val card: NekomoApi.Companion.AnimePageNewData?,
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
    private val mapper = JsonMapper.builder().addModule(KotlinModule.Builder().build())
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
    private val skipOpEnabled = settingsManager.getBoolean("skip_op_enabled", true)
    val doubleTapEnabled = settingsManager.getBoolean("double_tap_enabled", false)
    private val playBackSpeedEnabled = settingsManager.getBoolean("playback_speed_enabled", true)
    private val playerResizeEnabled = settingsManager.getBoolean("player_resize_enabled", true)
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

    //private val restoreLockClickable = Runnable { fv<android.widget.ImageView>(R.id.video_lock)?.isClickable = true }
    private var timer: Timer? = null
    //private val linkLoadedEvent = Event<ExtractorLink>()

    private val resizeModes = listOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT,
        AspectRatioFrameLayout.RESIZE_MODE_FILL,
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
    )
    private var resizeMode = ((getCurrentContext()?.getKey(RESIZE_MODE_KEY, 0) ?: 0)).coerceIn(resizeModes.indices)

    // Made getters because this can change if user is in a split view for example
    val width: Int
        get() {
            //Resources.getSystem().displayMetrics.heightPixels
            return fv<com.google.android.exoplayer2.ui.PlayerView>(R.id.player_view)?.width ?: 0
        }

    val height: Int
        get() {
            //Resources.getSystem().displayMetrics.widthPixels
            return fv<com.google.android.exoplayer2.ui.PlayerView>(R.id.player_view)?.height ?: 0
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

    private fun getCurrentEpisode(): NekomoApi.Companion.AnimePageNewEpisodes? {
        val episodeIndex = data?.episodeIndex ?: return null
        return data?.card?.episodes?.getOrNull(episodeIndex)//data?.card!!.cdnData.seasons.getOrNull(data?.seasonIndex!!)?.episodes?.get(data?.episodeIndex!!)
    }

    private var noStreams = false

    /** Pop-out: shrinks the video into a floating picture-in-picture window that stays above other apps. */
    private fun popOut() {
        val act = activity ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            !act.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)
        ) {
            Toast.makeText(act, "Pop-out needs Android 8 or newer with picture-in-picture support.", LENGTH_LONG).show()
            return
        }
        try {
            act.enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(android.util.Rational(16, 9)).build())
        } catch (e: Exception) {
            logError(e)
            Toast.makeText(act, "Allow picture-in-picture for Nekomo in your system settings to use pop-out.", LENGTH_LONG).show()
        }
    }

    /** Hands the current stream to another installed player (VLC, MX Player, ...). */
    private fun openExternal(): Boolean {
        val ctx = context ?: return false
        val link = playerViewModel?.selectedSource?.value ?: sources.second?.firstOrNull() ?: extractorLinks.firstOrNull()
        if (link == null) {
            Toast.makeText(ctx, "There is no stream to open yet.", LENGTH_LONG).show()
            return false
        }
        val pos = if (this::exoPlayer.isInitialized) exoPlayer.currentPosition else 0L
        val headers = (link.headers.orEmpty() + (if (link.referer.isNotBlank()) mapOf("Referer" to link.referer) else emptyMap()))
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(Uri.parse(link.url), if (link.isM3u8) "application/x-mpegURL" else if (link.isDash) "application/dash+xml" else "video/*")
            .putExtra("title", data?.title ?: data?.card?.anime?.title ?: "Nekomo")
            .putExtra("position", pos.toInt())
            .putExtra("headers", headers.flatMap { listOf(it.key, it.value) }.toTypedArray())
            .putExtra("secure_uri", true)
        if (intent.resolveActivity(ctx.packageManager) == null) {
            Toast.makeText(ctx, "No external video player is installed. Install VLC or MX Player first.", LENGTH_LONG).show()
            return false
        }
        if (this::exoPlayer.isInitialized) exoPlayer.pause()
        try {
            startActivity(Intent.createChooser(intent, "Play in external player"))
        } catch (e: Exception) {
            logError(e)
            Toast.makeText(ctx, "Could not open an external player.", LENGTH_LONG).show()
            return false
        }
        return true
    }

    private fun loadAndPlay() {
        noStreams = false
        // Cached, first is index, second is links
        thread {
            if (!(sources.first == data?.episodeIndex && data?.episodeIndex != null)) {
                val episodes = getCurrentEpisode()?.sources?.let {
                    mapper.readValue<List<NekomoApi.Companion.EpisodeObject?>?>(it)
                }
                val videoId = episodes?.firstOrNull { it?.slug == "gogostream" }
                videoId?.source?.let {
                    loadLinks(
                        it,
                        false,
                        callback = ::linkLoaded
                    )
                }
                if (extractorLinks.isEmpty() && data?.url == null) {
                    noStreams = true
                    main {
                        val c = getCurrentContext() ?: context
                        val msg = if (!com.lagradost.shiro.utils.SourceState.hasAny(c))
                            "No sources installed yet. Add a repository and install a source in Settings > Extensions."
                        else "No playable streams were found for this episode from this source. " +
                                "Go back and pick another source with the cloud icon on the anime page."
                        Toast.makeText(c, msg, LENGTH_LONG).show()
                        // Don't leave the user on a spinner with nothing to play.
                        normalSafeApiCall { activity?.onBackPressed() }
                    }
                }
            }
            main {
                val external = context?.let {
                    androidx.preference.PreferenceManager.getDefaultSharedPreferences(it).getBoolean("external_player_default", false)
                } ?: false
                when {
                    noStreams -> {}
                    external && extractorLinks.isNotEmpty() && openExternal() -> normalSafeApiCall { activity?.onBackPressed() }
                    else -> initPlayerIfPossible()
                }
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
            !link.name.startsWith("Nekomo")
        ) {
            main {
                fv<android.widget.TextView>(R.id.links_loaded_text)?.text = "${safeLinks.distinctBy { it.url }.size} - Loaded ${link.name}"
                fv<com.google.android.material.button.MaterialButton>(R.id.quickstart_btt)?.visibility = VISIBLE
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
                // Keep the continue-watching home-screen widget in sync with playback.
                context?.let { com.lagradost.shiro.ui.widget.ContinueWidgetProvider.refresh(it) }
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
        statsRunning = false
        statsHandler.removeCallbacksAndMessages(null)
        subtitleDelayListener?.let { l ->
            context?.let { c ->
                androidx.preference.PreferenceManager.getDefaultSharedPreferences(c)
                    .unregisterOnSharedPreferenceChangeListener(l)
            }
        }
        subtitleDelayListener = null

        super.onDestroy()
        //MainActivity.showSystemUI()
    }

    private fun updateLock() {
        fv<android.widget.ImageView>(R.id.video_locked_img).setImageResource(if (isLocked) R.drawable.video_locked else R.drawable.video_unlocked)
        fv<android.widget.ImageView>(R.id.video_locked_img).setColorFilter(
            if (isLocked && activity != null) Cyanea.instance.primary
            else Color.WHITE
        )

        setIsClickable(!isLocked)
        //println("UPDATED LOCK $isClick")

        val fadeTo = if (!isLocked) 1f else 0f
        val fadeAnimation = AlphaAnimation(1f - fadeTo, fadeTo)

        fadeAnimation.duration = 100
        fadeAnimation.fillAfter = true

        fv<android.view.View>(R.id.shadow_overlay).startAnimation(fadeAnimation)
    }

    private fun setIsClickable(isClickable: Boolean) {
        main {
            fv<android.widget.ImageButton>(R.id.exo_play)?.isClickable = isClickable
            fv<android.widget.ImageButton>(R.id.exo_pause)?.isClickable = isClickable
            fv<android.widget.ImageButton>(R.id.exo_ffwd)?.isClickable = isClickable
            fv<android.widget.ImageButton>(R.id.exo_rew)?.isClickable = isClickable
            fv<android.widget.ImageButton>(R.id.exo_prev)?.isClickable = isClickable
            fv<android.widget.ImageView>(R.id.video_go_back)?.isClickable = isClickable
            fv<androidx.cardview.widget.CardView>(R.id.prev_episode_btt)?.isClickable = isClickable
            fv<androidx.cardview.widget.CardView>(R.id.next_episode_btt)?.isClickable = isClickable
            fv<androidx.cardview.widget.CardView>(R.id.playback_speed_btt)?.isClickable = isClickable
            fv<androidx.cardview.widget.CardView>(R.id.skip_op)?.isClickable = isClickable
            fv<androidx.cardview.widget.CardView>(R.id.resize_player)?.isClickable = isClickable
            fv<androidx.cardview.widget.CardView>(R.id.sources_btt)?.isClickable = isClickable
            fv<androidx.cardview.widget.CardView>(R.id.subtitles_btt)?.isClickable = isClickable

            // Clickable doesn't seem to work on com.google.android.exoplayer2.ui.DefaultTimeBar
            //fv<com.google.android.exoplayer2.ui.DefaultTimeBar>(R.id.exo_progress).isClickable = isClick
            fv<com.google.android.exoplayer2.ui.DefaultTimeBar>(R.id.exo_progress)?.isEnabled = isClickable

        }
    }

    private fun changePlayerTextVisibility(visible: Boolean) {
        fv<android.widget.TextView>(R.id.player_speed_text)?.isVisible = visible
        fv<android.widget.TextView>(R.id.sources_text)?.isVisible = visible
        fv<android.widget.TextView>(R.id.resize_text)?.isVisible = visible
        fv<android.widget.TextView>(R.id.skip_op_text)?.isVisible = visible
        fv<android.widget.TextView>(R.id.subtitles_text)?.isVisible = visible
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
            fv<android.widget.FrameLayout>(R.id.player_holder)?.alpha = 0f
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
            fv<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.nav_view)?.visibility = GONE
            val filter = IntentFilter()
            filter.addAction(
                ACTION_MEDIA_CONTROL
            )
            activity?.let { ContextCompat.registerReceiver(it, receiver!!, filter, ContextCompat.RECEIVER_NOT_EXPORTED) }
            updatePIPModeActions()
        } else {
            // Restore the full-screen UI.
            fv<android.widget.FrameLayout>(R.id.player_holder)?.alpha = 1f
            receiver?.let {
                try {
                    activity?.unregisterReceiver(it)
                } catch (_: IllegalArgumentException) {
                }
                receiver = null
            }
            fv<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.nav_view)?.visibility = VISIBLE

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
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
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
        fv<android.widget.TextView>(R.id.video_title)?.let {
            ObjectAnimator.ofFloat(it, "translationY", titleMove).apply {
                duration = 200
                start()
            }
        }
        val playerBarMove = if (isShowing) 0f else 50.toPx.toFloat()
        fv<android.widget.LinearLayout>(R.id.bottom_player_bar)?.let {
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

        fv<android.widget.LinearLayout>(R.id.video_lock_holder)?.startAnimation(fadeAnimation)
        // To prevent UI bug when clicking twice when animating
        fv<android.widget.ImageView>(R.id.video_lock)?.isClickable = isShowing
        //handler.postDelayed(restoreLockClickable, time + 50L)

        fv<android.widget.LinearLayout>(R.id.bottom_player_bar)?.startAnimation(fadeAnimation)
        if (!isLocked || fv<androidx.constraintlayout.widget.ConstraintLayout>(R.id.video_holder)?.alpha != 1.0f || fv<android.view.View>(R.id.shadow_overlay)?.alpha != 1.0f || fv<android.widget.LinearLayout>(R.id.bottom_player_bar_button_holder)?.alpha != 1.0f) {
            fv<androidx.constraintlayout.widget.ConstraintLayout>(R.id.video_holder)?.startAnimation(fadeAnimation)
            fv<android.widget.LinearLayout>(R.id.bottom_player_bar_button_holder)?.startAnimation(fadeAnimation)
            fv<android.view.View>(R.id.shadow_overlay)?.startAnimation(fadeAnimation)
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
        if (!this::exoPlayer.isInitialized) return
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
                            if (audioManager != null && fv<android.widget.RelativeLayout>(R.id.progressBarLeftHolder) != null) {
                                val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                                val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

                                if (fv<android.widget.RelativeLayout>(R.id.progressBarLeftHolder).alpha <= 0f) {
                                    cachedVolume = currentVolume.toFloat() / maxVolume.toFloat()
                                }

                                fv<android.widget.RelativeLayout>(R.id.progressBarLeftHolder)?.alpha = 1f
                                val vol = minOf(
                                    1f,
                                    cachedVolume - diffY.toFloat() * 0.5f
                                ) // 0.05f *if (diffY > 0) 1 else -1
                                cachedVolume = vol
                                //fv<android.widget.ProgressBar>(R.id.progressBarRight)?.progress = ((1f - alpha) * 100).toInt()

                                fv<android.widget.ProgressBar>(R.id.progressBarLeft)?.max = 100 * 100
                                fv<android.widget.ProgressBar>(R.id.progressBarLeft)?.progress = ((vol) * 100 * 100).toInt()

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
                        } else if (fv<android.widget.RelativeLayout>(R.id.progressBarRightHolder) != null) {
                            fv<android.widget.RelativeLayout>(R.id.progressBarRightHolder)?.alpha = 1f
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
                            //fv<android.view.View>(R.id.brightness_overlay)?.alpha = alpha

                            //fv<android.widget.ProgressBar>(R.id.progressBarRight)?.max = 100 * 100
                            fv<android.widget.ProgressBar>(R.id.progressBarRight)?.progress = (alpha * 100 * 100).toInt()

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
                        fv<android.widget.TextView>(R.id.timeText)?.alpha = 1f
                        fv<android.widget.TextView>(R.id.timeText)?.text = timeString
                    } else {
                        fv<android.widget.TextView>(R.id.timeText)?.alpha = 0f
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

                fv<android.widget.FrameLayout>(R.id.player_holder)?.let {
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

                fv<android.widget.TextView>(R.id.timeText)?.animate()?.alpha(0f)?.setDuration(200)
                    ?.setInterpolator(AccelerateInterpolator())?.start()
                fv<android.widget.RelativeLayout>(R.id.progressBarRightHolder)?.animate()?.alpha(0f)?.setDuration(200)
                    ?.setInterpolator(AccelerateInterpolator())?.start()
                fv<android.widget.RelativeLayout>(R.id.progressBarLeftHolder)?.animate()?.alpha(0f)?.setDuration(200)
                    ?.setInterpolator(AccelerateInterpolator())?.start()
                //val fadeAnimation = AlphaAnimation(1f, 0f)
                //fadeAnimation.duration = 100
                //fadeAnimation.fillAfter = true
                //fv<android.widget.RelativeLayout>(R.id.progressBarLeftHolder).startAnimation(fadeAnimation)
                //fv<android.widget.RelativeLayout>(R.id.progressBarRightHolder).startAnimation(fadeAnimation)
                //fv<android.widget.TextView>(R.id.timeText).startAnimation(fadeAnimation)

            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        playerViewModel!!.videoSize.observe(viewLifecycleOwner) {
            fv<android.widget.TextView>(R.id.video_title)?.text = getCurrentTitle()
        }

        playerViewModel!!.selectedSource.observe(viewLifecycleOwner) {
            playerViewModel?.videoSize?.postValue(null)
        }

        navigationBarHeight = guaranteedContext(context).getNavigationBarHeight()
        statusBarHeight = guaranteedContext(context).getStatusBarHeight()

        fv<android.view.View>(R.id.shadow_overlay)?.isVisible = !settingsManager.getBoolean("disable_player_shadow", false)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            fv<android.widget.ProgressBar>(R.id.next_episode_progressbar)?.progressTintList = ColorStateList.valueOf(Cyanea.instance.primary)
            fv<android.widget.ProgressBar>(R.id.progressBarLeft).progressTintList = ColorStateList.valueOf(Cyanea.instance.primary)
            fv<android.widget.ProgressBar>(R.id.progressBarRight).progressTintList = ColorStateList.valueOf(Cyanea.instance.primary)
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

        fv<com.google.android.exoplayer2.ui.DefaultTimeBar>(R.id.exo_progress).setPlayedColor(Cyanea.instance.primary)

        MainActivity.onPlayerEvent += ::handlePlayerEvent
        MainActivity.onAudioFocusEvent += ::handleAudioFocusEvent

        hideKeyboard()

        updateLock()

        handler.postDelayed({
            unFuckLayout()
        }, 200)

        fv<android.widget.ImageView>(R.id.video_lock).setOnClickListener {
            updateHideTime()
            isLocked = !isLocked
            val fadeTo = if (isLocked) 0f else 1f

            val fadeAnimation = AlphaAnimation(1f - fadeTo, fadeTo)
            fadeAnimation.duration = 100
            //   fadeAnimation.startOffset = 100
            fadeAnimation.fillAfter = true
            fv<androidx.constraintlayout.widget.ConstraintLayout>(R.id.video_holder).startAnimation(fadeAnimation)
            fv<android.widget.LinearLayout>(R.id.bottom_player_bar_button_holder)?.startAnimation(fadeAnimation)

            updateLock()
        }
        fv<androidx.cardview.widget.CardView>(R.id.cancel_next).setOnClickListener {
            cancelNextEpisode()
        }

        // Lazy hack
        fv<androidx.cardview.widget.CardView>(R.id.play_next).setOnClickListener {
            fv<androidx.cardview.widget.CardView>(R.id.next_episode_btt).performClick()
        }

        fv<com.google.android.exoplayer2.ui.DefaultTimeBar>(R.id.exo_progress).addListener(object : TimeBar.OnScrubListener {
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

        fv<android.widget.FrameLayout>(R.id.ffwd_holder)?.isVisible = !hidePlayerFFWD
        fv<android.widget.FrameLayout>(R.id.rew_holder)?.isVisible = !hidePlayerFFWD

        /*
        fv<android.widget.FrameLayout>(R.id.player_holder).setOnTouchListener(OnTouchListener { v, event -> // ignore all touch events
            !isShowing
        })*/
        //println("RESIZE $resizeMode")
        fv<com.google.android.exoplayer2.ui.PlayerView>(R.id.player_view)?.resizeMode = resizeModes[resizeMode]
        if (playerResizeEnabled) {
            fv<androidx.cardview.widget.CardView>(R.id.resize_player).visibility = VISIBLE
            fv<androidx.cardview.widget.CardView>(R.id.resize_player).setOnClickListener {
                updateHideTime()
                resizeMode = (resizeMode + 1).fmod(resizeModes.size)
                //println("RESIZE $resizeMode")
                context?.setKey(RESIZE_MODE_KEY, resizeMode)
                fv<com.google.android.exoplayer2.ui.PlayerView>(R.id.player_view).resizeMode = resizeModes[resizeMode]
                //exoPlayer.videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
            }
        } else {
            fv<androidx.cardview.widget.CardView>(R.id.resize_player).visibility = GONE
        }
        fv<com.google.android.material.button.MaterialButton>(R.id.quickstart_btt).setOnClickListener {
            initPlayerIfPossible()
        }


        class Listener : DoubleTapGestureListener(this) {
            val rootLayout = fv<androidx.constraintlayout.widget.ConstraintLayout>(R.id.root_constraint_layout)
            val secondsView = fv<com.lagradost.shiro.ui.player.SecondsView>(R.id.seconds_view)
            val circleClipTapView = fv<com.lagradost.shiro.ui.player.CircleClipTapView>(R.id.circle_clip_tap_view)

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


        fv<android.widget.FrameLayout>(R.id.player_holder).setOnTouchListener(
            detectorListener
        )

        isInPlayer = true
        retainInstance = true // OTHERWISE IT WILL CAUSE A CRASH

        fv<android.widget.ImageView>(R.id.video_go_back).setOnClickListener {
            // Local player
            if (data?.title != null && data?.title == data?.url) {
                playerActivity?.finish()
                activity?.onBackPressed()
            } else {
                activity?.onBackPressed()
            }
        }
        fv<android.widget.ImageView>(R.id.video_go_back_holder).setOnClickListener {
            // Local player
            if (data?.title != null && data?.title == data?.url) {
                playerActivity?.finish()
                activity?.onBackPressed()
            } else {
                activity?.onBackPressed()
            }
        }
        fv<android.widget.TextView>(R.id.exo_rew_text)?.text = fastForwardTime.toString()
        fv<android.widget.TextView>(R.id.exo_ffwd_text)?.text = fastForwardTime.toString()

        fv<android.widget.TextView>(R.id.exo_rew_text)?.text = fastForwardTime.toString()
        fv<android.widget.TextView>(R.id.exo_ffwd_text)?.text = fastForwardTime.toString()
        fv<android.widget.ImageButton>(R.id.exo_rew).setOnClickListener {
            updateHideTime()
            val rotateLeft = AnimationUtils.loadAnimation(context, R.anim.rotate_left)
            fv<android.widget.ImageButton>(R.id.exo_rew).startAnimation(rotateLeft)

            val goLeft = AnimationUtils.loadAnimation(context, R.anim.go_left)
            goLeft.setAnimationListener(object : Animation.AnimationListener {
                override fun onAnimationStart(animation: Animation?) {
                }

                override fun onAnimationRepeat(animation: Animation?) {

                }

                override fun onAnimationEnd(animation: Animation?) {
                    fv<android.widget.TextView>(R.id.exo_rew_text)?.post { fv<android.widget.TextView>(R.id.exo_rew_text)?.text = "$fastForwardTime" }
                }
            })
            fv<android.widget.TextView>(R.id.exo_rew_text)?.startAnimation(goLeft)
            fv<android.widget.TextView>(R.id.exo_rew_text)?.text = "-$fastForwardTime"
            seekTime(fastForwardTime * -1000L)

        }
        fv<android.widget.ImageButton>(R.id.exo_play).setOnClickListener {
            if (this::exoPlayer.isInitialized) exoPlayer.play()
            updateHideTime()
            cancelNextEpisode()
        }
        fv<android.widget.ImageButton>(R.id.exo_pause).setOnClickListener {
            if (this::exoPlayer.isInitialized) exoPlayer.pause()
            updateHideTime()
            cancelNextEpisode()
        }
        fv<android.widget.ImageButton>(R.id.exo_ffwd).setOnClickListener {
            updateHideTime()
            val rotateRight = AnimationUtils.loadAnimation(context, R.anim.rotate_right)
            fv<android.widget.ImageButton>(R.id.exo_ffwd).startAnimation(rotateRight)

            val goRight = AnimationUtils.loadAnimation(context, R.anim.go_right)
            goRight.setAnimationListener(object : Animation.AnimationListener {
                override fun onAnimationStart(animation: Animation?) {
                }

                override fun onAnimationRepeat(animation: Animation?) {

                }

                override fun onAnimationEnd(animation: Animation?) {
                    fv<android.widget.TextView>(R.id.exo_ffwd_text)?.post { fv<android.widget.TextView>(R.id.exo_ffwd_text)?.text = "$fastForwardTime" }
                }
            })
            fv<android.widget.TextView>(R.id.exo_ffwd_text)?.startAnimation(goRight)
            fv<android.widget.TextView>(R.id.exo_ffwd_text)?.text = "+$fastForwardTime"
            seekTime(fastForwardTime * 1000L)
        }

        fv<androidx.cardview.widget.CardView>(R.id.playback_speed_btt).visibility = if (playBackSpeedEnabled) VISIBLE else GONE
        fv<androidx.cardview.widget.CardView>(R.id.playback_speed_btt).setOnClickListener {
            updateHideTime()
            // Lmao kind bad
            val dialog = Dialog(it.context, R.style.AlertDialogCustom)
            val speedsText = arrayOf("0.5x", "0.75x", "1x", "1.25x", "1.5x", "1.75x", "2x")
            val speedsNumbers = arrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

            //dialog = builder.create()
            dialog.setContentView(R.layout.bottom_sheet)
            dialog.fv<androidx.cardview.widget.CardView>(R.id.bottom_sheet_top_bar)?.visibility = GONE
            val res = dialog.fv<android.widget.ListView>(R.id.sort_click)

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
                if (this@PlayerFragment::exoPlayer.isInitialized) exoPlayer.playbackParameters = param
                fv<android.widget.TextView>(R.id.player_speed_text)?.text = "Speed (${playbackSpeed}x)".replace(".0x", "x")
                dialog.dismiss()
            }
            dialog.window?.setSoftInputMode(SOFT_INPUT_STATE_HIDDEN)
            dialog.show()

        }

        fv<androidx.cardview.widget.CardView>(R.id.subtitles_btt)?.setOnClickListener {
            updateHideTime()
            showSubtitleDialog()
        }
        applySubtitleStyle()
        applyStatsOverlay()
        context?.let { c ->
            val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(c)
            subtitleDelayListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == "subtitle_delay_ms") {
                    DelayedRenderersFactory.current?.offsetUs =
                        prefs.getInt("subtitle_delay_ms", 0).toLong() * 1000L
                }
                if (key == "audio_delay_ms") {
                    DelayedRenderersFactory.currentAudio?.offsetUs =
                        prefs.getInt("audio_delay_ms", 0).toLong() * 1000L
                }
            }.also { prefs.registerOnSharedPreferenceChangeListener(it) }
        }
        fv<androidx.cardview.widget.CardView>(R.id.popout_btt)?.setOnClickListener {
            updateHideTime()
            popOut()
        }
        fv<androidx.cardview.widget.CardView>(R.id.external_btt)?.setOnClickListener {
            updateHideTime()
            openExternal()
        }
        fv<androidx.cardview.widget.CardView>(R.id.watch_together_btt)?.setOnClickListener {
            updateHideTime()
            val wt = com.lagradost.shiro.utils.WatchTogether
            val ctx2 = getCurrentActivity() ?: return@setOnClickListener
            val status = when {
                wt.hosting -> "Hosting on ${wt.localIp()}:${wt.PORT} — guests tap Join and type your IP"
                wt.following -> "Following the host — seeking when drift exceeds ${wt.DRIFT_MS / 1000}s"
                else -> "Watch the same thing on two phones on the same Wi-Fi: one hosts, the other joins with the host's IP."
            }
            android.app.AlertDialog.Builder(ctx2)
                .setTitle("Watch Together")
                .setMessage(status)
                .setNeutralButton("Stop") { _, _ -> wt.stop(); Toast.makeText(ctx2, "Watch Together stopped", Toast.LENGTH_LONG).show() }
                .setNegativeButton("Join…") { _, _ ->
                    val et = android.widget.EditText(ctx2).apply { hint = "Host IP, e.g. 192.168.1.20"; inputType = android.text.InputType.TYPE_CLASS_PHONE }
                    android.app.AlertDialog.Builder(ctx2).setTitle("Join a host")
                        .setView(et)
                        .setPositiveButton("Join") { _, _ ->
                            val ip = et.text.toString().trim()
                            if (ip.isNotEmpty()) wt.join(ip,
                                { snap -> if (kotlin.math.abs(exoPlayer.currentPosition - snap.posMs) > wt.DRIFT_MS) exoPlayer.seekTo(snap.posMs) },
                                { e -> Toast.makeText(ctx2, e, Toast.LENGTH_LONG).show() })
                        }
                        .setNegativeButton("Cancel", null).show()
                }
                .setPositiveButton("Host") { _, _ ->
                    wt.hostLoop {
                        val ep = data?.episodeIndex ?: return@hostLoop null
                        val slug = data?.slug ?: return@hostLoop null
                        com.lagradost.shiro.utils.WatchTogether.Snapshot(slug, ep, exoPlayer.currentPosition)
                    }
                    Toast.makeText(ctx2, "Hosting — your IP: ${wt.localIp() ?: "unknown"}", Toast.LENGTH_LONG).show()
                }
                .show()
        }
        fv<android.widget.TextView>(R.id.skip_segment_btt)?.setOnClickListener {
            currentSkipSegment?.let { seg ->
                if (this::exoPlayer.isInitialized) exoPlayer.seekTo(seg.endMs)
            }
        }

        fv<androidx.cardview.widget.CardView>(R.id.sources_btt).setOnClickListener {
            updateHideTime()
            sources.second?.let {
                val sourcesText = it.map { link -> link.name }
                val dialog = Dialog(guaranteedContext(context), R.style.AlertDialogCustom)

                val index = maxOf(sources.second?.indexOf(playerViewModel?.selectedSource?.value) ?: -1, 0)
                //dialog = builder.create()
                dialog.setContentView(R.layout.bottom_sheet)
                dialog.fv<androidx.cardview.widget.CardView>(R.id.bottom_sheet_top_bar)?.visibility = GONE
                val res = dialog.fv<android.widget.ListView>(R.id.sort_click)

                res.choiceMode = CHOICE_MODE_SINGLE
                val arrayAdapter = ArrayAdapter<String>(guaranteedContext(context), R.layout.bottom_single_choice)
                arrayAdapter.addAll(ArrayList(sourcesText))
                res.adapter = arrayAdapter
                res.setItemChecked(
                    index,
                    true
                )
                res.setOnItemClickListener { _, _, which, _ ->
                    // setValue (not postValue): loadAndPlay() below reads selectedSource right away
                    playerViewModel?.selectedSource?.value = it[which]
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
            fv<androidx.cardview.widget.CardView>(R.id.skip_op)?.visibility = VISIBLE
            fv<androidx.cardview.widget.CardView>(R.id.skip_op)?.setOnClickListener {
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
                    fv<android.widget.FrameLayout>(R.id.next_episode_overlay)?.visibility = VISIBLE
                    fv<android.widget.ProgressBar>(R.id.next_episode_progressbar)?.progress = 0
                    fv<android.widget.ProgressBar>(R.id.next_episode_progressbar)?.let { progressBar ->
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
                            fv<android.widget.TextView>(R.id.next_episode_time_text)?.text = "Next episode in ${(timeLeft / 1000).toInt()}..."
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
            fv<androidx.cardview.widget.CardView>(R.id.next_episode_btt)?.performClick()
        }
    }

    private fun cancelNextEpisode() {
        if (autoPlayEnabled) {
            handler.removeCallbacks(nextEpisodeAction)
            timer?.cancel()
            fv<android.widget.TextView>(R.id.next_episode_time_text)?.text = ""
            fv<android.widget.FrameLayout>(R.id.next_episode_overlay)?.visibility = GONE
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
        if (!this::exoPlayer.isInitialized) return
        exoPlayer.seekTo(maxOf(minOf(exoPlayer.currentPosition + time, exoPlayer.duration), 0))
    }

    // ---------------------------------------------------------------------------------------
    /** Subtitle size, color and background from Settings -> Player; applied to ExoPlayer's SubtitleView. */
    private val statsHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var statsRunning = false
    private var subtitleDelayListener: android.content.SharedPreferences.OnSharedPreferenceChangeListener? = null

    /** "Stats for nerds": codec / resolution / buffer / speed text over the video, refreshed once a second. */
    private fun applyStatsOverlay() {
        val view = fv<android.widget.TextView>(R.id.player_stats_overlay) ?: return
        val on = context?.let {
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(it).getBoolean("player_stats", false)
        } ?: false
        statsRunning = false
        statsHandler.removeCallbacksAndMessages(null)
        if (!on) {
            view.visibility = View.GONE
            return
        }
        view.visibility = View.VISIBLE
        statsRunning = true
        val tick = object : Runnable {
            override fun run() {
                if (!statsRunning) return
                if (this@PlayerFragment::exoPlayer.isInitialized) {
                    val f = exoPlayer.videoFormat
                    val codec = f?.sampleMimeType?.removePrefix("video/")?.uppercase() ?: "-"
                    val res = f?.let { "${it.width}x${it.height}" } ?: "-"
                    val br = f?.bitrate?.let { if (it > 0) "%.1f Mbps".format(it / 1e6) else "-" } ?: "-"
                    val buf = ((exoPlayer.bufferedPosition - exoPlayer.currentPosition) / 1000f).coerceAtLeast(0f)
                    val state = when (exoPlayer.playbackState) {
                        com.google.android.exoplayer2.Player.STATE_READY -> if (exoPlayer.isPlaying) "playing" else "paused"
                        com.google.android.exoplayer2.Player.STATE_BUFFERING -> "buffering"
                        com.google.android.exoplayer2.Player.STATE_ENDED -> "ended"
                        else -> "idle"
                    }
                    view.text = "$codec  $res\n$br  ${"%.1f".format(buf)}s buffer\n$state  ${exoPlayer.playbackParameters.speed}x"
                }
                statsHandler.postDelayed(this, 1000)
            }
        }
        statsHandler.post(tick)
    }

    private fun applySubtitleStyle() {
        val ctx = context ?: return
        val sub = fv<com.google.android.exoplayer2.ui.PlayerView>(R.id.player_view)?.subtitleView ?: return
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        val pct = prefs.getInt("subtitle_size_pct", 100).coerceIn(50, 250)
        // CaptionStyleCompat.DEFAULT_TEXT_SIZE_FRACTION is 0.0533 of the view height
        sub.setFractionalTextSize(0.0533f * pct / 100f)
        val fg = when (prefs.getString("subtitle_color", "white")) {
            "yellow" -> Color.YELLOW
            "green" -> Color.GREEN
            "cyan" -> Color.CYAN
            else -> Color.WHITE
        }
        val bg = if (prefs.getBoolean("subtitle_bg", false)) 0x99000000.toInt() else Color.TRANSPARENT
        sub.setStyle(
            com.google.android.exoplayer2.ui.CaptionStyleCompat(
                fg, bg, Color.TRANSPARENT,
                com.google.android.exoplayer2.ui.CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                Color.BLACK, null
            )
        )
    }

    // Subtitles (OpenSubtitles / SubDL / extension) and skip times (Anime Skip / AniSkip)
    // ---------------------------------------------------------------------------------------

    private var subtitleResults: List<SubtitleResult> = emptyList()
    private var subtitleSearchedFor: Int? = null
    private var activeSubtitle: SubtitleResult? = null
    private var activeSubtitleFile: File? = null
    private var subtitlesDisabled = false
    private var lastBaseMediaItem: MediaItem? = null
    private var lastMediaSourceFactory: MediaSource.Factory? = null

    private var skipSegments: List<SkipSegment> = emptyList()
    private var skipFetchedFor: Int? = null
    private var currentSkipSegment: SkipSegment? = null
    private val autoSkipped = HashSet<SkipSegment>()
    private val skipTicker: Runnable = object : Runnable {
        override fun run() {
            tickSkip()
            handler.postDelayed(this, 500)
        }
    }

    private fun subtitleConfig(file: File): MediaItem.SubtitleConfiguration {
        val mime = when (file.extension.lowercase()) {
            "vtt" -> MimeTypes.TEXT_VTT
            "ass", "ssa" -> MimeTypes.TEXT_SSA
            else -> MimeTypes.APPLICATION_SUBRIP
        }
        return MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(file))
            .setMimeType(mime)
            .setLanguage(activeSubtitle?.language?.takeIf { it.isNotEmpty() })
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .build()
    }

    /** Title/episode used to search subtitle providers. */
    private fun subtitleQuery(): Pair<String, Int?>? {
        val anime = data?.card?.anime
        val isExtension = com.lagradost.shiro.utils.isExtensionSlug(data?.slug ?: "")
        val rawTitle = (if (isExtension) anime?.title else anime?.title_english?.takeIf { it.isNotBlank() } ?: anime?.title)
            ?: data?.title ?: return null
        val title = rawTitle.replace(Regex("\\s*\\((Dub|Sub)\\)\\s*$", RegexOption.IGNORE_CASE), "")
        val isMovie = data?.card?.episodes?.size == 1 && anime?.status?.lowercase() == "finished airing"
        val episode = data?.episodeIndex?.let { it + 1 + episodeOffset }
        return title to (if (isMovie) null else episode)
    }

    /** Runs once per episode when playback becomes ready: fetches skip times and subtitles. */
    private fun onPlayerReadyExtras() {
        val ctx = context ?: return
        val episodeIndex = data?.episodeIndex ?: return
        if (!this::exoPlayer.isInitialized) return

        if (skipFetchedFor != episodeIndex) {
            skipFetchedFor = episodeIndex
            skipSegments = emptyList()
            currentSkipSegment = null
            autoSkipped.clear()
            val duration = exoPlayer.duration.takeIf { it != TIME_UNSET } ?: 0L
            val epNumber = episodeIndex + 1 + episodeOffset
            val anilist = data?.anilistID
            val mal = data?.malID
            if ((anilist != null || mal != null) && data?.url == null) {
                thread {
                    val segments = try {
                        SkipTimes.fetch(ctx.applicationContext, anilist, mal, epNumber, duration)
                    } catch (e: Exception) {
                        logError(e); emptyList()
                    }
                    main {
                        if (skipFetchedFor == episodeIndex) {
                            skipSegments = segments
                            // Real timestamps replace the fixed "skip 85 seconds" button
                            if (segments.isNotEmpty()) fv<androidx.cardview.widget.CardView>(R.id.skip_op)?.visibility = GONE
                        }
                    }
                }
            }
        }

        if (subtitleSearchedFor != episodeIndex) {
            subtitleSearchedFor = episodeIndex
            subtitleResults = emptyList()
            activeSubtitle = null
            activeSubtitleFile = null
            val prefs = IntegrationPrefs(ctx)
            val query = subtitleQuery()
            if (query != null && data?.url == null && !subtitlesDisabled && prefs.autoFetchSubtitles &&
                (prefs.hasSubtitleProvider || SubtitleProviders.extensionSubtitles.isNotEmpty())
            ) {
                thread { searchSubtitles(ctx.applicationContext, query, episodeIndex, autoApply = true) }
            }
        }
    }

    private fun searchSubtitles(ctx: Context, query: Pair<String, Int?>, episodeIndex: Int, autoApply: Boolean) {
        val lang = IntegrationPrefs(ctx).subtitleLanguage
        val results = try {
            SubtitleProviders.search(ctx, query.first, query.second, lang)
        } catch (e: Exception) {
            logError(e); emptyList()
        }
        main { if (subtitleSearchedFor == episodeIndex) subtitleResults = results }
        if (!autoApply) return
        // Prefer results in the wanted language, then whatever the providers ranked first
        val ordered = results.sortedByDescending { it.language.lowercase().startsWith(lang) }
        for (r in ordered.take(3)) {
            val file = SubtitleProviders.download(ctx, r) ?: continue
            main { if (subtitleSearchedFor == episodeIndex && !subtitlesDisabled) applySubtitle(file, r) }
            return
        }
    }

    /** Rebuilds the media source with [file] (or none) at the current position. */
    private fun applySubtitle(file: File?, result: SubtitleResult?) {
        if (!this::exoPlayer.isInitialized) return
        val base = lastBaseMediaItem ?: return
        val factory = lastMediaSourceFactory ?: return
        subtitlesDisabled = file == null
        activeSubtitle = result
        activeSubtitleFile = file
        val item = if (file != null) base.buildUpon().setSubtitleConfigurations(listOf(subtitleConfig(file))).build()
        else base
        val position = exoPlayer.currentPosition
        val play = exoPlayer.playWhenReady
        exoPlayer.setMediaSource(factory.createMediaSource(item), position)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = play
        fv<android.widget.TextView>(R.id.subtitles_text)?.text =
            if (result != null) "Subtitles (${result.language.ifEmpty { "on" }})" else "Subtitles"
    }

    private fun showSubtitleDialog() {
        val ctx = context ?: return
        val episodeIndex = data?.episodeIndex ?: return
        val prefs = IntegrationPrefs(ctx)
        if (!prefs.hasSubtitleProvider && SubtitleProviders.extensionSubtitles.isEmpty() && subtitleResults.isEmpty()) {
            Toast.makeText(ctx, "Add an OpenSubtitles or SubDL key under Settings > Integrations", LENGTH_LONG).show()
            return
        }

        fun present() {
            val entries = listOf<SubtitleResult?>(null) + subtitleResults
            val labels = entries.map { it?.label ?: "Off" }
            val dialog = Dialog(guaranteedContext(context), R.style.AlertDialogCustom)
            dialog.setContentView(R.layout.bottom_sheet)
            dialog.fv<androidx.cardview.widget.CardView>(R.id.bottom_sheet_top_bar)?.visibility = GONE
            val res = dialog.fv<android.widget.ListView>(R.id.sort_click)
            res.choiceMode = CHOICE_MODE_SINGLE
            val adapter = ArrayAdapter<String>(guaranteedContext(context), R.layout.bottom_single_choice)
            adapter.addAll(labels)
            res.adapter = adapter
            res.setItemChecked(
                if (subtitlesDisabled || activeSubtitle == null) 0 else entries.indexOf(activeSubtitle).coerceAtLeast(0),
                true
            )
            res.setOnItemClickListener { _, _, which, _ ->
                dialog.dismiss()
                val chosen = entries[which]
                if (chosen == null) {
                    applySubtitle(null, null)
                } else {
                    Toast.makeText(ctx, "Downloading subtitle...", Toast.LENGTH_SHORT).show()
                    thread {
                        val file = SubtitleProviders.download(ctx.applicationContext, chosen)
                        main {
                            if (file != null) applySubtitle(file, chosen)
                            else Toast.makeText(ctx, "Could not download that subtitle", LENGTH_LONG).show()
                        }
                    }
                }
            }
            dialog.window?.setSoftInputMode(SOFT_INPUT_STATE_HIDDEN)
            dialog.show()
        }

        if (subtitleResults.isNotEmpty() || subtitleSearchedFor != episodeIndex) {
            present()
        } else {
            val query = subtitleQuery() ?: return
            Toast.makeText(ctx, "Searching subtitles...", Toast.LENGTH_SHORT).show()
            thread {
                searchSubtitles(ctx.applicationContext, query, episodeIndex, autoApply = false)
                main {
                    if (subtitleResults.isEmpty()) Toast.makeText(ctx, "No subtitles found", LENGTH_LONG).show()
                    else present()
                }
            }
        }
    }

    /** Shows the skip button inside intro/outro/recap segments, auto-skips if enabled. */
    private fun tickSkip() {
        if (!this::exoPlayer.isInitialized || view == null) return
        val button = fv<android.widget.TextView>(R.id.skip_segment_btt) ?: return
        val segments = skipSegments
        if (segments.isEmpty()) {
            if (button.visibility != GONE) button.visibility = GONE
            currentSkipSegment = null
            return
        }
        val prefs = IntegrationPrefs(button.context)
        val position = exoPlayer.currentPosition
        val segment = segments.firstOrNull {
            position >= it.startMs && position < it.endMs - 1000 && SkipTimes.enabled(prefs, it.type)
        }
        currentSkipSegment = segment
        if (segment == null) {
            if (button.visibility != GONE) button.visibility = GONE
            return
        }
        if (prefs.autoSkip && autoSkipped.add(segment)) {
            exoPlayer.seekTo(segment.endMs)
            button.visibility = GONE
            return
        }
        if (button.visibility != VISIBLE) button.visibility = VISIBLE
        if (button.text != segment.type.label) button.text = segment.type.label
    }

    private fun releasePlayer() {
        handler.removeCallbacks(skipTicker)
        main {
            try {
                if (this@PlayerFragment::exoPlayer.isInitialized) {
                    isPlayerPlaying = exoPlayer.playWhenReady
                    playbackPosition = exoPlayer.currentPosition
                    currentWindow = exoPlayer.currentWindowIndex
                    exoPlayer.release()
                    println("RELEASED PLAYER")
                }
                // Because otherwise the height and width are fucked (especially from PiP), I don't know why
                // Placing these in some places just fixes it
                if (view != null) {
                    unFuckLayout()
                    val alphaAnimation = AlphaAnimation(0f, 1f)
                    alphaAnimation.duration = 100
                    alphaAnimation.fillAfter = true
                    fv<android.widget.FrameLayout>(R.id.loading_overlay)?.startAnimation(alphaAnimation)
                    fv<android.widget.ImageView>(R.id.video_go_back_holder)?.visibility = VISIBLE
                }
                playerViewModel?.videoSize?.postValue(null)
                isCurrentlyPlaying = false
            } catch (e: Exception) {
                // Fragment view may already be torn down (navigation/back press/PIP close race)
                logError(e)
            } finally {
                // Release the cache after the player itself has been released to avoid the
                // player still reading from a cache that's concurrently being closed.
                thread {
                    try {
                        simpleCache?.release()
                    } catch (e: Exception) {
                        logError(e)
                    }
                }
            }
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

        val episodeIndex = data?.episodeIndex
        if (this::exoPlayer.isInitialized && setPercentage != 0.0f && saveHistory && episodeIndex != null) {
            val currentPos = exoPlayer.currentPosition
            val currentDur = exoPlayer.duration
            val currentPercentage = currentPos.toFloat() / currentDur.toFloat()
            if (currentPercentage > setPercentage && lastSyncedEpisode < episodeIndex
                && currentDur != TIME_UNSET
                && !isLoadingNextEpisode
            ) {
                lastSyncedEpisode = episodeIndex
                thread {
                    try {
                        context?.updateProgress()
                    } catch (e: Exception) {
                        logError(e)
                    }
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
        if (com.lagradost.shiro.utils.Privacy.incognito(this)) return
        val episodeIndex = data?.episodeIndex ?: return
        val currentEpisodeProgress = episodeIndex + 1 + episodeOffset

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

        if (TrackerSync.anyConnected(this)) {
            TrackerSync.pushAndNotify(
                this, data?.malID, data?.anilistID, type.value, score, currentEpisodeProgress, monotonic = true
            )
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
                                    // Extractor-provided headers (extensions) take precedence
                                    val headers = HashMap<String, String>()
                                    headers["Referer"] = currentUrl.referer
                                    currentUrl.headers?.let { headers.putAll(it) }
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
                        fv<android.widget.TextView>(R.id.video_title)?.text = getCurrentTitle()

                        // removes sources button if downloaded file
                        fv<com.google.android.material.button.MaterialButton>(R.id.quickstart_btt)?.visibility = GONE

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
                                        fv<androidx.cardview.widget.CardView>(R.id.next_episode_btt)?.visibility = VISIBLE
                                        fv<androidx.cardview.widget.CardView>(R.id.next_episode_btt)?.setOnClickListener {
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
                                    fv<androidx.cardview.widget.CardView>(R.id.next_episode_btt)?.visibility = INVISIBLE
                                }
                                if (!prevEpisode.isNullOrEmpty()) {
                                    val fileInfo = VideoDownloadManager.getDownloadFileInfoAndUpdateSettings(
                                        guaranteedContext(context),
                                        prevEpisode[0].internalId
                                    )
                                    if (fileInfo != null && !hidePrevButton) {
                                        fv<androidx.cardview.widget.CardView>(R.id.prev_episode_btt)?.visibility = VISIBLE
                                        fv<androidx.cardview.widget.CardView>(R.id.prev_episode_btt)?.setOnClickListener {
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
                                    fv<androidx.cardview.widget.CardView>(R.id.prev_episode_btt)?.visibility = GONE
                                }
                            }
                            fv<androidx.cardview.widget.CardView>(R.id.sources_btt)?.visibility = GONE
                        } else {
                            if (canPlayEpisode(true)) {
                                fv<androidx.cardview.widget.CardView>(R.id.next_episode_btt)?.visibility = VISIBLE
                                fv<androidx.cardview.widget.CardView>(R.id.next_episode_btt)?.setOnClickListener {
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
                                fv<androidx.cardview.widget.CardView>(R.id.next_episode_btt)?.visibility = INVISIBLE
                            }

                            if (canPlayEpisode(false) && !hidePrevButton) {
                                fv<androidx.cardview.widget.CardView>(R.id.prev_episode_btt)?.visibility = VISIBLE
                                fv<androidx.cardview.widget.CardView>(R.id.prev_episode_btt)?.isVisible = true
                                fv<androidx.cardview.widget.CardView>(R.id.prev_episode_btt)?.setOnClickListener {
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
                                fv<androidx.cardview.widget.CardView>(R.id.prev_episode_btt)?.visibility = GONE
                            }
                        }

                        val mimeType = when {
                            currentUrl.isM3u8 -> MimeTypes.APPLICATION_M3U8
                            currentUrl.isDash -> MimeTypes.APPLICATION_MPD
                            else -> MimeTypes.APPLICATION_MP4
                        }

                        val mediaItemBuilder = MediaItem.Builder()
                            //Replace needed for android 6.0.0  https://github.com/google/ExoPlayer/issues/5983
                            .setMimeType(mimeType)

                        if (isOnline) {
                            mediaItemBuilder.setUri(currentUrl.url)
                            //fv<android.widget.TextView>(R.id.video_title)?.text = currentUrl.url
                        } else {
                            if (isScopedStorage() && !currentUrl.url.startsWith(getCurrentActivity()!!.filesDir.toString())) {
                                val uriPrimary = Uri.parse(currentUrl.url)
                                if (uriPrimary.scheme == "content") {
                                    mediaItemBuilder.setUri(uriPrimary)
                                    //      fv<android.widget.TextView>(R.id.video_title)?.text = uriPrimary.toString()
                                } else {
                                    //mediaItemBuilder.setUri(Uri.parse(currentUrl.url))
                                    val uri = getVideoContentUri(getCurrentActivity()!!, currentUrl.url)
                                    //    fv<android.widget.TextView>(R.id.video_title)?.text = uri.toString()
                                    mediaItemBuilder.setUri(uri)
                                }
                            } else {
                                //fv<android.widget.TextView>(R.id.video_title)?.text = Uri.fromFile(File(currentUrl.url)).toString()
                                mediaItemBuilder.setUri(Uri.fromFile(File(currentUrl.url)))
                            }
                        }

                        val baseMediaItem = mediaItemBuilder.build()
                        lastBaseMediaItem = baseMediaItem
                        val mediaItem = activeSubtitleFile?.takeIf { !subtitlesDisabled }?.let {
                            baseMediaItem.buildUpon().setSubtitleConfigurations(listOf(subtitleConfig(it))).build()
                        } ?: baseMediaItem
                        val trackSelector = DefaultTrackSelector(getCurrentActivity()!!)
                        // Text tracks are enabled so external (OpenSubtitles/SubDL/extension) and embedded
                        // subtitles render; a chosen external subtitle is flagged DEFAULT so it auto-selects.
                        trackSelector.parameters = DefaultTrackSelector.ParametersBuilder(getCurrentActivity()!!)
                            .setPreferredTextLanguage(IntegrationPrefs(guaranteedContext(context)).subtitleLanguage)
                            .setSelectUndeterminedTextLanguage(true)
                            .clearSelectionOverrides()
                            .build()

                        // Custom renderers factory: DelayedTextRenderer powers the subtitle-delay
                        // setting (offset lives in DelayedRenderersFactory.current).
                        val exoPlayerBuilder =
                            SimpleExoPlayer.Builder(getCurrentActivity()!!, DelayedRenderersFactory(getCurrentActivity()!!))
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

                        // DefaultDataSource routes http(s) through the cache and file:/content: (local
                        // subtitle files, downloads) to the right source.
                        val mediaSourceFactory = DefaultMediaSourceFactory(
                            DefaultDataSource.Factory(getCurrentActivity()!!, cacheFactory)
                        )
                        lastMediaSourceFactory = mediaSourceFactory
                        exoPlayer = exoPlayerBuilder.build().apply {
                            // Subtitle delay (ms) -> renderer offset (µs). Positive delays subtitles.
                            DelayedRenderersFactory.current?.offsetUs =
                                settingsManager.getInt("subtitle_delay_ms", 0).toLong() * 1000L
                            DelayedRenderersFactory.currentAudio?.offsetUs =
                                settingsManager.getInt("audio_delay_ms", 0).toLong() * 1000L
                            playWhenReady = isPlayerPlaying
                            seekTo(currentWindow, playbackPosition)
                            setMediaSource(
                                mediaSourceFactory.createMediaSource(mediaItem),
                                playbackPosition
                            )
//                            setMediaItem(mediaItem, false)
                            prepare()
                        }

                        val alphaAnimation = AlphaAnimation(1f, 0f)
                        alphaAnimation.duration = 300
                        alphaAnimation.fillAfter = true
                        fv<android.widget.FrameLayout>(R.id.loading_overlay)?.startAnimation(alphaAnimation)
                        fv<android.widget.ImageView>(R.id.video_go_back_holder)?.visibility = GONE
                        fv<android.widget.TextView>(R.id.links_loaded_text)?.text = ""

                        exoPlayer.setHandleAudioBecomingNoisy(true) // WHEN HEADPHONES ARE PLUGGED OUT https://github.com/google/ExoPlayer/issues/7288
                        fv<com.google.android.exoplayer2.ui.PlayerView>(R.id.player_view)?.player = exoPlayer

                        // Sets the speed
                        exoPlayer.playbackParameters = PlaybackParameters(playbackSpeed!!)
                        fv<android.widget.TextView>(R.id.player_speed_text)?.text = "Speed (${playbackSpeed}x)".replace(".0x", "x")

                        //https://stackoverflow.com/questions/47731779/detect-pause-resume-in-exoplayer
                        exoPlayer.addListener(object : Player.Listener {
                            @SuppressLint("NewApi")
                            override fun onPlayerStateChanged(playWhenReady: Boolean, playbackState: Int) {
                                updatePIPModeActions()
                                if (playWhenReady && playbackState == Player.STATE_READY) {
                                    focusRequest?.let { activity?.requestAudioFocus(it) }
                                }
                                if (playbackState == Player.STATE_READY) onPlayerReadyExtras()
                                if (playbackState == Player.STATE_ENDED && fv<androidx.cardview.widget.CardView>(R.id.next_episode_btt)?.visibility == VISIBLE) {
                                    if (autoPlayEnabled) queueNextEpisode()
                                } else {
                                    cancelNextEpisode()
                                }
                            }

                            override fun onVideoSizeChanged(videoSize: VideoSize) {
                                playerViewModel?.videoSize?.postValue(videoSize)
                                super.onVideoSizeChanged(videoSize)
                            }

                            override fun onPlayerError(error: PlaybackException) {
                                val list = sources.second
                                val idx = list?.indexOf(getCurrentUrl()) ?: -1
                                if (list != null && idx >= 0 && idx + 1 < list.size && data?.url == null) {
                                    // This link is dead or not playable: move on to the next source
                                    Toast.makeText(
                                        getCurrentContext() ?: context,
                                        "Source \"${list[idx].name}\" failed, trying \"${list[idx + 1].name}\"…",
                                        LENGTH_LONG
                                    ).show()
                                    playerViewModel?.selectedSource?.value = list[idx + 1]
                                    handler.post {
                                        releasePlayer()
                                        loadAndPlay()
                                    }
                                } else {
                                    // Lets pray this doesn't spam Toasts :)
                                    Toast.makeText(
                                        getCurrentContext() ?: context,
                                        "Player error (${error.errorCodeName})\n" + (error.message ?: ""),
                                        LENGTH_LONG
                                    ).show()
                                }
                            }
                        })
                        handler.removeCallbacks(skipTicker)
                        handler.post(skipTicker)
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
                if (fv<com.google.android.exoplayer2.ui.PlayerView>(R.id.player_view) != null) fv<com.google.android.exoplayer2.ui.PlayerView>(R.id.player_view).onResume()
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
        fv<android.widget.TextView>(R.id.timeTextLeft)?.alpha = 0f
        fv<android.widget.TextView>(R.id.timeTextRight)?.alpha = 0f

        if (Util.SDK_INT <= 23) {
            loadAndPlay()
            if (fv<com.google.android.exoplayer2.ui.PlayerView>(R.id.player_view) != null) fv<com.google.android.exoplayer2.ui.PlayerView>(R.id.player_view).onResume()
        }
    }

    override fun onPause() {
        super.onPause()
        if (Util.SDK_INT <= 23) {
            if (fv<com.google.android.exoplayer2.ui.PlayerView>(R.id.player_view) != null) fv<com.google.android.exoplayer2.ui.PlayerView>(R.id.player_view).onPause()
            releasePlayer()
        }
    }

    override fun onStop() {
        super.onStop()
        if (Util.SDK_INT > 23) {
            if (fv<com.google.android.exoplayer2.ui.PlayerView>(R.id.player_view) != null) fv<com.google.android.exoplayer2.ui.PlayerView>(R.id.player_view).onPause()
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