package cz.furmanek.motionphoto.panorama

import org.junit.Assert.assertEquals
import org.junit.Test

class PanoramaCaptureTest {

    @Test
    fun `rgba plane becomes opaque argb and skips row padding`() {
        // 2×2 image, rows padded to 12 bytes.
        val bytes = byteArrayOf(
            1, 2, 3, 0, 4, 5, 6, 0, 9, 9, 9, 9,
            7, 8, 9, 0, -1, 0, 0, 0, 9, 9, 9, 9,
        )

        val raster = rgbaToRaster(bytes, width = 2, height = 2, rowStride = 12, pixelStride = 4)

        assertEquals(
            listOf(0xFF010203.toInt(), 0xFF040506.toInt(), 0xFF070809.toInt(), 0xFFFF0000.toInt()),
            raster.pixels.toList(),
        )
    }

    @Test
    fun `turning right keeps the order and crosses 180 degrees smoothly`() {
        val layout = panoramaLayout(listOf(170f, -170f, -150f), focalPx = 1000.0)

        assertEquals(listOf(0, 1, 2), layout.order)
        assertEquals(listOf(349, 349), layout.expectedDx)
    }

    @Test
    fun `turning left lays the frames out in reverse`() {
        val layout = panoramaLayout(listOf(10f, -10f, -40f), focalPx = 1000.0)

        assertEquals(listOf(2, 1, 0), layout.order)
        assertEquals(listOf(524, 349), layout.expectedDx)
    }
}
