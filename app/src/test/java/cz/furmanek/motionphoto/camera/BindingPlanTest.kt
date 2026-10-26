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
            listOf(BindingAttempt(setOf(PREVIEW, IMAGE, VIDEO)), BindingAttempt(setOf(PREVIEW, IMAGE))),
            bindingAttempts(CaptureMode.PHOTO, livePhoto = true, videoExtras = true),
        )
    }

    @Test
    fun `plain photo binds preview and still capture only`() {
        assertEquals(
            listOf(BindingAttempt(setOf(PREVIEW, IMAGE))),
            bindingAttempts(CaptureMode.PHOTO, livePhoto = false, videoExtras = false),
        )
    }

    @Test
    fun `video binds preview and video capture`() {
        assertEquals(
            listOf(BindingAttempt(setOf(PREVIEW, VIDEO), videoExtras = true)),
            bindingAttempts(CaptureMode.VIDEO, livePhoto = true, videoExtras = false),
        )
    }

    @Test
    fun `video with 60 fps or stabilization falls back to plain video`() {
        assertEquals(
            listOf(
                BindingAttempt(setOf(PREVIEW, VIDEO), videoExtras = true),
                BindingAttempt(setOf(PREVIEW, VIDEO), videoExtras = false),
            ),
            bindingAttempts(CaptureMode.VIDEO, livePhoto = false, videoExtras = true),
        )
    }
}
