package io.github.kaustubhowmick.clipjoiner.media

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import java.io.IOException

/** One compressed audio frame, ready for the muxer. */
class EncodedSample(val data: ByteArray, val ptsUs: Long)

/**
 * Encodes silence in the output's audio format, so a clip without audio can sit between
 * clips with audio and the soundtrack stays continuous and in sync. Only audio is encoded;
 * the clip's video is still stream-copied.
 */
object SilentAudio {

    fun encode(mime: String, sampleRate: Int, channels: Int, bitrate: Int, durationUs: Long): List<EncodedSample> {
        val format = MediaFormat.createAudioFormat(mime, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, if (bitrate > 0) bitrate else 128_000)
            if (mime == MediaFormat.MIMETYPE_AUDIO_AAC) {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            }
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1024 * channels * 2 * 4)
        }
        val codec = try {
            MediaCodec.createEncoderByType(mime)
        } catch (e: Exception) {
            throw IOException("No $mime encoder for the silent track", e)
        }
        val out = ArrayList<EncodedSample>()
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val totalFrames = durationUs * sampleRate / 1_000_000
            var framesQueued = 0L
            var inputDone = false
            val info = MediaCodec.BufferInfo()
            while (true) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val buf = codec.getInputBuffer(inIndex)!!
                        val frames = minOf(1024L, totalFrames - framesQueued, (buf.capacity() / (2 * channels)).toLong()).toInt()
                        val pts = framesQueued * 1_000_000 / sampleRate
                        if (frames <= 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            buf.clear()
                            val bytes = frames * 2 * channels
                            for (i in 0 until bytes) buf.put(0)
                            codec.queueInputBuffer(inIndex, 0, bytes, pts, 0)
                            framesQueued += frames
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                if (outIndex >= 0) {
                    val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (!isConfig && info.size > 0) {
                        val buf = codec.getOutputBuffer(outIndex)!!
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        val bytes = ByteArray(info.size)
                        buf.get(bytes)
                        out += EncodedSample(bytes, info.presentationTimeUs)
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        } finally {
            try { codec.stop() } catch (_: Exception) {}
            codec.release()
        }
        // Encoders may shift timestamps for priming; lay the frames out evenly from zero
        // instead, so the silence lines up exactly with the clip's video.
        val samplesPerFrame = if (mime == MediaFormat.MIMETYPE_AUDIO_AAC) 1024L else 0L
        return if (samplesPerFrame > 0) {
            out.mapIndexed { i, s -> EncodedSample(s.data, i * samplesPerFrame * 1_000_000 / sampleRate) }
        } else {
            val first = out.firstOrNull()?.ptsUs ?: 0
            out.map { EncodedSample(it.data, it.ptsUs - first) }
        }
    }
}
