package cz.furmanek.motionphoto.camera

import cz.furmanek.motionphoto.camera.UseCaseKind.IMAGE
import cz.furmanek.motionphoto.camera.UseCaseKind.PREVIEW
import cz.furmanek.motionphoto.camera.UseCaseKind.VIDEO
import cz.furmanek.motionphoto.settings.CaptureMode
import org.junit.Assert.assertEquals
import org.junit.Test

class BindingPlanTest {

    @Test
    fun `live photo falls back to a plain photo binding`() {
        assertEquals(
            listOf(setOf(PREVIEW, IMAGE, VIDEO), setOf(PREVIEW, IMAGE)),
            bindingAttempts(CaptureMode.PHOTO, livePhoto = true),
        )
    }

    @Test
    fun `plain photo binds preview and still capture only`() {
        assertEquals(listOf(setOf(PREVIEW, IMAGE)), bindingAttempts(CaptureMode.PHOTO, livePhoto = false))
    }

    @Test
    fun `video binds preview and video capture`() {
        assertEquals(listOf(setOf(PREVIEW, VIDEO)), bindingAttempts(CaptureMode.VIDEO, livePhoto = true))
    }
}
