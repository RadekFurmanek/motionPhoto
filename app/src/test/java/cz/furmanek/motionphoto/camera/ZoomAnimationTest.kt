package cz.furmanek.motionphoto.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ZoomAnimationTest {

    @Test
    fun `animation ends exactly on the target`() {
        val steps = zoomAnimationSteps(from = 1f, to = 5f, durationMs = 300, frameMs = 16)
        assertEquals(5f, steps.last(), 0f)
    }

    @Test
    fun `zooming in only ever increases`() {
        val steps = zoomAnimationSteps(from = 1f, to = 5f, durationMs = 300, frameMs = 16)
        assertTrue(steps.zipWithNext().all { (a, b) -> b > a })
    }

    @Test
    fun `zooming out only ever decreases`() {
        val steps = zoomAnimationSteps(from = 10f, to = 0.5f, durationMs = 300, frameMs = 16)
        assertTrue(steps.zipWithNext().all { (a, b) -> b < a })
        assertEquals(0.5f, steps.last(), 0f)
    }

    @Test
    fun `one step per frame`() {
        assertEquals(19, zoomAnimationSteps(from = 1f, to = 2f, durationMs = 300, frameMs = 16).size)
    }

    @Test
    fun `ratio changes geometrically so 1-2x feels like 5-10x`() {
        val low = zoomAnimationSteps(from = 1f, to = 2f, durationMs = 300, frameMs = 16)
        val high = zoomAnimationSteps(from = 5f, to = 10f, durationMs = 300, frameMs = 16)
        low.zip(high).forEach { (a, b) -> assertEquals(a * 5f, b, 1e-3f) }
    }

    @Test
    fun `no animation when already there`() {
        assertEquals(listOf(2f), zoomAnimationSteps(from = 2f, to = 2f, durationMs = 300, frameMs = 16))
    }
}
