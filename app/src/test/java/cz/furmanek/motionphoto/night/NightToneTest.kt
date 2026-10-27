package cz.furmanek.motionphoto.night

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NightToneTest {

    @Test
    fun shadowsLiftAndWhiteStaysWhite() {
        assertTrue(toneCurve(0.1f, SHADOW_GAIN) > 0.15f)
        assertEquals(1f, toneCurve(1f, SHADOW_GAIN), 0.001f)
        assertEquals(0f, toneCurve(0f, SHADOW_GAIN), 0.001f)
    }

    @Test
    fun curveNeverDarkensAndKeepsOrder() {
        var last = -1f
        for (i in 0..100) {
            val x = i / 100f
            val y = toneCurve(x, SHADOW_GAIN)
            assertTrue(y >= x - 1e-4f)
            assertTrue(y >= last)
            last = y
        }
    }

    @Test
    fun digitalGainBrightensMore() {
        assertTrue(toneCurve(0.1f, SHADOW_GAIN * 2) > toneCurve(0.1f, SHADOW_GAIN))
    }

    @Test
    fun greyStaysGrey() {
        val argb = yuvToArgb(128, 128, 128)
        val r = argb shr 16 and 0xFF
        val g = argb shr 8 and 0xFF
        val b = argb and 0xFF
        assertEquals(r, g)
        assertEquals(g, b)
    }
}
