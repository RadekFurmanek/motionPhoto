package cz.furmanek.motionphoto.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class ModeBarTest {

    private val centers = floatArrayOf(40f, 150f, 300f)

    @Test
    fun `centre follows the label centres and blends between them`() {
        assertEquals(40f, centerAt(centers, 0f), 0.001f)
        assertEquals(150f, centerAt(centers, 1f), 0.001f)
        assertEquals(225f, centerAt(centers, 1.5f), 0.001f)
        assertEquals(300f, centerAt(centers, 2f), 0.001f)
    }

    @Test
    fun `centre stays on the ends`() {
        assertEquals(40f, centerAt(centers, -1f), 0.001f)
        assertEquals(300f, centerAt(centers, 5f), 0.001f)
        assertEquals(0f, centerAt(FloatArray(0), 1f), 0.001f)
    }

    @Test
    fun `dragging moves by the gap between the labels`() {
        // Finger 55 px to the left: half way from the first label to the second.
        assertEquals(0.5f, positionAfterDrag(centers, 0f, -55f), 0.001f)
        // Then 75 px further: half way from the second to the third.
        assertEquals(1.5f, positionAfterDrag(centers, 1f, -75f), 0.001f)
        assertEquals(0f, positionAfterDrag(centers, 0.2f, 500f), 0.001f)
        assertEquals(2f, positionAfterDrag(centers, 1.8f, -500f), 0.001f)
    }

    @Test
    fun `release lands on the nearest mode`() {
        assertEquals(2, modeAfterDrag(start = 0, shown = 1.6f, draggedPx = -200f, thresholdPx = 100f, count = 3))
        assertEquals(1, modeAfterDrag(start = 1, shown = 1.2f, draggedPx = -20f, thresholdPx = 100f, count = 3))
    }

    @Test
    fun `a quick swipe still moves one mode`() {
        assertEquals(2, modeAfterDrag(start = 1, shown = 1.3f, draggedPx = -120f, thresholdPx = 100f, count = 3))
        assertEquals(0, modeAfterDrag(start = 1, shown = 0.7f, draggedPx = 120f, thresholdPx = 100f, count = 3))
        assertEquals(2, modeAfterDrag(start = 2, shown = 2f, draggedPx = -120f, thresholdPx = 100f, count = 3))
    }
}
