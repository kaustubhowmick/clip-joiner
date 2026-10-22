package io.github.kaustubhowmick.clipjoiner

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.kaustubhowmick.clipjoiner.media.ClipAction
import io.github.kaustubhowmick.clipjoiner.media.ClipInfo
import io.github.kaustubhowmick.clipjoiner.media.ClipProbe
import io.github.kaustubhowmick.clipjoiner.media.MergeAnalysis
import io.github.kaustubhowmick.clipjoiner.media.MergeEngine
import io.github.kaustubhowmick.clipjoiner.media.MergePlan
import io.github.kaustubhowmick.clipjoiner.media.Orientation
import io.github.kaustubhowmick.clipjoiner.media.SavedVideo
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs the real merge engine (probe → plan → Transformer conversion → MediaMuxer stream copy →
 * MediaStore) on the clips from scripts/make_test_clips.sh. The outputs land in
 * Movies/Clip Joiner/test_*.mp4, where CI pulls them and checks them with ffprobe
 * (scripts/verify_outputs.py).
 */
@RunWith(AndroidJUnit4::class)
class MergeEngineTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var dir: File

    @Before
    fun copyClips() {
        dir = File(context.cacheDir, "testclips").apply { mkdirs() }
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        for (name in assets.list("clips")!!) {
            assets.open("clips/$name").use { input -> File(dir, name).outputStream().use { input.copyTo(it) } }
        }
    }

    private fun clips(vararg names: String): List<ClipInfo> =
        names.map { ClipProbe.probe(context, Uri.fromFile(File(dir, "$it.mp4"))) }

    private fun merge(name: String, clips: List<ClipInfo>, plan: MergePlan): SavedVideo {
        val start = SystemClock.elapsedRealtime()
        val saved = runBlocking { MergeEngine(context).merge(clips, plan, "$name.mp4") }
        val ms = SystemClock.elapsedRealtime() - start
        Log.i(TAG, "RESULT $name: ${ms} ms, ${saved.sizeBytes} bytes, inputs ${clips.sumOf { it.sizeBytes }} bytes, " +
            "converted ${plan.convertCount}, silent ${plan.silenceCount}")
        // The device's own decoder must play the whole thing, including the switch to a
        // converted clip's codec headers.
        val expectedFrames = clips.sumOf { (it.durationUs * it.video.frameRate / 1_000_000).toInt() }
        val decoded = decodeAllFrames(saved.uri)
        Log.i(TAG, "RESULT $name: decoded $decoded frames, expected ~$expectedFrames")
        assertTrue("decoded $decoded of ~$expectedFrames frames", kotlin.math.abs(decoded - expectedFrames) <= 3)
        return saved
    }

    @Test
    fun a_threeIdenticalPortraitClips_streamCopyOnly() {
        val clips = clips("p1", "p2", "p3")
        clips.forEach {
            assertEquals(Orientation.PORTRAIT, it.video.orientation)
            assertEquals(90, it.video.rotation)
        }
        val analysis = MergeAnalysis(clips)
        assertTrue(analysis.allCompatible)
        val plan = analysis.defaultPlan()
        assertEquals(0, plan.convertCount)
        val saved = merge("test_a", clips, plan)
        val inputs = clips.sumOf { it.sizeBytes }
        assertTrue("size ${saved.sizeBytes} vs inputs $inputs", kotlin.math.abs(saved.sizeBytes - inputs) < inputs * 0.03)
    }

    @Test
    fun b_twoPortraitOneLandscapeHevc_convertsOnlyTheLandscapeClip() {
        val clips = clips("p1", "p2", "l_hevc")
        val analysis = MergeAnalysis(clips)
        assertTrue(analysis.needsOrientationChoice)
        assertEquals(Orientation.PORTRAIT, analysis.majorityOrientation)
        val plan = analysis.planFor(Orientation.PORTRAIT)
        assertEquals(listOf(ClipAction.COPY, ClipAction.COPY, ClipAction.CONVERT), plan.steps.map { it.action })
        merge("test_b", clips, plan)
    }

    @Test
    fun c_onePortraitOneLandscape_mustChoose() {
        val clips = clips("p1", "l_hevc")
        val analysis = MergeAnalysis(clips)
        assertTrue(analysis.needsOrientationChoice)
        assertNull(analysis.majorityOrientation)
        merge("test_c_portrait", clips, analysis.planFor(Orientation.PORTRAIT))
        val landscape = analysis.planFor(Orientation.LANDSCAPE)
        assertEquals("video/hevc", landscape.targetVideo.mime)
        merge("test_c_landscape", clips, landscape)
    }

    @Test
    fun d_clipWithoutAudio_getsSilenceAndIsStillCopied() {
        val clips = clips("p1", "p_noaudio", "p2")
        assertNull(clips[1].audio)
        val analysis = MergeAnalysis(clips)
        assertTrue(analysis.allCompatible)
        assertFalse(analysis.needsOrientationChoice)
        val plan = analysis.defaultPlan()
        assertEquals(0, plan.convertCount)
        assertEquals(listOf(false, true, false), plan.steps.map { it.addSilence })
        merge("test_d", clips, plan)
    }

    private fun decodeAllFrames(uri: Uri): Int {
        val ex = MediaExtractor()
        ex.setDataSource(context, uri, null)
        val track = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("video/") }
        val format = ex.getTrackFormat(track)
        ex.selectTrack(track)
        val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(format, null, null, 0)
        codec.start()
        val info = MediaCodec.BufferInfo()
        var frames = 0
        var inputDone = false
        try {
            while (true) {
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val n = ex.readSampleData(codec.getInputBuffer(i)!!, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                if (o >= 0) {
                    if (info.size > 0) frames++
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        } finally {
            codec.stop()
            codec.release()
            ex.release()
        }
        return frames
    }

    private companion object {
        const val TAG = "ClipJoinerTest"
    }
}
