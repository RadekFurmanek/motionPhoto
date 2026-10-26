package cz.furmanek.motionphoto.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class MotionPhotoTimingTest {

    @Test
    fun `still position is measured from the clip start derived from its end`() {
        assertEquals(
            500_000L,
            motionPhotoPresentationTimestampUs(
                stillNs = 1_500_000_000,
                stopNs = 3_000_000_000,
                clipDurationNs = 2_000_000_000,
            ),
        )
    }

    @Test
    fun `still before the first frame is pinned to the start`() {
        assertEquals(0L, motionPhotoPresentationTimestampUs(stillNs = 500_000_000, stopNs = 3_000_000_000, clipDurationNs = 2_000_000_000))
    }

    @Test
    fun `still after the last frame is pinned to the end`() {
        assertEquals(
            2_000_000L,
            motionPhotoPresentationTimestampUs(stillNs = 3_500_000_000, stopNs = 3_000_000_000, clipDurationNs = 2_000_000_000),
        )
    }
}
