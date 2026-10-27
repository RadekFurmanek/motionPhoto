package cz.furmanek.motionphoto.panorama

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PanoramaProgressTest {

    @Test
    fun `first reading always captures, then every step`() {
        val progress = PanoramaProgress(stepDeg = 20f)
        assertTrue(progress.onYaw(0f))
        assertFalse(progress.onYaw(10f))
        assertTrue(progress.onYaw(20f))
        assertEquals(2, progress.capturedCount)
    }

    @Test
    fun `turning back captures nothing`() {
        val progress = PanoramaProgress(stepDeg = 20f)
        progress.onYaw(0f)
        progress.onYaw(20f)
        assertFalse(progress.onYaw(15f))
        assertFalse(progress.onYaw(0f))
        assertEquals(2, progress.capturedCount)
    }

    @Test
    fun `crossing plus minus 180 keeps going`() {
        val progress = PanoramaProgress(stepDeg = 20f)
        progress.onYaw(160f)
        assertTrue(progress.onYaw(180f))
        assertTrue(progress.onYaw(-160f))
        assertEquals(40f, progress.sweptDeg, 0.01f)
    }

    @Test
    fun `turning left works too`() {
        val progress = PanoramaProgress(stepDeg = 20f)
        assertTrue(progress.onYaw(0f))
        assertTrue(progress.onYaw(-20f))
        assertTrue(progress.onYaw(-40f))
        assertEquals(40f, progress.sweptDeg, 0.01f)
    }

    @Test
    fun `direction is unknown until the first move of half a step`() {
        val right = PanoramaProgress(stepDeg = 20f)
        right.onYaw(0f)
        right.onYaw(5f)
        assertEquals(0, right.direction)
        right.onYaw(10f)
        assertEquals(1, right.direction)

        // Left across ±180°: from −175° to 175° is 10° to the left.
        val left = PanoramaProgress(stepDeg = 20f)
        left.onYaw(-175f)
        left.onYaw(175f)
        assertEquals(-1, left.direction)
    }

    @Test
    fun `finishes near a full turn`() {
        val progress = PanoramaProgress(stepDeg = 20f)
        var yaw = 0f
        while (yaw <= 340f) {
            progress.onYaw(((yaw + 180f) % 360f) - 180f)
            yaw += 20f
        }
        assertTrue(progress.finished)
    }

    @Test
    fun `level within tolerance`() {
        assertTrue(isLevel(5f))
        assertFalse(isLevel(-7f))
    }
}
