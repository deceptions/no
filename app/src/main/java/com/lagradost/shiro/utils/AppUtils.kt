package com.lagradost.shiro.utils

import BOOKMARK_KEY
import DataStore.containsKey
import DataStore.getKey
import DataStore.removeKey
import DataStore.setKey
import SUBSCRIPTIONS_BOOKMARK_KEY
import SUBSCRIPTIONS_KEY
import VIEWSTATE_KEY
import VIEW_DUR_KEY
import VIEW_LST_KEY
import VIEW_POS_KEY
import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AppOpsManager
import android.app.UiModeManager
import android.content.*
import android.content.Context.UI_MODE_SERVICE
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.database.Cursor
import android.graphics.Color
import android.graphics.Point
import android.graphics.Rect
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.http.HttpResponseCache
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import android.util.TypedValue
import android.view.*
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import androidx.appcompat.view.menu.MenuBuilder
import androidx.appcompat.widget.PopupMenu
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.google.android.exoplayer2.ext.cast.CastPlayer
import com.google.android.exoplayer2.util.MimeTypes
import com.google.android.exoplayer2.video.VideoSize
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaQueueItem
import com.google.android.gms.cast.MediaStatus.REPEAT_MODE_REPEAT_SINGLE
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.images.WebImage
import com.google.firebase.ktx.Firebase
import com.google.firebase.messaging.ktx.messaging
import com.jaredrummler.cyanea.Cyanea
import com.jaredrummler.cyanea.app.CyaneaAppCompatActivity
import com.lagradost.shiro.AcraApplication.Companion.getAppContext
import com.lagradost.shiro.R
import com.lagradost.shiro.ui.*
import com.lagradost.shiro.ui.MainActivity.Companion.activity
import com.lagradost.shiro.ui.MainActivity.Companion.masterViewModel
import com.lagradost.shiro.ui.MainActivity.Companion.navController
import com.lagradost.shiro.ui.home.CARD_LIST
import com.lagradost.shiro.ui.home.CardAdapter
import com.lagradost.shiro.ui.home.CardContinueAdapter
import com.lagradost.shiro.ui.home.HomeFragment.Companion.homeViewModel
import com.lagradost.shiro.ui.home.TITLE
import com.lagradost.shiro.ui.player.PlayerActivity.Companion.playerActivity
import com.lagradost.shiro.ui.player.PlayerData
import com.lagradost.shiro.ui.result.IS_MAL_ID
import com.lagradost.shiro.ui.result.NAME
import com.lagradost.shiro.ui.result.SLUG
import com.lagradost.shiro.ui.settings.SettingsActivity.Companion.settingsActivity
import com.lagradost.shiro.ui.tv.TvActivity.Companion.tvActivity
import com.lagradost.shiro.utils.AniListApi.Companion.authenticateLogin
import com.lagradost.shiro.utils.AniListApi.Companion.initGetUser
import com.lagradost.shiro.utils.Coroutines.main
import com.lagradost.shiro.utils.MALApi.Companion.authenticateMalLogin
import com.lagradost.shiro.utils.ShiroApi.Companion.getAnimePageNew
import com.lagradost.shiro.utils.ShiroApi.Companion.getFav
import com.lagradost.shiro.utils.ShiroApi.Companion.getFullUrlCdn
import com.lagradost.shiro.utils.ShiroApi.Companion.getSlugFromMalId
import com.lagradost.shiro.utils.ShiroApi.Companion.getSubbed
import com.lagradost.shiro.utils.ShiroApi.Companion.requestHome
import com.lagradost.shiro.utils.extractors.Vidstream
import com.lagradost.shiro.utils.mvvm.logError
import com.lagradost.shiro.utils.mvvm.normalSafeApiCall
import org.json.JSONObject
import java.io.*
import java.net.URL
import java.net.URLDecoder
import java.security.MessageDigest
import kotlin.concurrent.thread
import kotlin.math.roundToInt


