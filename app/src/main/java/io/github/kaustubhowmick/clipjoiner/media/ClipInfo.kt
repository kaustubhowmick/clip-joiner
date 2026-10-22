package io.github.kaustubhowmick.clipjoiner.media

import kotlin.math.abs
import kotlin.math.max

enum class Orientation { PORTRAIT, LANDSCAPE }

/**
 * Video track properties that decide whether two clips can be joined by stream copy.
 * [width]/[height] are the coded (stored) size; [rotation] is the clockwise display
 * rotation from the container, so a 1920×1080 clip with rotation 90 is portrait.
 */
data class VideoSpec(
    val mime: String,
    val width: Int,
    val height: Int,
    val rotation: Int,
    val frameRate: Float,
    val bitrate: Int,
) {
    val displayWidth: Int get() = if (rotation % 180 != 0) height else width
    val displayHeight: Int get() = if (rotation % 180 != 0) width else height
    val orientation: Orientation
        get() = if (displayHeight > displayWidth) Orientation.PORTRAIT else Orientation.LANDSCAPE

    /** Same codec, coded size and rotation, and (nearly) the same frame rate. */
    fun matches(other: VideoSpec): Boolean =
        mime == other.mime && width == other.width && height == other.height &&
            rotation == other.rotation && frameRatesMatch(frameRate, other.frameRate)

    companion object {
        /**
         * Phones record variable frame rate, so a measured rate wobbles (29.7 vs 30.0).
         * Rates within 5% (at least 1 fps) count as the same.
         */
        fun frameRatesMatch(a: Float, b: Float): Boolean {
            if (a <= 0f || b <= 0f) return true // unknown: don't force a conversion over it
            return abs(a - b) <= max(1f, 0.05f * max(a, b))
        }
    }
}

data class AudioSpec(
    val mime: String,
    val sampleRate: Int,
    val channels: Int,
    val bitrate: Int,
) {
    fun matches(other: AudioSpec): Boolean =
        mime == other.mime && sampleRate == other.sampleRate && channels == other.channels
}

data class ClipInfo(
    /** Content or file URI as a string, so this model stays free of Android types. */
    val uri: String,
    val name: String,
    val durationUs: Long,
    val sizeBytes: Long,
    val video: VideoSpec,
    /** Null when the clip has no audio track. */
    val audio: AudioSpec?,
)
