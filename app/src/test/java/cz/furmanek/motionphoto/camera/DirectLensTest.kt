package cz.furmanek.motionphoto.camera

import android.hardware.camera2.CameraMetadata
import android.view.Surface
import cz.furmanek.motionphoto.settings.FlashSetting
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class DirectLensTest {

    private fun lens(id: String, focal: Float, hidden: Boolean = false, back: Boolean = true) = LensInfo(
        cameraId = id,
        physicalId = null,
        back = back,
        focalMm = focal,
        minFocusDiopters = 10f,
        hidden = hidden,
        sensorWidthMm = 6.4f,
    )

    @Test
    fun ultraWideChosenWhenClearlyWider() {
        val wide = lens("2", 2.1f, hidden = true)
        assertEquals(wide, ultraWideLens(listOf(lens("0", 5.6f), lens("1", 2.9f, back = false), wide), "0"))
    }

    @Test
    fun noUltraWideWhenOnlyVisibleLenses() {
        assertNull(ultraWideLens(listOf(lens("0", 5.6f), lens("3", 2.1f)), "0"))
        // Barely wider is not an ultra-wide.
        assertNull(ultraWideLens(listOf(lens("0", 5.6f), lens("2", 5.0f, hidden = true)), "0"))
    }

    @Test
    fun labelIsFovRatio() {
        assertEquals(0.4f, zoomLabelRatio(lens("2", 2.1f, hidden = true), lens("0", 5.6f)), 0.001f)
    }

    @Test
    fun jpegOrientationPortraitAndLandscape() {
        assertEquals(90, jpegOrientation(90, Surface.ROTATION_0))
        assertEquals(0, jpegOrientation(90, Surface.ROTATION_90))
        assertEquals(180, jpegOrientation(90, Surface.ROTATION_270))
        assertEquals(270, jpegOrientation(90, Surface.ROTATION_180))
    }

    @Test
    fun tapMapsThroughSensorRotation() {
        // Sensor turned 90°: the portrait preview's top-left is the sensor's bottom-left.
        assertEquals(0f to 1f, tapToSensor(0f, 0f, 90))
        assertEquals(0f to 0f, tapToSensor(1f, 0f, 90))
        assertEquals(1f to 1f, tapToSensor(0f, 1f, 90))
        assertEquals(0.25f to 0.5f, tapToSensor(0.25f, 0.5f, 0))
    }

    @Test
    fun cropCentredByZoom() {
        assertArrayEquals(intArrayOf(1000, 750, 3000, 2250), cropRegion(4000, 3000, 2f))
        assertArrayEquals(intArrayOf(0, 0, 4000, 3000), cropRegion(4000, 3000, 1f))
    }

    @Test
    fun largest43Picks43() {
        val sizes = listOf(4000 to 3000, 4096 to 2304, 1920 to 1440, 640 to 480)
        assertEquals(4000 to 3000, largest43(sizes))
        assertEquals(1920 to 1440, largest43(sizes, maxLong = 1920))
        assertNull(largest43(listOf(1920 to 1080)))
    }

    @Test
    fun `flash setting picks the auto exposure mode`() {
        assertEquals(CameraMetadata.CONTROL_AE_MODE_ON, aeModeFor(FlashSetting.OFF, hasFlash = true))
        assertEquals(CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH, aeModeFor(FlashSetting.AUTO, hasFlash = true))
        assertEquals(CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH, aeModeFor(FlashSetting.ON, hasFlash = true))
    }

    @Test
    fun `no flash unit means no flash`() {
        assertEquals(CameraMetadata.CONTROL_AE_MODE_ON, aeModeFor(FlashSetting.ON, hasFlash = false))
        assertEquals(CameraMetadata.CONTROL_AE_MODE_ON, aeModeFor(FlashSetting.AUTO, hasFlash = false))
    }

    @Test
    fun `metering for the flash ends once it has run`() {
        assertFalse(precaptureSettled(CameraMetadata.CONTROL_AE_STATE_SEARCHING, seen = false))
        assertFalse(precaptureSettled(CameraMetadata.CONTROL_AE_STATE_PRECAPTURE, seen = true))
        assertTrue(precaptureSettled(CameraMetadata.CONTROL_AE_STATE_FLASH_REQUIRED, seen = true))
        assertTrue(precaptureSettled(CameraMetadata.CONTROL_AE_STATE_CONVERGED, seen = true))
    }
}
