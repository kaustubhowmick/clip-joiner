package io.github.kaustubhowmick.clipjoiner.media

import android.content.Context
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

sealed interface MergeProgress {
    /** Converting clip [index] (1-based) of [count]; [fraction] covers all conversions. */
    data class Converting(val index: Int, val count: Int, val fraction: Float) : MergeProgress
    /** Copying packets into the output; [fraction] is null when it's too quick to matter. */
    data class Joining(val fraction: Float?) : MergeProgress
}

/** Runs a [MergePlan]: convert the clips that need it, stream-copy everything, save. */
class MergeEngine(context: Context) {
    private val context = context.applicationContext

    suspend fun merge(
        clips: List<ClipInfo>,
        plan: MergePlan,
        displayName: String = defaultName(),
        onProgress: (MergeProgress) -> Unit = {},
    ): SavedVideo = withContext(Dispatchers.IO) {
        val workDir = File(context.cacheDir, "work/${System.nanoTime()}").apply { mkdirs() }
        try {
            var activePlan = plan
            val parts = try {
                prepare(clips, activePlan, workDir, onProgress)
            } catch (e: UnexpectedFormat) {
                // The device couldn't encode the target (e.g. its HEVC encoder doesn't go up to
                // this size), so convert every clip to H.264 at the target size, which every
                // device can encode; the output track stays uniform.
                Log.w(TAG, "Converted clip came out as ${e.produced}; converting all clips to H.264", e)
                workDir.listFiles()?.forEach { it.delete() }
                activePlan = plan.convertAllTo(plan.targetVideo.copy(mime = MediaFormat.MIMETYPE_VIDEO_AVC))
                prepare(clips, activePlan, workDir, onProgress)
            }

            val showJoinProgress = activePlan.convertCount > 0
            onProgress(MergeProgress.Joining(if (showJoinProgress) 0f else null))
            val reference = activePlan.steps.indexOfFirst { it.action == ClipAction.COPY }.coerceAtLeast(0)
            val output = OutputStore.create(context, displayName)
            try {
                val muxer = output.openMuxer()
                try {
                    StreamJoiner.join(
                        context = context,
                        parts = parts,
                        referenceIndex = reference,
                        rotation = activePlan.targetVideo.rotation,
                        withAudio = activePlan.targetAudio != null,
                        muxer = muxer,
                        onProgress = { if (showJoinProgress) onProgress(MergeProgress.Joining(it)) },
                        checkCancelled = { coroutineContext.ensureActive() },
                    )
                } finally {
                    muxer.release()
                }
                output.commit()
            } catch (t: Throwable) {
                output.discard()
                throw t
            }
        } finally {
            workDir.deleteRecursively()
        }
    }

    private class UnexpectedFormat(val produced: VideoSpec) : Exception("Converted clip is $produced")

    /** Returns what to join for every clip: the original, or a converted copy in [workDir]. */
    private suspend fun prepare(
        clips: List<ClipInfo>,
        plan: MergePlan,
        workDir: File,
        onProgress: (MergeProgress) -> Unit,
    ): List<StreamJoiner.Part> {
        val toConvert = plan.steps.withIndex().filter { it.value.action != ClipAction.COPY }.map { it.index }
        val totalUs = toConvert.sumOf { clips[it].durationUs.coerceAtLeast(1) }.toFloat()
        var doneUs = 0L
        return clips.mapIndexed { i, clip ->
            val step = plan.steps[i]
            if (step.action == ClipAction.COPY) return@mapIndexed StreamJoiner.Part(Uri.parse(clip.uri))
            val n = toConvert.indexOf(i)
            val out = File(workDir, "clip$i.mp4")
            ClipConverter.convert(
                context = context,
                clip = clip,
                targetVideo = plan.targetVideo,
                targetAudio = plan.targetAudio,
                videoToo = step.action == ClipAction.CONVERT,
                output = out,
            ) { f ->
                onProgress(MergeProgress.Converting(n + 1, toConvert.size, (doneUs + f * clip.durationUs) / totalUs))
            }
            doneUs += clip.durationUs
            val producedInfo = ClipProbe.probe(context, Uri.fromFile(out))
            val produced = producedInfo.video
            val t = plan.targetVideo
            if (step.action == ClipAction.CONVERT &&
                (produced.mime != t.mime || produced.width != t.width || produced.height != t.height)
            ) {
                if (t.mime == MediaFormat.MIMETYPE_VIDEO_AVC && produced.mime == t.mime) {
                    throw IOException("This device can't encode ${t.width}×${t.height} video")
                }
                throw UnexpectedFormat(produced)
            }
            val a = producedInfo.audio
            val delayUs = if (a != null && a.mime == MediaFormat.MIMETYPE_AUDIO_AAC) EncoderDelay.aacDelayUs(a.sampleRate, a.channels) else 0L
            StreamJoiner.Part(Uri.fromFile(out), audioDelayUs = delayUs)
        }
    }

    companion object {
        private const val TAG = "MergeEngine"

        fun defaultName(): String =
            "ClipJoiner_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"

        /** Removes intermediates left behind if the process died mid-merge. */
        fun cleanStaleWork(context: Context) {
            File(context.cacheDir, "work").deleteRecursively()
        }
    }
}
