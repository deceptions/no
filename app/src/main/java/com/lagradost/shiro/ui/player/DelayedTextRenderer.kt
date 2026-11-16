package com.lagradost.shiro.ui.player

import android.content.Context
import android.os.Looper
import com.google.android.exoplayer2.DefaultRenderersFactory
import com.google.android.exoplayer2.Format
import com.google.android.exoplayer2.Renderer
import com.google.android.exoplayer2.RendererCapabilities
import com.google.android.exoplayer2.RendererConfiguration
import com.google.android.exoplayer2.mediacodec.MediaCodecSelector
import com.google.android.exoplayer2.source.SampleStream
import com.google.android.exoplayer2.text.SubtitleDecoderFactory
import com.google.android.exoplayer2.text.TextOutput
import com.google.android.exoplayer2.text.TextRenderer
import com.google.android.exoplayer2.util.MediaClock

/**
 * Subtitle-delay support. [TextRenderer] is final so it can't be subclassed — this wraps one and
 * forwards the whole [Renderer] contract, but calls `render()` with `positionUs - offsetUs`, so
 * every cue lands `offsetUs` later (positive = subtitles delayed, negative = subtitles ahead).
 */
class DelayedTextRenderer(output: TextOutput, outputLooper: Looper, decoderFactory: SubtitleDecoderFactory) : Renderer {

    private val inner = TextRenderer(output, outputLooper, decoderFactory)

    @Volatile
    var offsetUs: Long = 0L

    override fun getName() = inner.name
    override fun getTrackType() = inner.trackType
    override fun getStream() = inner.stream
    override fun reset() = inner.reset()
    override fun getState() = inner.state
    override fun getReadingPositionUs() = inner.readingPositionUs
    override fun init(index: Int, playerId: com.google.android.exoplayer2.analytics.PlayerId) = inner.init(index, playerId)
    override fun getCapabilities(): RendererCapabilities = inner.capabilities
    override fun getMediaClock(): MediaClock? = inner.mediaClock
    override fun hasReadStreamToEnd() = inner.hasReadStreamToEnd()
    override fun setCurrentStreamFinal() = inner.setCurrentStreamFinal()
    override fun isCurrentStreamFinal() = inner.isCurrentStreamFinal
    override fun maybeThrowStreamError() = inner.maybeThrowStreamError()
    override fun resetPosition(positionUs: Long) = inner.resetPosition(positionUs)
    override fun start() = inner.start()
    override fun replaceStream(formats: Array<Format>, stream: SampleStream, startPositionUs: Long, offsetUs: Long) =
        inner.replaceStream(formats, stream, startPositionUs, offsetUs)
    override fun render(positionUs: Long, elapsedRealtimeUs: Long) =
        inner.render(positionUs - offsetUs, elapsedRealtimeUs)
    override fun disable() = inner.disable()
    override fun stop() = inner.stop()
    override fun isReady() = inner.isReady
    override fun isEnded() = inner.isEnded
    override fun setPlaybackSpeed(currentPlaybackSpeed: Float, targetPlaybackSpeed: Float) =
        inner.setPlaybackSpeed(currentPlaybackSpeed, targetPlaybackSpeed)
    override fun enable(
        configuration: RendererConfiguration, formats: Array<Format>, stream: SampleStream,
        positionUs: Long, joining: Boolean, mayRenderStartOfStream: Boolean,
        startPositionUs: Long, offsetUs: Long
    ) = inner.enable(configuration, formats, stream, positionUs, joining, mayRenderStartOfStream, startPositionUs, offsetUs)
    override fun handleMessage(messageType: Int, payload: Any?) = inner.handleMessage(messageType, payload)
}

/**
 * Audio-delay support: wraps the real [com.google.android.exoplayer2.audio.MediaCodecAudioRenderer]
 * and calls `render()` with `positionUs - offsetUs`, so the audio timeline lands `offsetUs` later
 * (positive = audio delayed, negative = audio ahead) — same semantics as [DelayedTextRenderer].
 */
