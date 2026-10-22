package io.github.kaustubhowmick.clipjoiner.media

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Joins clips that share one format by copying their compressed packets into a new MP4,
 * shifting each clip's timestamps by the running duration. Nothing is decoded or re-encoded,
 * so it runs at storage speed and the output is about the size of the inputs combined —
 * the same thing `ffmpeg -f concat -c copy` does.
 */
object StreamJoiner {

    /**
     * @param audioDelayUs encoder priming at the start of this clip's audio (re-encoded clips);
     *   the audio is moved earlier by this much and the priming dropped.
     */
    class Part(val uri: Uri, val audioDelayUs: Long = 0)

    /**
     * @param referenceIndex the part whose track formats define the output (a clip that is
     *   copied untouched, so the output's codec headers match most clips).
     * @param rotation clockwise display rotation written to the output track.
     * @param withAudio whether the output has an audio track. Parts without audio then get
     *   silence in the same format.
     */
    fun join(
        context: Context,
        parts: List<Part>,
        referenceIndex: Int,
        rotation: Int,
        withAudio: Boolean,
        muxer: MediaMuxer,
        onProgress: (Float) -> Unit = {},
        checkCancelled: () -> Unit = {},
    ) {
        require(parts.isNotEmpty())
        val formats = parts.map { readFormats(context, it.uri) }
        val videoFormat = formats[referenceIndex].video
        val audioFormat = if (withAudio) {
            (listOf(formats[referenceIndex]) + formats).firstNotNullOfOrNull { it.audio }
                ?: throw IOException("No clip has audio")
        } else null

        val referenceCsd = csdOf(videoFormat)
        val maxInput = formats.maxOf { f ->
            maxOf(f.video.intOr(MediaFormat.KEY_MAX_INPUT_SIZE, 0), f.audio?.intOr(MediaFormat.KEY_MAX_INPUT_SIZE, 0) ?: 0)
        }
        var buffer = ByteBuffer.allocateDirect(maxOf(maxInput, 2 * 1024 * 1024) + formats.maxOf { csdOf(it.video).size })
        val info = MediaCodec.BufferInfo()

        // A converted reference clip carries rotation 0; the output always uses the target's.
        videoFormat.setInteger(MediaFormat.KEY_ROTATION, rotation)
        val videoTrack = muxer.addTrack(videoFormat)
        val audioTrack = if (audioFormat != null) muxer.addTrack(audioFormat) else -1
        muxer.setOrientationHint(rotation)
        muxer.start()

        val totalUs = formats.sumOf { it.durationUs }.coerceAtLeast(1)
        var offsetUs = 0L
        var lastAudioPtsUs = -1L

        for ((index, part) in parts.withIndex()) {
            val f = formats[index]
            val video = ExtractorSource(context, part.uri, f.videoIndex)
            val audio: SampleSource? = when {
                audioTrack < 0 -> null
                f.audioIndex >= 0 -> ExtractorSource(context, part.uri, f.audioIndex)
                else -> {
                    val af = audioFormat!!
                    // Match the clip's video length to the nearest audio frame, so neither
                    // track has a gap before the next clip.
                    val sampleRate = af.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    val frameUs = 1024L * 1_000_000 / sampleRate
                    val samples = SilentAudio.encode(
                        mime = af.getString(MediaFormat.KEY_MIME)!!,
                        sampleRate = sampleRate,
                        channels = af.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
                        bitrate = af.intOr(MediaFormat.KEY_BIT_RATE, 0),
                        durationUs = f.videoDurationUs + 3 * frameUs,
                    ).filter { it.ptsUs + frameUs / 2 < f.videoDurationUs }
                    ListSource(samples, startUs = video.timeUs.coerceAtLeast(0))
                }
            }
            try {
                // Parameter sets that differ from the output header (e.g. a clip converted on this
                // device, or a clip from another camera) go in-band in front of each of the clip's
                // keyframes, so the decoder switches to them at the boundary and after a seek.
                val partCsd = csdOf(f.video)
                val inBandCsd: ByteArray? = if (partCsd.isNotEmpty() && !partCsd.contentEquals(referenceCsd)) partCsd else null
                val audioDelayUs = if (f.audioIndex >= 0) part.audioDelayUs else 0L

                val startUs = minOf(video.timeUs.takeIf { it >= 0 } ?: Long.MAX_VALUE, audio?.timeUs?.takeIf { it >= 0 } ?: Long.MAX_VALUE)
                    .let { if (it == Long.MAX_VALUE) 0 else it }
                val videoStats = TrackStats()
                val audioStats = TrackStats()

                while (true) {
                    checkCancelled()
                    val vt = video.timeUs
                    val at = audio?.timeUs ?: -1
                    if (vt < 0 && at < 0) break
                    val takeVideo = at < 0 || (vt in 0..at)
                    val source = if (takeVideo) video else audio!!

                    val prefix = if (takeVideo && inBandCsd != null && source.flags() != 0) inBandCsd else null
                    val needed = source.sampleSize() + (prefix?.size ?: 0)
                    if (needed > buffer.capacity()) buffer = ByteBuffer.allocateDirect(needed + 1024 * 1024)
                    buffer.clear()

                    val size = source.read(buffer, prefix?.size ?: 0)
                    if (size < 0) {
                        source.advance()
                        continue
                    }
                    if (prefix != null) writeParameterSets(buffer, prefix)
                    val t = source.timeUs
                    val ptsUs = offsetUs + (t - startUs) - (if (takeVideo) 0 else audioDelayUs)
                    info.set(0, size + (prefix?.size ?: 0), ptsUs, source.flags())

                    if (takeVideo) {
                        videoStats.add(t)
                        muxer.writeSampleData(videoTrack, buffer, info)
                    } else {
                        // Muxers need rising audio timestamps; a clip whose audio starts early
                        // (encoder priming) could otherwise overlap the previous clip's tail.
                        audioStats.add(t - audioDelayUs)
                        if (ptsUs > lastAudioPtsUs && ptsUs >= offsetUs) {
                            muxer.writeSampleData(audioTrack, buffer, info)
                            lastAudioPtsUs = ptsUs
                        }
                    }
                    source.advance()
                    onProgress(((offsetUs + (t - startUs)).toFloat() / totalUs).coerceIn(0f, 1f))
                }
                val partEnd = maxOf(videoStats.endUs(), audioStats.endUs())
                offsetUs += (partEnd - startUs).coerceAtLeast(0)
            } finally {
                video.release()
                audio?.release()
            }
        }
        muxer.stop()
        onProgress(1f)
    }

