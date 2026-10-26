package cz.furmanek.motionphoto.camera

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiVisibilityTest {

    @Test
    fun `a new screen starting before the old one stops keeps the camera visible`() {
        val visibility = UiVisibility()
        visibility.shown()
        visibility.shown()
        visibility.hidden()

        assertTrue(visibility.anyVisible)
    }

    @Test
    fun `hidden after every screen stopped`() {
        val visibility = UiVisibility()
        visibility.shown()
        visibility.hidden()

        assertFalse(visibility.anyVisible)
    }

    @Test
    fun `extra hide does not go negative`() {
        val visibility = UiVisibility()
        visibility.hidden()
        visibility.shown()

        assertTrue(visibility.anyVisible)
    }
}