class DelayedAudioRenderer(private val inner: Renderer) : Renderer {

    @Volatile
    var offsetUs: Long = 0L

    override fun getName() = inner.name
    override fun getTrackType() = inner.trackType
    override fun getStream() = inner.stream
    override fun reset() = inner.reset()
    override fun getState() = inner.state
    override fun getReadingPositionUs() = inner.readingPositionUs
    override fun init(index: Int, playerId: com.google.android.exoplayer2.analytics.PlayerId) = inner.init(index, playerId)
    override fun getCapabilities(): RendererCapabilities = inner.capabilities
    override fun getMediaClock(): MediaClock? = inner.mediaClock
    override fun hasReadStreamToEnd() = inner.hasReadStreamToEnd()
    override fun setCurrentStreamFinal() = inner.setCurrentStreamFinal()
    override fun isCurrentStreamFinal() = inner.isCurrentStreamFinal
    override fun maybeThrowStreamError() = inner.maybeThrowStreamError()
    override fun resetPosition(positionUs: Long) = inner.resetPosition(positionUs)
    override fun start() = inner.start()
    override fun replaceStream(formats: Array<Format>, stream: SampleStream, startPositionUs: Long, offsetUs: Long) =
        inner.replaceStream(formats, stream, startPositionUs, offsetUs)
    override fun render(positionUs: Long, elapsedRealtimeUs: Long) =
        inner.render(positionUs - offsetUs, elapsedRealtimeUs)
    override fun disable() = inner.disable()
    override fun stop() = inner.stop()
    override fun isReady() = inner.isReady
    override fun isEnded() = inner.isEnded
    override fun setPlaybackSpeed(currentPlaybackSpeed: Float, targetPlaybackSpeed: Float) =
        inner.setPlaybackSpeed(currentPlaybackSpeed, targetPlaybackSpeed)
    override fun enable(
        configuration: RendererConfiguration, formats: Array<Format>, stream: SampleStream,
        positionUs: Long, joining: Boolean, mayRenderStartOfStream: Boolean,
        startPositionUs: Long, offsetUs: Long
    ) = inner.enable(configuration, formats, stream, positionUs, joining, mayRenderStartOfStream, startPositionUs, offsetUs)
    override fun handleMessage(messageType: Int, payload: Any?) = inner.handleMessage(messageType, payload)
}

/**
 * DefaultRenderersFactory that swaps the stock TextRenderer for [DelayedTextRenderer] and keeps the
 * latest instance in [current] so the fragment can update the delay while playing.
 */
class DelayedRenderersFactory(context: Context) : DefaultRenderersFactory(context) {
    companion object {
        @Volatile
        var current: DelayedTextRenderer? = null

        @Volatile
        var currentAudio: DelayedAudioRenderer? = null
    }

    override fun buildTextRenderers(
        context: Context,
        output: TextOutput,
        outputLooper: Looper,
        extensionRendererMode: Int,
        out: ArrayList<Renderer>
    ) {
        val renderer = DelayedTextRenderer(output, outputLooper, SubtitleDecoderFactory.DEFAULT)
        current = renderer
        out.add(renderer)
    }

    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: com.google.android.exoplayer2.audio.AudioSink,
        eventHandler: android.os.Handler,
        eventListener: com.google.android.exoplayer2.audio.AudioRendererEventListener,
        out: ArrayList<Renderer>
    ) {
        super.buildAudioRenderers(
            context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback,
            audioSink, eventHandler, eventListener, out
        )
        // Wrap the audio renderer the stock factory added so render() can shift its clock.
        for (i in out.indices) {
            if (out[i].trackType == com.google.android.exoplayer2.C.TRACK_TYPE_AUDIO &&
                out[i] !is DelayedAudioRenderer
            ) {
                out[i] = DelayedAudioRenderer(out[i]).also { currentAudio = it }
            }
        }
    }
}
