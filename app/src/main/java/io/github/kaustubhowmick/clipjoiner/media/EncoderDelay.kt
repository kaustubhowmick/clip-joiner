package io.github.kaustubhowmick.clipjoiner.media

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * AAC encoders prepend "priming" samples (typically 2048), so a re-encoded clip's sound
 * starts a little late. Transformer doesn't mark that delay in the file, so it is measured
 * here once per format: a click is encoded and decoded, and its shift is the delay. The
 * joiner then moves the converted clip's audio earlier by that much, keeping it in sync.
 */
object EncoderDelay {
    private const val TAG = "EncoderDelay"
    private val cache = HashMap<String, Long>()

    /** Delay in microseconds that this device's default AAC encoder adds, or 0 if unknown. */
    @Synchronized
    fun aacDelayUs(sampleRate: Int, channels: Int): Long = cache.getOrPut("$sampleRate/$channels") {
        try {
            val samples = measureSamples(sampleRate, channels)
            Log.i(TAG, "AAC encoder delay at $sampleRate Hz x$channels: $samples samples")
            samples * 1_000_000L / sampleRate
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't measure encoder delay; assuming 2048 samples", e)
            2048L * 1_000_000L / sampleRate
        }
    }

    private const val LEAD = 8192

    private fun measureSamples(sampleRate: Int, channels: Int): Long {
        // Silence, a loud 1 kHz burst, silence.
        val total = LEAD + 2048 + 8192
        val pcm = ShortArray(total * channels)
        for (i in LEAD until LEAD + 2048) {
            val v = (sin(2 * PI * 1000 * (i - LEAD) / sampleRate) * 20000).toInt().toShort()
            for (c in 0 until channels) pcm[i * channels + c] = v
        }

        val mime = MediaFormat.MIMETYPE_AUDIO_AAC
        val encFormat = MediaFormat.createAudioFormat(mime, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        }
        val encoded = ArrayList<ByteArray>()
        var csd: ByteArray? = null
        val enc = MediaCodec.createEncoderByType(mime)
        try {
            enc.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            enc.start()
            runCodec(enc, inputFrames = { buf ->
                val pos = nextFrame
                if (pos >= total) null else {
                    val n = minOf(1024, total - pos, buf.remaining() / (2 * channels))
                    buf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(pcm, pos * channels, n * channels)
                    nextFrame += n
                    n * 2 * channels
                }
            }) { data, flags ->
                if (flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) csd = data else if (data.isNotEmpty()) encoded += data
            }
        } finally {
            nextFrame = 0
            enc.release()
        }

        val decFormat = MediaFormat.createAudioFormat(mime, sampleRate, channels).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(csd ?: error("no AAC config")))
        }
        val decoded = java.io.ByteArrayOutputStream()
        val dec = MediaCodec.createDecoderByType(mime)
        var packet = 0
        try {
            dec.configure(decFormat, null, null, 0)
            dec.start()
            runCodec(dec, inputFrames = { buf ->
                if (packet >= encoded.size) null else {
                    buf.put(encoded[packet++])
                    buf.position()
                }
            }) { data, _ -> decoded.write(data) }
        } finally {
            dec.release()
        }

        val out = ByteBuffer.wrap(decoded.toByteArray()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val frames = out.remaining() / channels
        for (i in 0 until frames) {
            if (abs(out.get(i * channels).toInt()) > 4000) {
                return (i - LEAD).toLong().coerceIn(0, 4096)
            }
        }
        error("burst not found")
    }

    private var nextFrame = 0

    /** Feeds [inputFrames] until it returns null (then EOS) and hands every output to [onOutput]. */
    private fun runCodec(codec: MediaCodec, inputFrames: (ByteBuffer) -> Int?, onOutput: (ByteArray, Int) -> Unit) {
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var pts = 0L
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            if (!inputDone) {
                val i = codec.dequeueInputBuffer(5_000)
                if (i >= 0) {
                    val buf = codec.getInputBuffer(i)!!
                    buf.clear()
                    val n = inputFrames(buf)
                    if (n == null) {
                        codec.queueInputBuffer(i, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(i, 0, n, pts, 0)
                        pts += 21_333
                    }
                }
            }
            val o = codec.dequeueOutputBuffer(info, 5_000)
            if (o >= 0) {
                val buf = codec.getOutputBuffer(o)!!
                buf.position(info.offset)
                buf.limit(info.offset + info.size)
                val data = ByteArray(info.size)
                buf.get(data)
                onOutput(data, info.flags)
                codec.releaseOutputBuffer(o, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
            }
        }
        error("codec timed out")
    }
}
