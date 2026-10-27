package cz.furmanek.motionphoto.livephoto

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class ClipWriterTest {

    @Test
    fun relativeTimesStartAtZeroAndKeepSpacing() {
        val samples = listOf(1_500_000L, 1_533_333L, 1_566_666L).map { EncodedSample(Track.VIDEO, it, false, ByteArray(0)) }
        assertArrayEquals(longArrayOf(0, 33_333, 66_666), ClipWriter.relativeTimesUs(samples, originUs = 1_500_000))
    }

    @Test
    fun relativeTimesNeverNegative() {
        val samples = listOf(1_490_000L, 1_510_000L).map { EncodedSample(Track.AUDIO, it, true, ByteArray(0)) }
        assertArrayEquals(longArrayOf(0, 10_000), ClipWriter.relativeTimesUs(samples, originUs = 1_500_000))
    }
}
