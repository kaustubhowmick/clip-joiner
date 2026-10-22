package io.github.kaustubhowmick.clipjoiner.media

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import java.io.File
import java.io.IOException

/** Reads the properties [MergeAnalysis] needs from a clip, without decoding anything. */
object ClipProbe {

    fun probe(context: Context, uri: Uri): ClipInfo {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            var videoIndex = -1
            var audioIndex = -1
            for (i in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                if (videoIndex < 0 && mime.startsWith("video/")) videoIndex = i
                if (audioIndex < 0 && mime.startsWith("audio/")) audioIndex = i
            }
            if (videoIndex < 0) throw IOException("No video track")

            val vf = extractor.getTrackFormat(videoIndex)
            val retrieverRotation = readRotation(context, uri)
            val rotation = when {
                retrieverRotation != null -> retrieverRotation
                vf.containsKey(MediaFormat.KEY_ROTATION) -> vf.getInteger(MediaFormat.KEY_ROTATION)
                else -> 0
            }

            // Walk the video sample table (no decoding) for frame count, span and byte size.
            extractor.selectTrack(videoIndex)
            var frames = 0
            var minPts = Long.MAX_VALUE
            var maxPts = Long.MIN_VALUE
            var bytes = 0L
            while (true) {
                val t = extractor.sampleTime
                if (t < 0) break
                frames++
                if (t < minPts) minPts = t
                if (t > maxPts) maxPts = t
                if (Build.VERSION.SDK_INT >= 28) bytes += extractor.sampleSize
                if (!extractor.advance()) break
            }
            val spanUs = if (frames > 1) maxPts - minPts else 0L
            val frameRate = when {
                spanUs > 0 -> (frames - 1) * 1_000_000f / spanUs
                vf.containsKey(MediaFormat.KEY_FRAME_RATE) -> vf.getNumber(MediaFormat.KEY_FRAME_RATE)
                else -> 0f
            }
            val videoDurationUs = vf.longOr(MediaFormat.KEY_DURATION, if (frameRate > 0) spanUs + (1_000_000 / frameRate).toLong() else spanUs)
            val videoBitrate = when {
                bytes > 0 && videoDurationUs > 0 -> (bytes * 8 * 1_000_000 / videoDurationUs).toInt()
                vf.containsKey(MediaFormat.KEY_BIT_RATE) -> vf.getInteger(MediaFormat.KEY_BIT_RATE)
                else -> 0
            }

            val audio = if (audioIndex >= 0) {
                val af = extractor.getTrackFormat(audioIndex)
                AudioSpec(
                    mime = af.getString(MediaFormat.KEY_MIME)!!,
                    sampleRate = af.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                    channels = af.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
                    bitrate = af.intOr(MediaFormat.KEY_BIT_RATE, 0),
                )
            } else null
            val audioDurationUs = if (audioIndex >= 0) extractor.getTrackFormat(audioIndex).longOr(MediaFormat.KEY_DURATION, 0) else 0

            return ClipInfo(
                uri = uri.toString(),
                name = displayName(context, uri),
                durationUs = maxOf(videoDurationUs, audioDurationUs),
                sizeBytes = sizeOf(context, uri),
                video = VideoSpec(
                    mime = vf.getString(MediaFormat.KEY_MIME)!!,
                    width = vf.getInteger(MediaFormat.KEY_WIDTH),
                    height = vf.getInteger(MediaFormat.KEY_HEIGHT),
                    rotation = ((rotation % 360) + 360) % 360,
                    frameRate = frameRate,
                    bitrate = videoBitrate,
                ),
                audio = audio,
            )
        } finally {
            extractor.release()
        }
    }

    private fun readRotation(context: Context, uri: Uri): Int? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, uri)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull()
        } catch (_: RuntimeException) {
            null
        } finally {
            r.release()
        }
    }

    private fun displayName(context: Context, uri: Uri): String {
        if (uri.scheme == "file") return uri.lastPathSegment ?: "clip"
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else null
            }
        } catch (_: RuntimeException) {
            null
        } ?: uri.lastPathSegment ?: "clip"
    }

    private fun sizeOf(context: Context, uri: Uri): Long {
        if (uri.scheme == "file") return File(uri.path!!).length()
        return try {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }?.takeIf { it > 0 }
                ?: context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
                    if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else 0L
                } ?: 0L
        } catch (_: Exception) {
            0L
        }
    }
}

internal fun MediaFormat.longOr(key: String, default: Long): Long =
    if (containsKey(key)) getLong(key) else default

internal fun MediaFormat.intOr(key: String, default: Int): Int =
    if (containsKey(key)) getInteger(key) else default

private fun MediaFormat.getNumber(key: String): Float =
    try { getInteger(key).toFloat() } catch (_: ClassCastException) { getFloat(key) }
