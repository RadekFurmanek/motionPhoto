package cz.furmanek.motionphoto.camera

import androidx.camera.extensions.ExtensionMode
import cz.furmanek.motionphoto.settings.CameraSettings
import cz.furmanek.motionphoto.settings.CaptureMode.NIGHT
import cz.furmanek.motionphoto.settings.CaptureMode.PANORAMA
import cz.furmanek.motionphoto.settings.CaptureMode.PHOTO
import cz.furmanek.motionphoto.settings.CaptureMode.PORTRAIT
import cz.furmanek.motionphoto.settings.CaptureMode.SLOW_MOTION
import cz.furmanek.motionphoto.settings.CaptureMode.TIMELAPSE
import cz.furmanek.motionphoto.settings.CaptureMode.VIDEO
import cz.furmanek.motionphoto.settings.LENS_FACING_BACK
import cz.furmanek.motionphoto.settings.LENS_FACING_FRONT
import cz.furmanek.motionphoto.settings.VideoQualitySetting
import org.junit.Assert.assertEquals
import org.junit.Test

class ModesTest {

    private fun caps(
        slowMotionFps: Set<Int> = emptySet(),
        extensionModes: Set<Int> = emptySet(),
        hasRotationVector: Boolean = false,
        focalMm: Float = 0f,
    ) = CameraCapabilities(
        hasFlash = false,
        minZoom = 1f,
        maxZoom = 1f,
        exposureRange = 0..0,
        exposureStep = 0f,
        videoQualities = listOf(VideoQualitySetting.HD),
        fps60Qualities = emptySet(),
        videoStabilization = false,
        backLenses = emptyList(),
        hasFrontCamera = true,
        hasBackCamera = true,
        slowMotionFps = slowMotionFps,
        extensionModes = extensionModes,
        hasRotationVector = hasRotationVector,
        focalMm = focalMm,
        sensorWidthMm = 5.6f,
        sensorHeightMm = 4.2f,
    )

    private val panoramaReady = caps(hasRotationVector = true, focalMm = 4.38f)

    @Test
    fun `plain camera offers timelapse, video and photo`() {
        assertEquals(listOf(TIMELAPSE, VIDEO, PHOTO), availableModes(caps(), LENS_FACING_BACK))
    }

    @Test
    fun `slow motion and extension modes appear in bar order`() {
        assertEquals(
            listOf(TIMELAPSE, SLOW_MOTION, VIDEO, PHOTO, PORTRAIT, NIGHT),
            availableModes(caps(slowMotionFps = setOf(120), extensionModes = setOf(ExtensionMode.BOKEH, ExtensionMode.NIGHT)), LENS_FACING_BACK),
        )
    }

    @Test
    fun `hdr alone adds no mode`() {
        assertEquals(listOf(TIMELAPSE, VIDEO, PHOTO), availableModes(caps(extensionModes = setOf(ExtensionMode.HDR)), LENS_FACING_BACK))
    }

    @Test
    fun `panorama comes last on a back camera with a rotation vector sensor`() {
        assertEquals(listOf(TIMELAPSE, VIDEO, PHOTO, PANORAMA), availableModes(panoramaReady, LENS_FACING_BACK))
    }

    @Test
    fun `no panorama without a rotation vector sensor`() {
        assertEquals(false, PANORAMA in availableModes(caps(focalMm = 4.38f), LENS_FACING_BACK))
    }

    @Test
    fun `no panorama on the front camera`() {
        assertEquals(false, PANORAMA in availableModes(panoramaReady, LENS_FACING_FRONT))
    }

    @Test
    fun `no panorama when the camera reports no focal length`() {
        assertEquals(false, PANORAMA in availableModes(caps(hasRotationVector = true), LENS_FACING_BACK))
    }

    @Test
    fun `unavailable stored mode falls back to photo`() {
        assertEquals(PHOTO, fallbackMode(NIGHT, listOf(VIDEO, PHOTO)))
        assertEquals(VIDEO, fallbackMode(VIDEO, listOf(VIDEO, PHOTO)))
    }

    @Test
    fun `slow motion rate needs an exact fixed range`() {
        val only240 = setOf(240 to 240)
        assertEquals(false, supportsFixedRate(only240, 120))
        assertEquals(true, supportsFixedRate(only240, 240))
    }

    @Test
    fun `a mode that fails to bind falls back to plain photo`() {
        val night = CameraSettings(mode = NIGHT)
        assertEquals(CameraSettings(mode = PHOTO), afterBindFailure(night))
        val slow = CameraSettings(mode = SLOW_MOTION)
        assertEquals(PHOTO, afterBindFailure(slow)?.mode)
    }

    @Test
    fun `hdr that fails to bind is switched off`() {
        assertEquals(CameraSettings(mode = PHOTO, hdr = false), afterBindFailure(CameraSettings(mode = PHOTO, hdr = true)))
    }

    @Test
    fun `plain photo or video failures have nothing to fall back to`() {
        assertEquals(null, afterBindFailure(CameraSettings(mode = PHOTO)))
        assertEquals(null, afterBindFailure(CameraSettings(mode = VIDEO)))
    }
}
