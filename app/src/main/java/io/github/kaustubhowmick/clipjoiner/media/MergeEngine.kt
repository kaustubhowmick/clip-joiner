package io.github.kaustubhowmick.clipjoiner.media

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File
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
                // The device encoded something other than the target (e.g. no HEVC encoder),
                // so convert every clip to what it can make; the output track stays uniform.
                Log.w(TAG, "Converted clip came out as ${e.produced}; converting all clips", e)
                workDir.listFiles()?.forEach { it.delete() }
                activePlan = plan.convertAllTo(e.produced)
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
                        parts = parts.map { StreamJoiner.Part(it) },
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

    /** Returns the URI to join for every clip: the original, or a converted copy in [workDir]. */
    private suspend fun prepare(
        clips: List<ClipInfo>,
        plan: MergePlan,
        workDir: File,
        onProgress: (MergeProgress) -> Unit,
    ): List<Uri> {
        val toConvert = plan.steps.withIndex().filter { it.value.action != ClipAction.COPY }.map { it.index }
        val totalUs = toConvert.sumOf { clips[it].durationUs.coerceAtLeast(1) }.toFloat()
        var doneUs = 0L
        return clips.mapIndexed { i, clip ->
            val step = plan.steps[i]
            if (step.action == ClipAction.COPY) return@mapIndexed Uri.parse(clip.uri)
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
            val produced = ClipProbe.probe(context, Uri.fromFile(out)).video
            val t = plan.targetVideo
            if (step.action == ClipAction.CONVERT &&
                (produced.mime != t.mime || produced.width != t.width || produced.height != t.height)
            ) {
                throw UnexpectedFormat(produced)
            }
            Uri.fromFile(out)
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
