package com.lagradost.shiro.utils

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.source.DefaultMediaSourceFactory
import com.google.android.exoplayer2.ui.PlayerView
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource
import com.lagradost.shiro.utils.mvvm.logError
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * In-app trailers. Trailer tokens are "youtube:<id>" or "dailymotion:<id>" (see the metadata providers). YouTube
 * trailers are resolved to a direct stream with NewPipe's extractor and played in the app's own ExoPlayer, so they
 * never leave the app. When a trailer cannot be resolved, the dialog says so and offers to open it in the browser.
 */
object Trailers {
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"
    private var initialised = false

    private class NpDownloader : Downloader() {
        private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()

        override fun execute(request: Request): Response {
            val method = request.httpMethod()
            val body = if (method == "GET" || method == "HEAD") null else (request.dataToSend() ?: ByteArray(0)).toRequestBody()
            val b = okhttp3.Request.Builder().url(request.url()).method(method, body).header("User-Agent", UA)
            request.headers().forEach { (k, values) ->
                b.removeHeader(k)
                values.forEach { b.addHeader(k, it) }
            }
            client.newCall(b.build()).execute().use { res ->
                if (res.code == 429) throw ReCaptchaException("Rate limited", request.url())
                return Response(res.code, res.message, res.headers.toMultimap(), res.body?.string(), res.request.url.toString())
            }
        }
    }

    private fun init() {
        if (initialised) return
        NewPipe.init(NpDownloader())
        initialised = true
    }

    fun id(token: String) = token.substringAfter(':', "")
    fun site(token: String) = token.substringBefore(':', "")

    fun webUrl(token: String): String? = when (site(token)) {
        "youtube" -> "https://www.youtube.com/watch?v=${id(token)}"
        "dailymotion" -> "https://www.dailymotion.com/video/${id(token)}"
        else -> null
    }

    /** Blocking. Returns a direct stream URL, or null when the trailer can't be played inside the app. */
    fun resolveStream(token: String): String? {
        if (site(token) != "youtube" || id(token).isBlank()) return null
        return try {
            init()
            val info = StreamInfo.getInfo("https://www.youtube.com/watch?v=${id(token)}")
            // Muxed (video + audio) streams only: ExoPlayer plays them directly. Prefer up to 720p.
            val muxed = info.videoStreams.filter { !it.isVideoOnly && it.content.isNotBlank() }
            muxed.filter { it.height in 1..720 }.maxByOrNull { it.height }?.content
                ?: muxed.minByOrNull { it.height }?.content
                ?: info.hlsUrl?.takeIf { it.isNotBlank() }
        } catch (t: Throwable) {
            logError(t)
            null
        }
    }

    private fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    /** Shows the trailer in a dialog with the built-in player (play/pause and seek controls). */
    fun show(activity: FragmentActivity, title: String, token: String) {
        val ctx: Context = activity
        val dialog = Dialog(ctx, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val root = FrameLayout(ctx).apply { setBackgroundColor(Color.BLACK) }
        val playerView = PlayerView(ctx).apply {
            useController = true
            setShowNextButton(false)
            setShowPreviousButton(false)
            controllerShowTimeoutMs = 3500
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            visibility = View.INVISIBLE
        }
        root.addView(playerView, FrameLayout.LayoutParams(-1, -1))

        val spinner = ProgressBar(ctx)
        root.addView(spinner, FrameLayout.LayoutParams(dp(ctx, 48), dp(ctx, 48), Gravity.CENTER))

        val message = TextView(ctx).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(dp(ctx, 32), 0, dp(ctx, 32), 0)
            visibility = View.GONE
        }
        val openBtn = Button(ctx).apply {
            text = "Open in browser"
            isAllCaps = false
            visibility = View.GONE
            setOnClickListener {
                webUrl(token)?.let { u -> runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u))) } }
            }
        }
        val fallback = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(message)
            addView(openBtn, android.widget.LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(ctx, 16) })
        }
        root.addView(fallback, FrameLayout.LayoutParams(-1, -1))

        val titleView = TextView(ctx).apply {
            text = "Trailer · $title"
            setTextColor(Color.WHITE)
            textSize = 14f
            maxLines = 1
            setShadowLayer(6f, 0f, 0f, Color.BLACK)
        }
        root.addView(titleView, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
            marginStart = dp(ctx, 16); topMargin = dp(ctx, 20); marginEnd = dp(ctx, 72)
        })
        val closeText = TextView(ctx).apply {
            text = "✕"
            setTextColor(Color.WHITE)
            textSize = 24f
            gravity = Gravity.CENTER
            contentDescription = "Close trailer"
            isClickable = true
            isFocusable = true
            setOnClickListener { dialog.dismiss() }
        }
        root.addView(closeText, FrameLayout.LayoutParams(dp(ctx, 56), dp(ctx, 56), Gravity.TOP or Gravity.END).apply { topMargin = dp(ctx, 8) })

        dialog.setContentView(root, ViewGroup.LayoutParams(-1, -1))
        dialog.setCancelable(true)

        var player: ExoPlayer? = null
        fun fail(text: String) {
            spinner.visibility = View.GONE
            playerView.visibility = View.INVISIBLE
            message.text = text
            message.visibility = View.VISIBLE
            openBtn.visibility = if (webUrl(token) != null) View.VISIBLE else View.GONE
        }

        val observer = object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                player?.pause()
            }
        }
        activity.lifecycle.addObserver(observer)
        dialog.setOnDismissListener {
            activity.lifecycle.removeObserver(observer)
            player?.release()
            player = null
        }
        dialog.show()

        thread {
            val url = resolveStream(token)
            activity.runOnUiThread {
                if (!dialog.isShowing) return@runOnUiThread
                if (url == null) {
                    fail(
                        if (site(token) == "youtube") "This trailer can't be played inside the app right now."
                        else "This trailer is hosted on another site and can only be opened in the browser."
                    )
                    return@runOnUiThread
                }
                val http = DefaultHttpDataSource.Factory().setUserAgent(UA).setAllowCrossProtocolRedirects(true)
                val exo = ExoPlayer.Builder(ctx).setMediaSourceFactory(DefaultMediaSourceFactory(http)).build()
                player = exo
                playerView.player = exo
                exo.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_READY) {
                            spinner.visibility = View.GONE
                            playerView.visibility = View.VISIBLE
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        logError(error)
                        fail("The trailer could not be loaded. Check your connection and try again.")
                    }
                })
                exo.setMediaItem(MediaItem.fromUri(url))
                exo.prepare()
                exo.playWhenReady = true
                playerView.requestFocus()
            }
        }
    }
}
