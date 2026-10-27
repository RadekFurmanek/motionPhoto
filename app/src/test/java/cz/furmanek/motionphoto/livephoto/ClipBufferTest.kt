package cz.furmanek.motionphoto.livephoto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipBufferTest {

    /** Video at 30 fps with a key frame every 0.5 s and audio every 20 ms, from [fromUs] until [toUs]. */
    private fun ClipBuffer.fill(fromUs: Long, toUs: Long) {
        val samples = mutableListOf<EncodedSample>()
        var frame = fromUs * 30 / 1_000_000
        while (frame * 1_000_000 / 30 <= toUs) {
            val t = frame * 1_000_000 / 30
            samples += EncodedSample(Track.VIDEO, t, keyFrame = frame % 15 == 0L, data = ByteArray(1))
            frame++
        }
        var a = fromUs
        while (a <= toUs) {
            samples += EncodedSample(Track.AUDIO, a, keyFrame = true, data = ByteArray(1))
            a += 20_000
        }
        samples.sortedBy { it.timeUs }.forEach(::add)
    }

    @Test
    fun clipStartsAtKeyFrameNearestBeforePreRoll() {
        val buffer = ClipBuffer().apply { fill(0, 4_500_000) }
        val clip = buffer.clip(pressUs = 3_000_000, preUs = 1_500_000, postUs = 1_500_000)!!
        assertEquals(1_500_000, clip.firstVideoUs)
        val last = clip.video.last().timeUs
        assertTrue(last in 4_466_000..4_500_000)
    }

    @Test
    fun clipBeforeKeyFrameRoundsDown() {
        val buffer = ClipBuffer().apply { fill(0, 4_700_000) }
        assertEquals(1_500_000, buffer.clip(3_200_000, 1_500_000, 1_500_000)!!.firstVideoUs)
    }

    @Test
    fun earlyPressStartsAtFirstKeyFrame() {
        val buffer = ClipBuffer().apply { fill(0, 2_000_000) }
        assertEquals(0, buffer.clip(600_000, 1_500_000, 1_500_000)!!.firstVideoUs)
    }

    @Test
    fun noKeyFrameReturnsNull() {
        val buffer = ClipBuffer()
        for (i in 1..20) buffer.add(EncodedSample(Track.VIDEO, i * 33_333L, keyFrame = false, data = ByteArray(1)))
        assertNull(buffer.clip(500_000, 1_500_000, 1_500_000))
    }

    @Test
    fun audioTrimmedToVideoRange() {
        val buffer = ClipBuffer().apply { fill(0, 4_500_000) }
        val clip = buffer.clip(3_000_000, 1_500_000, 1_500_000)!!
        val lastVideo = clip.video.last().timeUs
        assertTrue(clip.audio.isNotEmpty())
        assertTrue(clip.audio.all { it.timeUs in clip.firstVideoUs..lastVideo })
    }

    @Test
    fun oldSamplesDropped() {
        val buffer = ClipBuffer().apply { fill(0, 10_000_000) }
        val clip = buffer.clip(pressUs = 0, preUs = 0, postUs = 20_000_000)!!
        val oldest = clip.video.first()
        assertTrue(oldest.keyFrame)
        assertTrue(oldest.timeUs in 5_500_000..6_000_000)
    }

    @Test
    fun stillPositionInsideClip() {
        val buffer = ClipBuffer().apply { fill(0, 4_500_000) }
        val clip = buffer.clip(3_000_000, 1_500_000, 1_500_000)!!
        assertEquals(1_500_000, stillPositionUs(3_000_000, clip))
        assertEquals(0, stillPositionUs(1_000_000, clip))
    }

    @Test
    fun newestVideoTracksLatestFrame() {
        val buffer = ClipBuffer()
        assertNull(buffer.newestVideoUs)
        buffer.fill(0, 1_000_000)
        assertEquals(1_000_000L, buffer.newestVideoUs)
        buffer.clear()
        assertNull(buffer.newestVideoUs)
    }
}