object AppUtils {
    var settingsManager: SharedPreferences? = null
    var allApi: Vidstream = Vidstream()
    val mapper: JsonMapper = JsonMapper.builder().addModule(KotlinModule())
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false).build()

    fun FragmentActivity.init() {
        settingsManager = PreferenceManager.getDefaultSharedPreferences(this)
        allApi.providersActive = settingsManager?.getStringSet("selected_providers", hashSetOf()) as HashSet<String>
    }

    fun <T : Any> T?.notNull(f: (it: T) -> Unit) {
        if (this != null) f(this)
    }

    fun unixTime(): Long {
        return System.currentTimeMillis() / 1000L
    }

    fun FragmentActivity.isTv(): Boolean {
        val uiModeManager: UiModeManager = getSystemService(UI_MODE_SERVICE) as UiModeManager
        return uiModeManager.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
    }

    fun Context.getStatusBarHeight(): Int {
        val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resourceId > 0) {
            resources.getDimensionPixelSize(resourceId)
        } else
            0
    }

    fun Any.toJson(): String{
        return mapper.writeValueAsString(this)
    }

    fun Context.getNavigationBarHeight(): Int {
        val resourceId = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (resourceId > 0) {
            resources.getDimensionPixelSize(resourceId)
        } else 0
    }


    fun FragmentActivity.handleIntent(intent: Intent?) {
        val data: Uri? = intent?.data

        if (data != null) {
            val dataString = data.toString()
            if (dataString != "") {
                if (dataString.contains("shiroapp")) {
                    if (dataString.contains("/anilistlogin")) {
                        authenticateLogin(dataString)
                    } else if (dataString.contains("/mallogin")) {
                        authenticateMalLogin(dataString)
                    }
                }
            }

            thread {
                when {
                    /** Shiro url */
                    data.toString().contains("********") -> {
                        val urlRegex = Regex("""shiro\.is/anime/(.*?)(/\d+|)$""")
                        val found = urlRegex.find(data.toString())
                        if (found != null) {
                            val (slug, episode) = found.destructured
                            // Kinda hack using 2 slugs, but should work semi fine
                            println("SLUG $slug EPISODE $episode")
                            val num = episode.replace("/", "").toIntOrNull()
                            val slugFixed = slug.replace("/", "")
                            if (num != null) {
                                val page = getAnimePageNew(slugFixed)
                                main {
                                    if (page?.data != null && page.data.episodes.size >= num && num > 0) {
                                        loadPlayer(num - 1, 0L, page.data)
                                    } else {
                                        loadPage(slugFixed, slugFixed)
                                    }
                                }
                            } else {
                                main {
                                    loadPage(slugFixed, slugFixed)
                                }
                            }

                        }
                    }
                    /** MAL url */
                    data.toString().contains("myanimelist.net") -> {
                        val urlRegex = Regex("""myanimelist\.net/anime/(.*)/(.*)""")
                        val found = urlRegex.find(data.toString())
                        if (found != null) {
                            val (malId, title) = found.destructured
                            // Kinda hack using 2 slugs, but should work semi fine
                            getSlugFromMalId(malId, title)?.let { slug ->
                                main {
                                    loadPage(slug, slug)
                                }
                            }
                        }
                    }
                }
            }


        } else {
            initGetUser()
        }
    }

    fun Context.isUsingMobileData(): Boolean {
        val conManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        //val networkInfo = conManager.allNetworks
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            conManager.getNetworkCapabilities(conManager.activeNetwork)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
        } else {
            conManager.activeNetworkInfo?.type == ConnectivityManager.TYPE_MOBILE
        }
        /*return networkInfo.any {
            conManager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
        }*/
    }

    fun Context.getNavigationBarSizeFake(): Int {
        val resourceId = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (resourceId > 0) {
            resources.getDimensionPixelSize(resourceId)
        } else
            0
    }

    fun Context.installCache() {
        try {
            val httpCacheDir = File(cacheDir, "http")
            val httpCacheSize: Long = 50 * 1024 * 1024 // 50 MiB
            HttpResponseCache.install(httpCacheDir, httpCacheSize)
        } catch (e: Exception) {
            logError(e)
        }
    }


    fun FragmentActivity.getNavController(): NavController? {
        return navController
            ?: (supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as? NavHostFragment?)?.navController
            ?: (supportFragmentManager.findFragmentById(R.id.home_root_tv) as? NavHostFragment?)?.navController
    }

    // https://stackoverflow.com/questions/20264268/how-do-i-get-the-height-and-width-of-the-android-navigation-bar-programmatically
    fun Context.getNavigationBarSize(): Point {
        val appUsableSize = getAppUsableScreenSize(this)
        val realScreenSize = getRealScreenSize(this)

        // navigation bar on the side
        if (appUsableSize.x < realScreenSize.x) {
            return Point(realScreenSize.x - appUsableSize.x, appUsableSize.y)
        }

        // navigation bar at the bottom
        return if (appUsableSize.y < realScreenSize.y) {
            Point(appUsableSize.x, realScreenSize.y - appUsableSize.y)
        } else Point()

        // navigation bar is not present
    }

    private fun getAppUsableScreenSize(context: Context): Point {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        //windowManager.currentWindowMetrics.bounds
        val display = windowManager.defaultDisplay
        val size = Point()
        display.getSize(size)
        return size
    }

    private fun getRealScreenSize(context: Context): Point {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val display = windowManager.defaultDisplay
        val size = Point()
        display.getRealSize(size)
        return size
    }

    fun Context.castEpisode(links: List<ExtractorLink>, data: ShiroApi.CommonAnimePageData, episodeIndex: Int) {
        val castContext = CastContext.getSharedInstance(this)
        castContext.castOptions
        val key = getViewKey(data.slug, episodeIndex)
        val mediaItems = links.map {
            val movieMetadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE)
            movieMetadata.putString(
                MediaMetadata.KEY_TITLE,
                "Episode ${episodeIndex + 1} - ${it.name}"
            )
            movieMetadata.putString(MediaMetadata.KEY_ALBUM_ARTIST, data.name)
            movieMetadata.addImage(WebImage(Uri.parse(getFullUrlCdn(data.image))))
            MediaQueueItem.Builder(
                MediaInfo.Builder(it.url)
                    .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
                    .setContentType(MimeTypes.VIDEO_UNKNOWN)
                    .setCustomData(JSONObject().put("data", it.name))
                    .setMetadata(movieMetadata)
                    .build()
            ).build()
        }.toTypedArray()

        val castPlayer = CastPlayer(castContext)
        castPlayer.loadItems(
            mediaItems,
            0,
            getKey(VIEW_POS_KEY, key, 0L)!!,
            REPEAT_MODE_REPEAT_SINGLE
        )
    }


    // https://stackoverflow.com/questions/67334537/how-to-make-viewpager2-less-sensitive
    fun ViewPager2.reduceDragSensitivity() {
        normalSafeApiCall {
            val recyclerViewField = ViewPager2::class.java.getDeclaredField("mRecyclerView")
            recyclerViewField.isAccessible = true
            val recyclerView = recyclerViewField.get(this) as RecyclerView

            val touchSlopField = RecyclerView::class.java.getDeclaredField("mTouchSlop")
            touchSlopField.isAccessible = true
            val touchSlop = touchSlopField.get(recyclerView) as Int
            touchSlopField.set(recyclerView, touchSlop * 2)       // "4" was obtained experimentally
        }
    }

    // Guarantee slug is dubbed or not
    fun ShiroApi.Companion.AnimePageNewData.dubbify(turnDubbed: Boolean): ShiroApi.Companion.AnimePageNewData {
        return this.copy(
            anime = this.anime.dubbify(turnDubbed)
        )
    }

    /**| S1:E2 Hello World
     * | Episode 2. Hello world
     * | Hello World
     * | Season 1 - Episode 2
     * | Episode 2
     * **/
    fun getNameFull(name: String?, episode: Int?, season: Int?): String {
        val rEpisode = if (episode == 0) null else episode
        val rSeason = if (season == 0) null else season

        if (name != null) {
            return if (rEpisode != null && rSeason != null) {
                "S${rSeason}:E${rEpisode} $name"
            } else if (rEpisode != null) {
                "Episode $rEpisode. $name"
            } else {
                name
            }
        } else {
            if (rEpisode != null && rSeason != null) {
                return "Season $rSeason - Episode $rEpisode"
            } else if (rSeason == null) {
                return "Episode $rEpisode"
            }
        }
        return ""
    }

    /**id, stringRes */
    @SuppressLint("RestrictedApi")
    fun View.popupMenuNoIcons(
        items: List<Pair<Int, Int>>,
        onMenuItemClick: MenuItem.() -> Unit,
    ): PopupMenu {
        val ctw = ContextThemeWrapper(context, R.style.PopupMenu)
        val popup = PopupMenu(ctw, this, Gravity.NO_GRAVITY, R.attr.actionOverflowMenuStyle, 0)

        items.forEach { (id, stringRes) ->
            popup.menu.add(0, id, 0, stringRes)
        }

        (popup.menu as? MenuBuilder)?.setOptionalIconsVisible(true)

        popup.setOnMenuItemClickListener {
            it.onMenuItemClick()
            true
        }

        popup.show()
        return popup
    }

    // Guarantee slug is dubbed or not
    fun ShiroApi.Companion.AnimePageNew.dubbify(turnDubbed: Boolean): ShiroApi.Companion.AnimePageNew {
        return copy(
            slug = if (turnDubbed) {
                slug.removeSuffix("-dub") + "-dub"
            } else {
                slug.removeSuffix("-dub")
            }
        )
    }

    fun getTheme(): Int? {
        return when (settingsManager?.getString("accent_color", "red")) {
            "Default Red" -> null
            "Blue" -> R.style.OverlayPrimaryColorBlue
            "Purple" -> R.style.OverlayPrimaryColorPurple
            "Green" -> R.style.OverlayPrimaryColorGreenApple
            "Pink" -> R.style.OverlayPrimaryColorPink
            "Orange" -> R.style.OverlayPrimaryColorOrange
            "Lime" -> R.style.OverlayPrimaryColorLime
            "Yellow" -> R.style.OverlayPrimaryColorYellow
            "Light Blue" -> R.style.OverlayPrimaryColorLightBlue
            else -> null
        }
    }

    // Guarantee slug is dubbed or not
    fun String.dubbify(turnDubbed: Boolean): String {
        return if (turnDubbed) {
            this.removeSuffix("-dub") + "-dub"
        } else {
            this.removeSuffix("-dub")
        }
    }

    fun expandTouchArea(bigView: View?, smallView: View?, extraPadding: Int) {
        bigView?.post {
            val rect = Rect()
            smallView?.getHitRect(rect)
            rect.top -= extraPadding
            rect.left -= extraPadding
            rect.right += extraPadding
            rect.bottom += extraPadding
            bigView.touchDelegate = TouchDelegate(rect, smallView)
        }
    }

    enum class AspectRatioTV(val value: Int) {
        RESIZE_MODE_FIT(0), // Normal
        RESIZE_MODE_STRETCH(1), // Stretch
        RESIZE_MODE_ZOOM(2), // Zoom
        RESIZE_MODE_4_3(3), // 4:3
    }

    fun SurfaceView.setAspectRatio(ratio: AspectRatioTV, size: VideoSize) {
        println("Changed surface view $ratio ${size.width} ${size.height}")

        val maxWidth = rootView.measuredWidth
        val maxHeight = rootView.measuredHeight

        val widthScale = maxWidth.toFloat() / size.width
        val heightScale = maxHeight.toFloat() / size.height

        val isHorizontalBlackBars = heightScale > widthScale
        val smallestScale = minOf(widthScale, heightScale)

        val normalHeight = if (isHorizontalBlackBars) (smallestScale * size.height).toInt() else MATCH_PARENT
        val normalWidth = if (isHorizontalBlackBars) MATCH_PARENT else (smallestScale * size.width).toInt()

        when (ratio) {
            AspectRatioTV.RESIZE_MODE_FIT -> {
                scaleX = 1f
                scaleY = 1f
                layoutParams = layoutParams.apply {
                    width = normalWidth
                    height = normalHeight
                }
            }
            AspectRatioTV.RESIZE_MODE_STRETCH -> {
                scaleX = 1f
                scaleY = 1f
                layoutParams = layoutParams.apply {
                    height = MATCH_PARENT
                    width = MATCH_PARENT
                }
            }
            AspectRatioTV.RESIZE_MODE_ZOOM -> {
                val biggestScale = maxOf(widthScale, heightScale)
                layoutParams = layoutParams.apply {
                    height = normalHeight
                    width = normalWidth
                }
                val scale = biggestScale / smallestScale
                scaleX = scale
                scaleY = scale
            }

            AspectRatioTV.RESIZE_MODE_4_3 -> {
                scaleX = 1f
                scaleY = 1f
                layoutParams = layoutParams.apply {
                    width = (maxHeight.toFloat() * (4F / 3F)).toInt()
                    height = MATCH_PARENT
                }
            }

        }
    }

    fun getVideoContentUri(context: Context, videoFilePath: String): Uri? {
        val cursor = context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Video.Media._ID),
            MediaStore.Video.Media.DATA + "=? ", arrayOf(videoFilePath), null
        )
        return if (cursor != null && cursor.moveToFirst()) {
            val id = cursor.getInt(cursor.getColumnIndex(MediaStore.MediaColumns._ID))
            cursor.close()
            Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "" + id)
        } else {
            val values = ContentValues()
            values.put(MediaStore.Video.Media.DATA, videoFilePath)
            context.contentResolver.insert(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values
            )
        }
    }

    // Copied from https://github.com/videolan/vlc-android/blob/master/application/vlc-android/src/org/videolan/vlc/util/FileUtils.kt
    fun Context.getUri(data: Uri?): Uri? {
        var uri = data
        val ctx = this
        if (data != null && data.scheme == "content") {
            // Mail-based apps - download the stream to a temporary file and play it
            if ("com.fsck.k9.attachmentprovider" == data.host || "gmail-ls" == data.host) {
                var inputStream: InputStream? = null
                var os: OutputStream? = null
                var cursor: Cursor? = null
                try {
                    cursor = ctx.contentResolver.query(
                        data,
                        arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null
                    )
                    if (cursor != null && cursor.moveToFirst()) {
                        val filename = cursor.getString(cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME))
                            .replace("/", "")
                        inputStream = ctx.contentResolver.openInputStream(data)
                        if (inputStream == null) return data
                        os = FileOutputStream(Environment.getExternalStorageDirectory().path + "/Download/" + filename)
                        val buffer = ByteArray(1024)
                        var bytesRead = inputStream.read(buffer)
                        while (bytesRead >= 0) {
                            os.write(buffer, 0, bytesRead)
                            bytesRead = inputStream.read(buffer)
                        }
                        uri =
                            Uri.fromFile(File(Environment.getExternalStorageDirectory().path + "/Download/" + filename))
                    }
                } catch (e: Exception) {
                    return null
                } finally {
                    inputStream?.close()
                    os?.close()
                    cursor?.close()
                }
            } else if (data.authority == "media") {
                uri = this.contentResolver.query(
                    data,
                    arrayOf(MediaStore.Video.Media.DATA), null, null, null
                )?.use {
                    val columnIndex = it.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
                    if (it.moveToFirst()) Uri.fromFile(File(it.getString(columnIndex))) ?: data else data
                }
                //uri = MediaUtils.getContentMediaUri(data)
                /*} else if (data.authority == ctx.getString(R.string.tv_provider_authority)) {
                    println("TV AUTHORITY")
                    //val medialibrary = Medialibrary.getInstance()
                    //val media = medialibrary.getMedia(data.lastPathSegment!!.toLong())
                    uri = null//media.uri*/
            } else {
                val inputPFD: ParcelFileDescriptor?
                try {
                    inputPFD = ctx.contentResolver.openFileDescriptor(data, "r")
                    if (inputPFD == null) return data
                    uri = Uri.parse("fd://" + inputPFD.fd)
                    //                    Cursor returnCursor =
                    //                            getContentResolver().query(data, null, null, null, null);
                    //                    if (returnCursor != null) {
                    //                        if (returnCursor.getCount() > 0) {
                    //                            int nameIndex = returnCursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    //                            if (nameIndex > -1) {
                    //                                returnCursor.moveToFirst();
                    //                                title = returnCursor.getString(nameIndex);
                    //                            }
                    //                        }
                    //                        returnCursor.close();
                    //                    }
                } catch (e: FileNotFoundException) {
                    Log.e("TAG", "${e.message} for $data", e)
                    return null
                } catch (e: IllegalArgumentException) {
                    Log.e("TAG", "${e.message} for $data", e)
                    return null
                } catch (e: IllegalStateException) {
                    Log.e("TAG", "${e.message} for $data", e)
                    return null
                } catch (e: NullPointerException) {
                    Log.e("TAG", "${e.message} for $data", e)
                    return null
                } catch (e: SecurityException) {
                    Log.e("TAG", "${e.message} for $data", e)
                    return null
                }
            }// Media or MMS URI
        }
        return uri
    }

    private fun Context.toggleHeart(name: String, image: String, slug: String): Boolean {
        /*Saving the new bookmark in the database*/
        val isBookmarked = getKey<BookmarkedTitle>(BOOKMARK_KEY, slug, null) != null
        if (!isBookmarked) {
            setKey(
                BOOKMARK_KEY,
                slug,
                BookmarkedTitle(
                    name,
                    image,
                    slug,
                    null,
                )
            )
        } else {
            removeKey(BOOKMARK_KEY, slug)
            removeKey(BOOKMARK_KEY, slug.replace("-dub", "-dubbed"))
        }
        thread {
            homeViewModel?.favorites?.postValue(getFav())
        }
        return !isBookmarked
    }

    fun Context.onLongCardClick(card: ShiroApi.CommonAnimePage): Boolean {
        var isBookmarked: Boolean? = null
        val selected =
            if (tvActivity != null && settingsManager?.getBoolean("hold_to_favorite", false) == true) "Toggle Favorite"
            else settingsManager?.getString("hold_behavior", "Show Toast") ?: "Show Toast"

        if (selected == "Toggle Favorite" || selected == "Toggle Favorite and Subscribe") {
            isBookmarked = toggleHeart(card.name, card.image, card.slug)
            println("CARD SLUG ${card.slug}")
            val prefix = if (isBookmarked) "Added" else "Removed"
            Toast.makeText(this, "$prefix ${card.name}", Toast.LENGTH_SHORT).show()
        }
        if (selected == "Toggle Subscribe" || selected == "Toggle Favorite and Subscribe") {
            subscribeToShow(card/*, isBookmarked*/)
        }
        if (selected == "Show Toast") {
            Toast.makeText(this, card.name, Toast.LENGTH_SHORT).show()
        }
        return isBookmarked ?: false
    }

    // TODO USE THIS IN RESULT_FRAGMENT
    private fun Context.subscribeToShow(data: ShiroApi.CommonAnimePage/*, isBookmarked: Boolean? = null*/) {
        // isBookmarked
        val subbedBookmark = getKey<BookmarkedTitle>(SUBSCRIPTIONS_BOOKMARK_KEY, data.slug, null)
        val isSubbedOld = getKey(SUBSCRIPTIONS_KEY, data.slug, false)!!
        val isSubbed = isSubbedOld || subbedBookmark != null

        if (isSubbed /*&& !(isBookmarked ?: !isSubbed)*/) {
            Firebase.messaging.unsubscribeFromTopic(data.slug)
                .addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        removeKey(SUBSCRIPTIONS_BOOKMARK_KEY, data.slug)
                        removeKey(SUBSCRIPTIONS_KEY, data.slug)
                    }
                    var msg = "Unsubscribed to ${data.name}"//getString(R.string.msg_subscribed)
                    if (!task.isSuccessful) {
                        msg = "Unsubscribing failed :("//getString(R.string.msg_subscribe_failed)
                    }
                    thread {
                        homeViewModel?.subscribed?.postValue(getSubbed())
                    }
                    //Log.d(TAG, msg)
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                }
        } else /*if (!isSubbed && (isBookmarked ?: !isSubbed))*/ {
            Firebase.messaging.subscribeToTopic(data.slug)
                .addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        setKey(
                            SUBSCRIPTIONS_BOOKMARK_KEY, data.slug, BookmarkedTitle(
                                data.name,
                                data.image,
                                data.slug,
                                data.english
                            )
                        )
                    }
                    var msg = "Subscribed to ${data.name}"//getString(R.string.msg_subscribed)
                    if (!task.isSuccessful) {
                        msg = "Subscription failed :("//getString(R.string.msg_subscribe_failed)
                    }
                    thread {
                        homeViewModel?.subscribed?.postValue(getSubbed())
                    }
                    //Log.d(TAG, msg)
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                }
        }
    }


    fun adjustAlpha(@ColorInt color: Int, factor: Float): Int {
        val alpha = (Color.alpha(color) * factor).roundToInt()
        val red = Color.red(color)
        val green = Color.green(color)
        val blue = Color.blue(color)
        return Color.argb(alpha, red, green, blue)
    }

    fun filterCardList(cards: List<ShiroApi.CommonAnimePage>?): List<ShiroApi.CommonAnimePage>? {
        return when (settingsManager!!.getString("hide_behavior", "None")) {
            "Hide dubbed" ->
                cards?.filter { it.slug.endsWith("-dub").not() }
            "Hide subbed" ->
                cards?.filter { it.slug.endsWith("-dub") }
            else ->
                cards
        }
    }

    fun FragmentActivity.addFragmentOnlyOnce(location: Int, fragment: Fragment, tag: String) {
        // Make sure the current transaction finishes first
        normalSafeApiCall {
            supportFragmentManager.executePendingTransactions()

            // If there is no fragment yet with this tag...
            if (supportFragmentManager.findFragmentByTag(tag) == null) {
                supportFragmentManager.beginTransaction()
                    .setCustomAnimations(R.anim.enter, R.anim.exit, R.anim.pop_enter, R.anim.pop_exit)
                    .add(location, fragment, tag)
                    .commitAllowingStateLoss()
            }
        }
    }

    fun Context.isCastApiAvailable(): Boolean {
        val isCastApiAvailable =
            GoogleApiAvailability.getInstance()
                .isGooglePlayServicesAvailable(applicationContext) == ConnectionResult.SUCCESS
        try {
            applicationContext?.let { CastContext.getSharedInstance(it) }
        } catch (e: Exception) {
            // track non-fatal
            return false
        }
        return isCastApiAvailable
    }


    fun getViewKey(data: PlayerData): String {
        return getViewKey(
            data.slug,
            data.episodeIndex!!
        )
    }

    fun getViewKey(id: String, episodeIndex: Int): String {
        return id + "E" + episodeIndex
    }

    fun View.hideKeyboard() {
        val inputMethodManager = this.context.getSystemService(Activity.INPUT_METHOD_SERVICE) as? InputMethodManager?
        inputMethodManager?.hideSoftInputFromWindow(this.windowToken, 0)
    }

    fun Fragment.hideKeyboard() {
        view?.hideKeyboard()
    }

    fun getCurrentActivity(): CyaneaAppCompatActivity? {
        return settingsActivity ?: activity ?: tvActivity ?: playerActivity
    }

    fun getCurrentContext(): Context? {
        return getAppContext() ?: getCurrentActivity()
    }

    // If this gets NPE the app deserves to crash
    fun guaranteedContext(context: Context?): Context {
        return context ?: getCurrentContext()!!
    }

    fun Activity.requestAudioFocus(focusRequest: AudioFocusRequest?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && focusRequest != null) {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.requestAudioFocus(focusRequest)
        } else {
            val audioManager: AudioManager =
                getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.requestAudioFocus(
                null,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            )
        }
    }

    fun FragmentActivity.changeStatusBarState(hide: Boolean): Int {
        return if (hide) {
            window?.setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN
            )
            0
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            getStatusBarHeight()
        }
    }

    fun Context.openBrowser(url: String) {
        if (tvActivity != null) {
            tvActivity?.addFragmentOnlyOnce(android.R.id.content, WebViewFragment.newInstance(url), "WEB_VIEW")
        } else {
            val components = arrayOf(ComponentName(applicationContext, MainActivity::class.java))
            val intent = Intent(Intent.ACTION_VIEW)
            intent.data = Uri.parse(url)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
                startActivity(Intent.createChooser(intent, null).putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, components))
            else
                startActivity(intent)
        }
    }

    fun FragmentActivity.showNavigation() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.navigationBarColor = Cyanea.instance.backgroundColor
        }
    }

    // https://stackoverflow.com/questions/29069070/completely-transparent-status-bar-and-navigation-bar-on-lollipop
    fun FragmentActivity.transparentStatusAndNavigation(
        systemUiScrim: Int = Color.parseColor("#40000000") // 25% black
    ) {
        var systemUiVisibility = 0
        // Use a dark scrim by default since light status is API 23+
        // var statusBarColor = systemUiScrim
        // Use a dark scrim by default since light nav bar is API 27+
        var navigationBarColor = systemUiScrim
        val winParams = window.attributes

        /*if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            systemUiVisibility = systemUiVisibility// or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            statusBarColor = Color.TRANSPARENT
        }*/
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            systemUiVisibility =
                if (Cyanea.instance.isLight) systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else systemUiVisibility
            navigationBarColor = Color.TRANSPARENT
        }
        systemUiVisibility = systemUiVisibility or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        window.decorView.systemUiVisibility = systemUiVisibility
        winParams.flags = winParams.flags or
                //WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS or
                WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            winParams.flags = winParams.flags and
                    //(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS or
                    WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION.inv()
            //window.statusBarColor = statusBarColor
            window.navigationBarColor = navigationBarColor
        }

        window.attributes = winParams
    }


    fun Context.getViewPosDur(aniListId: String, episodeIndex: Int): EpisodePosDurInfo {
        val key = getViewKey(aniListId, episodeIndex)

        return EpisodePosDurInfo(
            getKey(VIEW_POS_KEY, key, -1L) ?: -1L,
            getKey(VIEW_DUR_KEY, key, -1L) ?: -1L,
            containsKey(VIEWSTATE_KEY, key)
        )
    }

    private fun canPlayNextEpisode(card: ShiroApi.Companion.AnimePageNewData, episodeIndex: Int): NextEpisode {
        val canNext = card.episodes.size > episodeIndex + 1

        return if (canNext) {
            NextEpisode(true, episodeIndex + 1, 0)
        } else {
            NextEpisode(false, episodeIndex + 1, 0)
        }
    }

    fun Context.getLatestSeenEpisode(data: ShiroApi.Companion.AnimePageNewData): NextEpisode {
        for (i in (data.episodes.size.minus(1) ?: 0) downTo 0) {
            val firstPos = getViewPosDur(data.anime.slug, i)
            if (firstPos.viewstate) {
                return NextEpisode(true, i, 0)
            }
        }
        return NextEpisode(false, 0, 0)
    }


    fun FragmentActivity.displayCardData(
        data: List<ShiroApi.CommonAnimePage>?,
        resView: RecyclerView,
        textView: TextView,
        isOnTop: Boolean = false,
        adapter: RecyclerView.Adapter<RecyclerView.ViewHolder>? = null,
        overrideHideDubbed: Boolean = false
    ) {
        val newAdapter: RecyclerView.Adapter<RecyclerView.ViewHolder> = adapter ?: CardAdapter(
            this,
            ArrayList(),
            isOnTop
        )

        //val snapHelper = PagerSnapHelper()
        //snapHelper.attachToRecyclerView(scrollView)

        val filteredData = if (overrideHideDubbed) data else filterCardList(data)
        resView.adapter = newAdapter

        (ArrayList(filteredData ?: listOf())).let {
            (resView.adapter as CardAdapter).cardList = it
        }
        (resView.adapter as CardAdapter).notifyDataSetChanged()

//        val layoutId = if (tvActivity != null) R.id.home_root_tv else R.id.homeRoot
        textView.setOnClickListener {
            val arguments = Bundle().apply {
                putString(CARD_LIST, mapper.writeValueAsString(data))
                putString(TITLE, textView.text.toString())
            }
            getNavController()?.navigate(
                R.id.action_navigation_home_to_navigation_expanded_home,
                arguments,
            )
        }
    }

    fun FragmentActivity.displayCardData(
        data: List<LastEpisodeInfo?>?,
        resView: RecyclerView,
        isOnTop: Boolean = false
    ) {
        val adapter: RecyclerView.Adapter<RecyclerView.ViewHolder> = CardContinueAdapter(
            this,
            listOf(),
            isOnTop
        )

        //val snapHelper = LinearSnapHelper()
        //snapHelper.attachToRecyclerView(scrollView)

        resView.adapter = adapter
        if (data != null) {
            (resView.adapter as CardContinueAdapter).cardList = data
            (resView.adapter as CardContinueAdapter).notifyDataSetChanged()
        }
    }


    fun Context.getNextEpisode(data: ShiroApi.Companion.AnimePageNewData): NextEpisode {
        // HANDLES THE LOGIC FOR NEXT EPISODE
        var episodeIndex = 0
        var seasonIndex = 0
        val maxValue = 90
        val firstPos = getViewPosDur(data.anime.slug, 0)
        // Hacky but works :)
        if (((firstPos.pos * 100) / firstPos.dur <= maxValue || firstPos.pos == -1L) && !firstPos.viewstate) {
            val found = data.episodes.getOrNull(episodeIndex) != null
            return NextEpisode(found, episodeIndex, seasonIndex)
        }

        while (true) { // IF PROGRESS IS OVER 95% CONTINUE SEARCH FOR NEXT EPISODE
            val next = canPlayNextEpisode(data, episodeIndex)
            if (next.isFound) {
                val nextPro = getViewPosDur(data.anime.slug, next.episodeIndex)
                seasonIndex = next.seasonIndex
                episodeIndex = next.episodeIndex
                if (((nextPro.pos * 100) / nextPro.dur <= maxValue || nextPro.pos == -1L) && !nextPro.viewstate) {
                    return NextEpisode(true, episodeIndex, seasonIndex)
                }
            } else {
                val found = data.episodes.getOrNull(episodeIndex) != null
                return NextEpisode(found, episodeIndex, seasonIndex)
            }
        }
    }


    fun Context.setViewPosDur(data: PlayerData, pos: Long, dur: Long) {
        val key = getViewKey(data)

        if (settingsManager?.getBoolean("save_history", true) == true) {
            setKey(VIEW_POS_KEY, key, pos)
            setKey(VIEW_DUR_KEY, key, dur)
            setKey(VIEWSTATE_KEY, key, System.currentTimeMillis())
        }

        if (data.card == null) return

        // HANDLES THE LOGIC FOR NEXT EPISODE
        var episodeIndex = data.episodeIndex!!
        var seasonIndex = data.seasonIndex!!
        val maxValue = 90
        var canContinue: Boolean = (pos * 100 / dur) > maxValue
        var isFound = true
        var _pos = pos
        var _dur = dur

        val card = data.card
        while (canContinue) { // IF PROGRESS IS OVER 95% CONTINUE SEARCH FOR NEXT EPISODE
            val next = canPlayNextEpisode(card, episodeIndex)
            if (next.isFound) {
                val nextPro = getViewPosDur(card.anime.slug, next.episodeIndex)
                seasonIndex = next.seasonIndex
                episodeIndex = next.episodeIndex
                if ((nextPro.pos * 100) / dur <= maxValue) {
                    _pos = nextPro.pos
                    _dur = nextPro.dur
                    canContinue = false
                    isFound = true
                }
            } else {
                canContinue = false
                isFound = false
            }
        }

        //if (!isFound) return

        if (settingsManager?.getBoolean("save_history", true) == true) {
            setKey(
                VIEW_LST_KEY,
                data.card.anime.slug,
                LastEpisodeInfo(
                    _pos,
                    _dur,
                    System.currentTimeMillis(),
                    card,
                    card.anime.slug,
                    episodeIndex,
                    seasonIndex,
                    data.card.episodes.size == 1 && data.card.anime.status.lowercase() == "finished airing",
                    card.episodes.getOrNull(episodeIndex),
                    card.anime.poster,
                    card.anime.title,
                    card.anime.banner,
                    data.anilistID,
                    data.malID,
                    data.fillerEpisodes
                )
            )

            thread {
                requestHome(true)
            }
        }
    }

    fun Context.checkWrite(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun FragmentActivity.requestRW() {
        ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        )

        ActivityCompat.requestPermissions(
            this,
            arrayOf(
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ),
            1337
        )
    }

    fun fixCardTitle(title: String): String {
        val suffix = " (Dub)"
        return if (title.endsWith(suffix)) "✦ ${
            title.substring(
                0,
                title.length - suffix.length
            ).replace(" (Anime)", "")
        }" else title.replace(" (Anime)", "")
    }

    fun splitQuery(url: URL): Map<String, String> {
        val queryPairs: MutableMap<String, String> = LinkedHashMap()
        val query: String = url.query
        val pairs = query.split("&").toTypedArray()
        for (pair in pairs) {
            val idx = pair.indexOf("=")
            queryPairs[URLDecoder.decode(pair.substring(0, idx), "UTF-8")] =
                URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
        }
        return queryPairs
    }

    fun FragmentActivity.hideSystemUI() {
        // Enables regular immersive mode.
        // For "lean back" mode, remove SYSTEM_UI_FLAG_IMMERSIVE.
        // Or for "sticky immersive," replace it with SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        window?.decorView?.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                // Set the content to appear under the system bars so that the
                // content doesn't resize when the system bars hide and show.
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                // Hide the nav bar and status bar
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                )
    }

    fun String.md5(): String {
        return hashString(this, "MD5")
    }

    fun String.sha256(): String {
        return hashString(this, "SHA-256")
    }

    private fun hashString(input: String, algorithm: String): String {
        return MessageDigest
            .getInstance(algorithm)
            .digest(input.toByteArray())
            .fold("") { str, it -> str + "%02x".format(it) }
    }

    // Shows the system bars by removing all the flags
