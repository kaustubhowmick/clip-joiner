package io.github.kaustubhowmick.clipjoiner.media

/** What happens to one clip before the stream-copy join. */
enum class ClipAction {
    /** Packets copied untouched. */
    COPY,
    /** Video re-encoded to the target (fit + black bars), audio re-encoded to the target. */
    CONVERT,
    /** Video already matches and is passed through; only the audio is re-encoded. */
    CONVERT_AUDIO,
}

data class ClipStep(
    val action: ClipAction,
    /** The clip has no audio, so the joiner writes a matching silent track for it. */
    val addSilence: Boolean,
)

data class MergePlan(
    val targetVideo: VideoSpec,
    /** Null when no clip has audio; the output then has no audio track either. */
    val targetAudio: AudioSpec?,
    val steps: List<ClipStep>,
) {
    val convertCount: Int get() = steps.count { it.action != ClipAction.COPY }
    val silenceCount: Int get() = steps.count { it.addSilence }

    /**
     * Used when the device could not encode [targetVideo] (e.g. no HEVC encoder) and produced
     * [produced] instead: every clip is then converted to what the device can make, so the
     * single output track stays uniform.
     */
    fun convertAllTo(produced: VideoSpec): MergePlan = copy(
        targetVideo = produced.copy(rotation = targetVideo.rotation, frameRate = targetVideo.frameRate, bitrate = targetVideo.bitrate),
        steps = steps.map { it.copy(action = ClipAction.CONVERT) },
    )
}

/**
 * Decides how to merge a list of probed clips. Pure Kotlin so it is unit tested on the JVM.
 */
class MergeAnalysis(val clips: List<ClipInfo>) {

    init {
        require(clips.isNotEmpty())
    }

    private val portraitCount = clips.count { it.video.orientation == Orientation.PORTRAIT }
    private val landscapeCount = clips.size - portraitCount

    /** Every clip can be stream-copied (missing audio is filled with silence, not converted). */
    val allCompatible: Boolean = planFor(clips.first().video.orientation).convertCount == 0 &&
        clips.all { it.video.matches(clips.first().video) }

    /** Clips need converting and both orientations are present, so the user picks one. */
    val needsOrientationChoice: Boolean = !allCompatible && portraitCount > 0 && landscapeCount > 0

    /** The orientation most clips already have, or null on a tie (1 vs 1, 2 vs 2, ...). */
    val majorityOrientation: Orientation? = when {
        portraitCount > landscapeCount -> Orientation.PORTRAIT
        landscapeCount > portraitCount -> Orientation.LANDSCAPE
        else -> null
    }

    fun count(orientation: Orientation): Int =
        if (orientation == Orientation.PORTRAIT) portraitCount else landscapeCount

    /** The plan to use when no orientation choice is needed. */
    fun defaultPlan(): MergePlan {
        check(!needsOrientationChoice)
        return planFor(clips.first().video.orientation)
    }

    /**
     * Plan for the given output orientation. The target is the most common format among the
     * clips that already have that orientation, so those clips are copied untouched; ties go
     * to the larger frame, then to the earlier clip.
     */
    fun planFor(orientation: Orientation): MergePlan {
        val candidates = clips.filter { it.video.orientation == orientation }
        require(candidates.isNotEmpty()) { "No clip is $orientation" }

        val videoGroups = group(candidates) { a, b -> a.video.matches(b.video) }
        val best = videoGroups.maxWith(
            compareBy<List<ClipInfo>> { it.size }
                .thenBy { it.first().video.width.toLong() * it.first().video.height }
                .thenByDescending { clips.indexOf(it.first()) },
        )
        val bitrates = best.map { it.video.bitrate }.filter { it > 0 }.sorted()
        val targetVideo = best.first().video.copy(bitrate = if (bitrates.isEmpty()) 0 else bitrates[bitrates.size / 2])

        // Prefer the audio of the clips that will be copied, so they really stay untouched.
        val audioPool = best.filter { it.audio != null }.ifEmpty { clips.filter { it.audio != null } }
        val targetAudio = if (audioPool.isEmpty()) {
            null
        } else {
            group(audioPool) { a, b -> a.audio!!.matches(b.audio!!) }
                .maxWith(compareBy<List<ClipInfo>> { it.size }.thenByDescending { clips.indexOf(it.first()) })
                .first().audio
        }

        val steps = clips.map { clip ->
            val action = when {
                !clip.video.matches(targetVideo) -> ClipAction.CONVERT
                clip.audio != null && targetAudio != null && !clip.audio.matches(targetAudio) -> ClipAction.CONVERT_AUDIO
                else -> ClipAction.COPY
            }
            ClipStep(action, addSilence = clip.audio == null && targetAudio != null)
        }
        return MergePlan(targetVideo, targetAudio, steps)
    }

    private fun group(items: List<ClipInfo>, same: (ClipInfo, ClipInfo) -> Boolean): List<List<ClipInfo>> {
        val groups = mutableListOf<MutableList<ClipInfo>>()
        for (item in items) {
            groups.firstOrNull { same(it.first(), item) }?.add(item) ?: groups.add(mutableListOf(item))
        }
        return groups
    }
}
