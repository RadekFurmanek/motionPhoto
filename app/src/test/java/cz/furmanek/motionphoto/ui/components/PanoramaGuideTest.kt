package cz.furmanek.motionphoto.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class PanoramaGuideTest {

    @Test
    fun `target sits on the side of the sweep`() {
        assertEquals(60f, targetOffsetPx(yawToNextDeg = 10f, direction = 1, pxPerDegree = 6f, maxOffsetPx = 500f), 0.001f)
        assertEquals(-60f, targetOffsetPx(yawToNextDeg = 10f, direction = -1, pxPerDegree = 6f, maxOffsetPx = 500f), 0.001f)
    }

    @Test
    fun `target sits on the right before the direction is known`() {
        assertEquals(60f, targetOffsetPx(yawToNextDeg = 10f, direction = 0, pxPerDegree = 6f, maxOffsetPx = 500f), 0.001f)
    }

    @Test
    fun `offset is capped on either side and never crosses the middle`() {
        assertEquals(100f, targetOffsetPx(yawToNextDeg = 90f, direction = 1, pxPerDegree = 6f, maxOffsetPx = 100f), 0.001f)
        assertEquals(-100f, targetOffsetPx(yawToNextDeg = 90f, direction = -1, pxPerDegree = 6f, maxOffsetPx = 100f), 0.001f)
        assertEquals(0f, targetOffsetPx(yawToNextDeg = -5f, direction = -1, pxPerDegree = 6f, maxOffsetPx = 100f), 0.001f)
        assertEquals(0f, targetOffsetPx(yawToNextDeg = 10f, direction = 1, pxPerDegree = 6f, maxOffsetPx = -20f), 0.001f)
    }
}
