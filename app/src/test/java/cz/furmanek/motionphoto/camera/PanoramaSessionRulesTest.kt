package cz.furmanek.motionphoto.camera

import cz.furmanek.motionphoto.settings.CameraSettings
import cz.furmanek.motionphoto.settings.CaptureMode
import cz.furmanek.motionphoto.settings.LENS_FACING_FRONT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PanoramaSessionRulesTest {

    private val capturing = PanoramaUiState(capturing = true)
    private val stitching = PanoramaUiState(capturing = false, stitchingProgress = 0.5f)

    @Test
    fun `shutter finishes a running panorama whatever the mode says`() {
        assertEquals(ShutterAction.FINISH_PANORAMA, shutterAction(CaptureMode.PANORAMA, recording = false, panorama = capturing))
        assertEquals(ShutterAction.FINISH_PANORAMA, shutterAction(CaptureMode.PHOTO, recording = false, panorama = capturing))
    }

    @Test
    fun `shutter does nothing while a panorama is stitched`() {
        assertEquals(ShutterAction.NONE, shutterAction(CaptureMode.PANORAMA, recording = false, panorama = stitching))
    }

    @Test
    fun `shutter starts a panorama, a photo or a recording by mode`() {
        assertEquals(ShutterAction.START_PANORAMA, shutterAction(CaptureMode.PANORAMA, recording = false, panorama = null))
        assertEquals(ShutterAction.PHOTO, shutterAction(CaptureMode.PHOTO, recording = false, panorama = null))
        assertEquals(ShutterAction.START_RECORDING, shutterAction(CaptureMode.VIDEO, recording = false, panorama = null))
        assertEquals(ShutterAction.STOP_RECORDING, shutterAction(CaptureMode.TIMELAPSE, recording = true, panorama = null))
    }

    @Test
    fun `leaving panorama mode or the back camera ends the panorama`() {
        val panorama = CameraSettings(mode = CaptureMode.PANORAMA)
        assertTrue(endsPanorama(panorama, panorama.copy(mode = CaptureMode.PHOTO)))
        assertTrue(endsPanorama(panorama, panorama.copy(lensFacing = LENS_FACING_FRONT)))
        assertFalse(endsPanorama(panorama, panorama.copy(saveLocation = !panorama.saveLocation)))
    }

    @Test
    fun `controls are locked while a panorama is taken or stitched`() {
        assertFalse(SessionState().controlsLocked)
        assertTrue(SessionState(panorama = capturing).controlsLocked)
        assertTrue(SessionState(panorama = stitching).controlsLocked)
        assertTrue(SessionState(busy = true).controlsLocked)
    }
}
