package cz.furmanek.motionphoto.camera

import cz.furmanek.motionphoto.settings.VideoQualitySetting.FHD
import cz.furmanek.motionphoto.settings.VideoQualitySetting.HD
import cz.furmanek.motionphoto.settings.VideoQualitySetting.SD
import cz.furmanek.motionphoto.settings.VideoQualitySetting.UHD
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CameraCapabilitiesTest {

    @Test
    fun `zoom presets include ultra wide when the camera reaches it`() {
        assertEquals(listOf(0.5f, 1f, 2f, 5f, 10f), zoomPresets(0.5f, 10f))
    }

    @Test
    fun `zoom presets stay within the zoom range`() {
        assertEquals(listOf(1f, 2f, 5f), zoomPresets(1f, 8f))
        assertEquals(listOf(1f), zoomPresets(1f, 1f))
    }

    @Test
    fun `requested video quality falls back to the nearest lower one`() {
        assertEquals(FHD, resolveVideoQuality(UHD, listOf(SD, HD, FHD)))
    }

    @Test
    fun `video quality falls back to the lowest available when nothing lower exists`() {
        assertEquals(HD, resolveVideoQuality(SD, listOf(HD, FHD)))
    }

    @Test
    fun `no video quality when the camera cannot record`() {
        assertNull(resolveVideoQuality(FHD, emptyList()))
    }
}
