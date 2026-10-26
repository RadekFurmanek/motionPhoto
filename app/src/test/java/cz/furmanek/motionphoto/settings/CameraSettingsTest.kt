package cz.furmanek.motionphoto.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraSettingsTest {

    @Test
    fun `live photo is effective only without flash`() {
        assertTrue(CameraSettings(livePhoto = true, flash = FlashSetting.OFF).effectiveLivePhoto())
        assertFalse(CameraSettings(livePhoto = true, flash = FlashSetting.AUTO).effectiveLivePhoto())
        assertFalse(CameraSettings(livePhoto = true, flash = FlashSetting.ON).effectiveLivePhoto())
    }

    @Test
    fun `live photo switched off stays off whatever the flash`() {
        FlashSetting.entries.forEach {
            assertFalse(CameraSettings(livePhoto = false, flash = it).effectiveLivePhoto())
        }
    }

    @Test
    fun `shutter sound plays when the law requires it even if switched off`() {
        assertTrue(CameraSettings(shutterSound = false).shutterSoundRequired(mustPlay = true))
        assertFalse(CameraSettings(shutterSound = false).shutterSoundRequired(mustPlay = false))
        assertTrue(CameraSettings(shutterSound = true).shutterSoundRequired(mustPlay = false))
    }
}
