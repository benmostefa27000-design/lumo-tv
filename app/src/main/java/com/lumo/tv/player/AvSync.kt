package com.lumo.tv.player

import android.content.Context
import android.os.Handler
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener

/**
 * Synchro du son : décale l'image par rapport au son.
 * delayMs > 0 : le son est retardé (l'image passe plus tôt) ; < 0 : le son est avancé.
 */
object AvSync {
    @Volatile var delayMs: Int = 0
}

@OptIn(UnstableApi::class)
class OffsetVideoRenderer(
    context: Context,
    selector: MediaCodecSelector,
    joiningMs: Long,
    fallback: Boolean,
    handler: Handler?,
    listener: VideoRendererEventListener?
) : MediaCodecVideoRenderer(context, selector, joiningMs, fallback, handler, listener, 50) {
    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        super.render(positionUs + AvSync.delayMs * 1000L, elapsedRealtimeUs)
    }
}

@OptIn(UnstableApi::class)
class SyncRenderersFactory(context: Context) : DefaultRenderersFactory(context) {
    init { setEnableDecoderFallback(true) }

    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>
    ) {
        out.add(OffsetVideoRenderer(context, mediaCodecSelector, allowedVideoJoiningTimeMs, enableDecoderFallback, eventHandler, eventListener))
    }
}
