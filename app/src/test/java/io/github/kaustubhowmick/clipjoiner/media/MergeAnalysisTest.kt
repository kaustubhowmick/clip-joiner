package io.github.kaustubhowmick.clipjoiner.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MergeAnalysisTest {

    private val aac48 = AudioSpec("audio/mp4a-latm", 48_000, 2, 128_000)
    // Phone-style portrait: stored landscape with a 90° rotation flag.
    private val portrait = VideoSpec("video/avc", 1920, 1080, 90, 30f, 16_000_000)
    private val landscape = VideoSpec("video/avc", 1920, 1080, 0, 30f, 16_000_000)
    private val landscapeHevc = VideoSpec("video/hevc", 1920, 1080, 0, 30f, 10_000_000)

    private fun clip(v: VideoSpec, a: AudioSpec? = aac48, n: Int = 0) =
        ClipInfo("file:///c$n.mp4", "c$n", 5_000_000, 10_000_000, v, a)

    @Test
    fun rotationDecidesOrientation() {
        assertEquals(Orientation.PORTRAIT, portrait.orientation)
        assertEquals(1080, portrait.displayWidth)
        assertEquals(Orientation.LANDSCAPE, landscape.orientation)
        assertEquals(Orientation.PORTRAIT, VideoSpec("video/avc", 1080, 1920, 0, 30f, 0).orientation)
    }

    @Test
    fun identicalClipsAreStreamCopied() {
        val a = MergeAnalysis(listOf(clip(portrait, n = 1), clip(portrait, n = 2), clip(portrait.copy(frameRate = 29.7f), n = 3)))
        assertTrue(a.allCompatible)
        assertFalse(a.needsOrientationChoice)
        val plan = a.defaultPlan()
        assertEquals(0, plan.convertCount)
        assertTrue(plan.steps.all { it.action == ClipAction.COPY && !it.addSilence })
    }

    @Test
    fun twoPortraitOneLandscapeHevc_recommendsPortraitAndConvertsOnlyLandscape() {
        val a = MergeAnalysis(listOf(clip(portrait, n = 1), clip(portrait, n = 2), clip(landscapeHevc, n = 3)))
        assertFalse(a.allCompatible)
        assertTrue(a.needsOrientationChoice)
        assertEquals(Orientation.PORTRAIT, a.majorityOrientation)
        val plan = a.planFor(Orientation.PORTRAIT)
        assertEquals(listOf(ClipAction.COPY, ClipAction.COPY, ClipAction.CONVERT), plan.steps.map { it.action })
        assertEquals(portrait, plan.targetVideo)

        // Choosing the minority converts the majority to the landscape clip's exact format.
        val minority = a.planFor(Orientation.LANDSCAPE)
        assertEquals(listOf(ClipAction.CONVERT, ClipAction.CONVERT, ClipAction.COPY), minority.steps.map { it.action })
        assertEquals("video/hevc", minority.targetVideo.mime)
        assertEquals(0, minority.targetVideo.rotation)
    }

    @Test
    fun onePortraitOneLandscape_hasNoMajority() {
        val a = MergeAnalysis(listOf(clip(portrait, n = 1), clip(landscape, n = 2)))
        assertTrue(a.needsOrientationChoice)
        assertNull(a.majorityOrientation)
    }

    @Test
    fun twoVersusTwo_hasNoMajority() {
        val a = MergeAnalysis(listOf(clip(portrait, n = 1), clip(landscape, n = 2), clip(portrait, n = 3), clip(landscape, n = 4)))
        assertTrue(a.needsOrientationChoice)
        assertNull(a.majorityOrientation)
        assertEquals(2, a.planFor(Orientation.LANDSCAPE).convertCount)
    }

    @Test
    fun clipWithoutAudioGetsSilenceNotConversion() {
        val a = MergeAnalysis(listOf(clip(portrait, n = 1), clip(portrait, a = null, n = 2), clip(portrait, n = 3)))
        assertTrue(a.allCompatible)
        val plan = a.defaultPlan()
        assertEquals(0, plan.convertCount)
        assertEquals(listOf(false, true, false), plan.steps.map { it.addSilence })
        assertEquals(aac48, plan.targetAudio)
    }

    @Test
    fun noAudioAnywhereMeansNoAudioTrack() {
        val a = MergeAnalysis(listOf(clip(portrait, a = null, n = 1), clip(portrait, a = null, n = 2)))
        assertTrue(a.allCompatible)
        assertNull(a.defaultPlan().targetAudio)
        assertTrue(a.defaultPlan().steps.none { it.addSilence })
    }

    @Test
    fun audioOnlyMismatchConvertsJustTheAudio() {
        val a = MergeAnalysis(listOf(clip(portrait, n = 1), clip(portrait, aac48.copy(sampleRate = 44_100), n = 2), clip(portrait, n = 3)))
        assertFalse(a.allCompatible)
        assertFalse(a.needsOrientationChoice)
        assertEquals(listOf(ClipAction.COPY, ClipAction.CONVERT_AUDIO, ClipAction.COPY), a.defaultPlan().steps.map { it.action })
    }

    @Test
    fun sameOrientationDifferentResolution_noSheetMajorityWins() {
        val small = portrait.copy(width = 1280, height = 720)
        val a = MergeAnalysis(listOf(clip(small, n = 1), clip(portrait, n = 2), clip(portrait, n = 3)))
        assertFalse(a.needsOrientationChoice)
        val plan = a.defaultPlan()
        assertEquals(1920, plan.targetVideo.width)
        assertEquals(listOf(ClipAction.CONVERT, ClipAction.COPY, ClipAction.COPY), plan.steps.map { it.action })
    }

    @Test
    fun tieInFormatGoesToLargerFrame() {
        val small = landscape.copy(width = 1280, height = 720)
        val plan = MergeAnalysis(listOf(clip(small, n = 1), clip(landscape, n = 2))).defaultPlan()
        assertEquals(1920, plan.targetVideo.width)
    }

    @Test
    fun differentFrameRatesMismatch() {
        assertFalse(VideoSpec.frameRatesMatch(25f, 30f))
        assertTrue(VideoSpec.frameRatesMatch(29.97f, 30f))
        assertTrue(VideoSpec.frameRatesMatch(59.4f, 60f))
    }
}