// except for the ones that make the content appear under the system bars.
    fun Activity.showSystemUI() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)

    }

    fun <T> LifecycleOwner.observe(liveData: LiveData<T>, action: (t: T) -> Unit) {
        liveData.observe(this) { it?.let { t -> action(t) } }
    }

    fun FragmentActivity.loadPlayer(
        episodeIndex: Int,
        startAt: Long,
        card: ShiroApi.Companion.AnimePageNewData,
        anilistID: Int? = null,
        malID: Int? = null,
        fillerEpisodes: HashMap<Int, Boolean>? = null
    ) {
        loadPlayer(
            PlayerData(
                null, null,
                episodeIndex,
                0,
                card,
                startAt,
                card.anime.slug,
                anilistID,
                malID,
                fillerEpisodes
            )
        )
    }

/*fun loadPlayer(pageData: FastAniApi.AnimePageData, episodeIndex: Int, startAt: Long?) {
    loadPlayer(PlayerData("${pageData.name} - Episode ${episodeIndex + 1}", null, episodeIndex, null, null, startAt, null, true))
}
fun loadPlayer(title: String?, url: String, startAt: Long?) {
    loadPlayer(PlayerData(title, url, null, null, null, startAt, null))
}*/

    fun FragmentActivity.loadPlayer(data: PlayerData) {
        runOnUiThread {
            masterViewModel?.playerData?.value = data
            val destination =
                if (tvActivity != null) R.id.global_to_navigation_player_tv else R.id.global_to_navigation_player
            getNavController()?.navigate(
                destination
            )
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
        }
    }

    fun FragmentActivity.loadPage(slug: String, name: String, isMalId: Boolean = false) {
        getNavController()?.let {
            val arguments = Bundle().apply {
                putString(SLUG, slug.replace("-dubbed", "-dub"))
                putString(NAME, name)
                putBoolean(IS_MAL_ID, isMalId)
            }

//            val extras = sharedView?.let { sharedView ->
//                ViewCompat.setTransitionName(
//                    sharedView,
//                    "shared_element_container"
//                )
//                FragmentNavigatorExtras(sharedView to "shared_element_container")
//            }
            val destination =
                if (tvActivity != null) R.id.global_to_navigation_results_tv else R.id.global_to_navigation_results
            it.navigate(destination, arguments, null, null)
        }
    }

    fun Context.getTextColor(isGrey: Boolean = false): Int {
        return if (Cyanea.instance.isDark) {
            val color = if (isGrey) R.color.textColorGray else R.color.textColor
            ContextCompat.getColor(this, color)
        } else {
            val color = if (isGrey) R.color.lightTextColorGray else R.color.lightTextColor
            ContextCompat.getColor(this, color)
        }
    }

    @ColorInt
    fun Context.getColorFromAttr(
        @AttrRes attrColor: Int,
        typedValue: TypedValue = TypedValue(),
        resolveRefs: Boolean = true,
    ): Int {
        theme.resolveAttribute(attrColor, typedValue, resolveRefs)
        return typedValue.data
    }

    fun shouldShowPIPMode(isInPlayer: Boolean): Boolean {
        return settingsManager?.getBoolean("pip_enabled", false) ?: false && isInPlayer
    }

    fun Context.hasPIPPermission(): Boolean {
        val appOps =
            getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        return appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_PICTURE_IN_PICTURE,
            android.os.Process.myUid(),
            packageName
        ) == AppOpsManager.MODE_ALLOWED
    }


}