package io.github.kaustubhowmick.clipjoiner.media

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.FrameDropEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.transformer.AudioEncoderSettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Re-encodes one mismatched clip to the target format with Media3 Transformer (hardware
 * encoder when the device has one). The picture is scaled to fit the target frame and centred
 * on black — never stretched or cropped.
 */
@OptIn(UnstableApi::class)
object ClipConverter {

    /**
     * @param videoToo false when only the audio differs: the video is then passed through.
     */
    suspend fun convert(
        context: Context,
        clip: ClipInfo,
        targetVideo: VideoSpec,
        targetAudio: AudioSpec?,
        videoToo: Boolean,
        output: File,
        onProgress: (Float) -> Unit,
    ): Unit = withContext(Dispatchers.Main) {
        val videoEffects = mutableListOf<Effect>()
        if (videoToo) {
            // Transformer hands effects an upright (display-oriented) frame. Fit it into the
            // target's display size, then turn it to the target's stored orientation; the
            // joined track's rotation flag turns it back on playback.
            videoEffects += Presentation.createForWidthAndHeight(
                targetVideo.displayWidth, targetVideo.displayHeight, Presentation.LAYOUT_SCALE_TO_FIT,
            )
            if (targetVideo.rotation != 0) {
                // Display rotation is clockwise; undo it counter-clockwise.
                videoEffects += ScaleAndRotateTransformation.Builder().setRotationDegrees(targetVideo.rotation.toFloat()).build()
            }
            if (targetVideo.frameRate > 0 && clip.video.frameRate > targetVideo.frameRate * 1.1f) {
                videoEffects += FrameDropEffect.createDefaultFrameDropEffect(targetVideo.frameRate)
            }
        }
        val audioProcessors = mutableListOf<AudioProcessor>()
        val srcAudio = clip.audio
        if (srcAudio != null && targetAudio != null) {
            if (srcAudio.sampleRate != targetAudio.sampleRate) {
                audioProcessors += SonicAudioProcessor().apply { setOutputSampleRateHz(targetAudio.sampleRate) }
            }
            if (srcAudio.channels != targetAudio.channels && srcAudio.channels <= 2 && targetAudio.channels <= 2) {
                audioProcessors += ChannelMixingAudioProcessor().apply {
                    putChannelMixingMatrix(ChannelMixingMatrix.createForConstantGain(srcAudio.channels, targetAudio.channels))
                }
            }
        }

        val edited = EditedMediaItem.Builder(MediaItem.fromUri(Uri.parse(clip.uri)))
            .setRemoveAudio(targetAudio == null)
            .setEffects(Effects(audioProcessors, videoEffects))
            .build()

        val encoderFactory = DefaultEncoderFactory.Builder(context.applicationContext)
            .setRequestedVideoEncoderSettings(
                VideoEncoderSettings.Builder().apply {
                    // Match the bitrate of the clips being copied, so a converted clip is about
                    // as big per second as its neighbours. Bars are flat black and cost little.
                    if (targetVideo.bitrate > 0) setBitrate(targetVideo.bitrate)
                }.build(),
            )
            .setRequestedAudioEncoderSettings(
                AudioEncoderSettings.Builder().apply {
                    if (targetAudio != null && targetAudio.bitrate > 0) setBitrate(targetAudio.bitrate)
                }.build(),
            )
            .setEnableFallback(true)
            .build()

        val transformer = Transformer.Builder(context.applicationContext)
            .setVideoMimeType(targetVideo.mime)
            .setAudioMimeType(targetAudio?.mime ?: MimeTypes.AUDIO_AAC)
            // Effects already produce the stored orientation; don't let Transformer rotate again.
            .setPortraitEncodingEnabled(true)
            .setEncoderFactory(encoderFactory)
            .build()

        val poller = launch {
            val holder = ProgressHolder()
            while (true) {
                if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                    onProgress(holder.progress / 100f)
                }
                delay(200)
            }
        }
        try {
            suspendCancellableCoroutine<Unit> { cont ->
                transformer.addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        if (cont.isActive) cont.resume(Unit)
                    }

                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        if (cont.isActive) cont.resumeWithException(exportException)
                    }
                })
                transformer.start(edited, output.absolutePath)
                cont.invokeOnCancellation {
                    // May run off the main thread; Transformer must be called on its looper.
                    Handler(Looper.getMainLooper()).post { transformer.cancel() }
                }
            }
        } finally {
            poller.cancel()
        }
        onProgress(1f)
    }
}