    private class Formats(
        val video: MediaFormat,
        val videoIndex: Int,
        val audio: MediaFormat?,
        val audioIndex: Int,
        val videoDurationUs: Long,
        val durationUs: Long,
    )

    private fun readFormats(context: Context, uri: Uri): Formats {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(context, uri, null)
            var vi = -1
            var ai = -1
            for (i in 0 until ex.trackCount) {
                val mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                if (vi < 0 && mime.startsWith("video/")) vi = i
                if (ai < 0 && mime.startsWith("audio/")) ai = i
            }
            if (vi < 0) throw IOException("No video track in $uri")
            val v = ex.getTrackFormat(vi)
            val a = if (ai >= 0) ex.getTrackFormat(ai) else null
            val vd = v.longOr(MediaFormat.KEY_DURATION, 0)
            return Formats(v, vi, a, ai, vd, maxOf(vd, a?.longOr(MediaFormat.KEY_DURATION, 0) ?: 0))
        } finally {
            ex.release()
        }
    }

    /** csd-0 (+ csd-1) as Annex-B with 4-byte start codes. */
    private fun csdOf(format: MediaFormat): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for (key in listOf("csd-0", "csd-1")) {
            val b = format.getByteBuffer(key) ?: continue
            val bytes = ByteArray(b.remaining())
            b.duplicate().get(bytes)
            for (nal in splitAnnexB(bytes)) {
                out.write(byteArrayOf(0, 0, 0, 1))
                out.write(nal)
            }
        }
        return out.toByteArray()
    }

    /** Splits Annex-B data into NAL units (without start codes). */
    internal fun splitAnnexB(data: ByteArray): List<ByteArray> {
        val starts = mutableListOf<Pair<Int, Int>>() // (start code position, payload position)
        var i = 0
        while (i + 2 < data.size) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0) {
                if (data[i + 2].toInt() == 1) { starts += i to i + 3; i += 3; continue }
                if (i + 3 < data.size && data[i + 2].toInt() == 0 && data[i + 3].toInt() == 1) { starts += i to i + 4; i += 4; continue }
            }
            i++
        }
        if (starts.isEmpty()) return if (data.isEmpty()) emptyList() else listOf(data)
        return starts.mapIndexed { n, (_, payload) ->
            val end = if (n + 1 < starts.size) starts[n + 1].first else data.size
            data.copyOfRange(payload, end)
        }
    }

    /**
     * Writes [annexB] into the space reserved at the start of [buffer], in whichever framing the
     * sample that follows it uses (Annex-B start codes or 4-byte lengths; both are 4 bytes per NAL).
     */
    private fun writeParameterSets(buffer: ByteBuffer, annexB: ByteArray) {
        val p = annexB.size
        val sampleIsAnnexB = buffer.get(p).toInt() == 0 && buffer.get(p + 1).toInt() == 0 &&
            (buffer.get(p + 2).toInt() == 1 || (buffer.get(p + 2).toInt() == 0 && buffer.get(p + 3).toInt() == 1))
        if (sampleIsAnnexB) {
            for (i in annexB.indices) buffer.put(i, annexB[i])
        } else {
            var pos = 0
            for (nal in splitAnnexB(annexB)) {
                buffer.putInt(pos, nal.size)
                for (j in nal.indices) buffer.put(pos + 4 + j, nal[j])
                pos += 4 + nal.size
            }
        }
    }

    private class TrackStats {
        private var count = 0
        private var min = Long.MAX_VALUE
        private var max = Long.MIN_VALUE
        fun add(t: Long) { count++; if (t < min) min = t; if (t > max) max = t }
        /** Last timestamp plus one average sample duration. */
        fun endUs(): Long = when {
            count == 0 -> Long.MIN_VALUE
            count == 1 -> max
            else -> max + (max - min) / (count - 1)
        }
    }

    private interface SampleSource {
        /** Presentation time of the current sample, or -1 at the end. */
        val timeUs: Long
        fun sampleSize(): Int
        fun read(buffer: ByteBuffer, offset: Int): Int
        fun flags(): Int
        fun advance()
        fun release()
    }

    private class ExtractorSource(context: Context, uri: Uri, track: Int) : SampleSource {
        private val ex = MediaExtractor().apply {
            setDataSource(context, uri, null)
            selectTrack(track)
            seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        }
        override val timeUs: Long get() = ex.sampleTime
        override fun sampleSize(): Int = if (Build.VERSION.SDK_INT >= 28) ex.sampleSize.toInt() else 0
        override fun read(buffer: ByteBuffer, offset: Int): Int = ex.readSampleData(buffer, offset)
        override fun flags(): Int =
            if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
        override fun advance() { ex.advance() }
        override fun release() = ex.release()
    }

    private class ListSource(private val samples: List<EncodedSample>, private val startUs: Long) : SampleSource {
        private var i = 0
        override val timeUs: Long get() = if (i < samples.size) startUs + samples[i].ptsUs else -1
        override fun sampleSize(): Int = samples[i].data.size
        override fun read(buffer: ByteBuffer, offset: Int): Int {
            buffer.position(offset)
            buffer.put(samples[i].data)
            buffer.position(0)
            return samples[i].data.size
        }
        override fun flags(): Int = MediaCodec.BUFFER_FLAG_KEY_FRAME
        override fun advance() { i++ }
        override fun release() {}
    }
}
