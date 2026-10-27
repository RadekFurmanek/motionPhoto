package cz.furmanek.motionphoto.ui.components

import androidx.compose.ui.graphics.Color
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

    @Test
    fun targetVerticalOffsetFollowsPitch() {
        // Looking 5° above the start puts the target (the start height) 5·k below the middle.
        assertEquals(30f, targetVerticalOffsetPx(pitchDeg = 5f, startPitchDeg = 0f, pxPerDegree = 6f, maxOffsetPx = 500f), 0.001f)
        assertEquals(-30f, targetVerticalOffsetPx(pitchDeg = -7f, startPitchDeg = -2f, pxPerDegree = 6f, maxOffsetPx = 500f), 0.001f)
        assertEquals(100f, targetVerticalOffsetPx(pitchDeg = 40f, startPitchDeg = 0f, pxPerDegree = 6f, maxOffsetPx = 100f), 0.001f)
        assertEquals(-100f, targetVerticalOffsetPx(pitchDeg = -40f, startPitchDeg = 0f, pxPerDegree = 6f, maxOffsetPx = 100f), 0.001f)
    }

    @Test
    fun levelColourWithinTwoDegrees() {
        assertEquals(LevelYellow, levelLineColor(1.9f))
        assertEquals(LevelYellow, levelLineColor(-2f))
        assertEquals(LevelYellow, levelLineColor(89f))
        assertEquals(Color.White, levelLineColor(2.5f))
        assertEquals(Color.White, levelLineColor(-30f))
    }
}
