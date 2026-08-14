package com.lumora.player.playback

import android.content.Context
import android.os.Handler
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener
import java.nio.ByteBuffer

/**
 * Shifts the video renderer's presentation timestamps by [offsetUsProvider] instead of the
 * audio's, because ExoPlayer's A/V sync clock is driven by the audio track's actual playback
 * position - delaying audio going *into* the sink just delays the clock along with it, and the
 * two stay in lockstep with no visible effect. Nudging what the video renderer treats as "now"
 * relative to that clock is what actually separates them. Positive offsetUs plays video that
 * much later, which is video "waiting" for audio, i.e. audio arrives earlier (advanced) by
 * comparison - matching [AvOffsetManager]'s "negative advances audio" convention.
 */
private class OffsetVideoRenderer(
    context: Context,
    codecAdapterFactory: MediaCodecAdapter.Factory,
    mediaCodecSelector: MediaCodecSelector,
    allowedJoiningTimeMs: Long,
    enableDecoderFallback: Boolean,
    eventHandler: Handler?,
    eventListener: VideoRendererEventListener?,
    maxDroppedFramesToNotify: Int,
    private val offsetUsProvider: () -> Long,
) : MediaCodecVideoRenderer(
    context,
    codecAdapterFactory,
    mediaCodecSelector,
    allowedJoiningTimeMs,
    enableDecoderFallback,
    eventHandler,
    eventListener,
    maxDroppedFramesToNotify,
) {
    @Throws(ExoPlaybackException::class)
    override fun processOutputBuffer(
        positionUs: Long,
        elapsedRealtimeUs: Long,
        codec: MediaCodecAdapter?,
        buffer: ByteBuffer?,
        bufferIndex: Int,
        bufferFlags: Int,
        sampleCount: Int,
        bufferPresentationTimeUs: Long,
        isDecodeOnlyBuffer: Boolean,
        isLastBuffer: Boolean,
        format: androidx.media3.common.Format,
    ): Boolean {
        // Video "later" makes video wait, so audio is comparatively earlier - offsetUs is
        // subtracted so a *negative* offset (advance audio, per AvOffsetManager) adds to the
        // video timestamp instead, and a positive offset (delay audio) subtracts from it.
        return super.processOutputBuffer(
            positionUs,
            elapsedRealtimeUs,
            codec,
            buffer,
            bufferIndex,
            bufferFlags,
            sampleCount,
            bufferPresentationTimeUs - offsetUsProvider(),
            isDecodeOnlyBuffer,
            isLastBuffer,
            format,
        )
    }
}

/** Default renderer set, except the video renderer applies a live-adjustable A/V offset
 *  (see [OffsetVideoRenderer]). [offsetUsProvider] is read on every output frame, so the
 *  offset takes effect immediately - no player rebuild needed when the user changes it. */
class AvOffsetRenderersFactory(
    context: Context,
    private val offsetUsProvider: () -> Long,
) : DefaultRenderersFactory(context) {
    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>,
    ) {
        out.add(
            OffsetVideoRenderer(
                context,
                getCodecAdapterFactory(),
                mediaCodecSelector,
                allowedVideoJoiningTimeMs,
                enableDecoderFallback,
                eventHandler,
                eventListener,
                MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY,
                offsetUsProvider,
            )
        )
    }
}
