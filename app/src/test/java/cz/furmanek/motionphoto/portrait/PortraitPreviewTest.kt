package cz.furmanek.motionphoto.portrait

import cz.furmanek.motionphoto.panorama.Raster
import cz.furmanek.motionphoto.panorama.rotate
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PortraitPreviewTest {

    /** Every pixel distinct, opaque. */
    private fun numbered(width: Int, height: Int) = Raster(width, height, IntArray(width * height) { (0xFF shl 24) or it })

    @Test
    fun `turning at full size matches Raster rotate`() {
        val src = numbered(4, 3)
        for (degrees in intArrayOf(0, 90, 180, 270)) {
            val expected = src.rotate(degrees)
            val actual = previewRaster(src, 0, 0, 4, 3, degrees, mirrored = false, outHeight = expected.height)
            assertEquals("width at $degrees", expected.width, actual.width)
            assertArrayEquals("pixels at $degrees", expected.pixels, actual.pixels)
        }
    }

    @Test
    fun `mirroring flips the turned image left to right`() {
        val src = numbered(4, 3)
        val turned = src.rotate(90)
        val mirrored = previewRaster(src, 0, 0, 4, 3, 90, mirrored = true, outHeight = turned.height)
        for (y in 0 until turned.height) {
            for (x in 0 until turned.width) {
                assertEquals(turned.pixel(turned.width - 1 - x, y), mirrored.pixel(x, y))
            }
        }
    }

    @Test
    fun `only the crop is shown`() {
        val src = numbered(6, 4)
        val cropped = previewRaster(src, 1, 1, 4, 2, 0, mirrored = false, outHeight = 2)
        assertArrayEquals(intArrayOf(7, 8, 9, 10, 13, 14, 15, 16).map { (0xFF shl 24) or it }.toIntArray(), cropped.pixels)
    }

    @Test
    fun `a 960 x 720 sensor frame turned upright is 360 x 480`() {
        val out = previewRaster(Raster(960, 720, IntArray(960 * 720)), 0, 0, 960, 720, 90, mirrored = false, outHeight = 480)
        assertEquals(360, out.width)
        assertEquals(480, out.height)
    }

    @Test
    fun `halving averages neighbouring pixels`() {
        val src = Raster(4, 2, intArrayOf(0, 0xFF, 0, 0xFF, 0, 0xFF, 0, 0xFF).map { it or (0xFF shl 24) }.toIntArray())
        val half = previewRaster(src, 0, 0, 4, 2, 0, mirrored = false, outHeight = 1)
        assertArrayEquals(intArrayOf(0xFF000080.toInt(), 0xFF000080.toInt()), half.pixels)
    }

    @Test
    fun `the subject is transparent and the background opaque`() {
        val blurred = Raster(2, 1, intArrayOf(0xFF112233.toInt(), 0xFF445566.toInt()))
        val layer = backgroundLayer(blurred, maskAlpha(floatArrayOf(1f, 0f), 2, 1, 2, 1))
        assertArrayEquals(intArrayOf(0x00112233, 0xFF445566.toInt()), layer)
    }

    @Test
    fun `a half confident pixel is half covered`() =
        assertEquals(128, maskAlpha(floatArrayOf(0.5f), 1, 1, 1, 1)[0] ushr 24)

    @Test
    fun `the mask is stretched over the whole preview`() {
        val alpha = maskAlpha(floatArrayOf(1f, 0f), 2, 1, 4, 2)
        assertEquals(0, alpha[0] ushr 24)
        assertEquals(255, alpha[7] ushr 24)
    }

    /** [src] as an RGBA_8888 plane with [padding] spare bytes at each row end. */
    private fun rgbaPlane(src: Raster, padding: Int): ByteArray {
        val rowStride = src.width * 4 + padding
        val bytes = ByteArray(rowStride * src.height)
        for (y in 0 until src.height) {
            for (x in 0 until src.width) {
                val p = src.pixel(x, y)
                val o = y * rowStride + x * 4
                bytes[o] = (p ushr 16).toByte()
                bytes[o + 1] = (p ushr 8).toByte()
                bytes[o + 2] = p.toByte()
                bytes[o + 3] = 0xFF.toByte()
            }
        }
        return bytes
    }

    @Test
    fun `reading the plane directly matches the raster path`() {
        val src = Raster(12, 9, IntArray(108) { (0xFF shl 24) or ((it * 37) % 256 shl 16) or ((it * 11) % 256 shl 8) or (it * 5 % 256) })
        val bytes = rgbaPlane(src, padding = 8)
        for (degrees in intArrayOf(0, 90, 180, 270)) {
            for (mirrored in listOf(false, true)) {
                for (outHeight in intArrayOf(4, 6, 9)) {
                    val expected = previewRaster(src, 2, 1, 8, 6, degrees, mirrored, outHeight)
                    val actual = rgbaPreview(bytes, 12 * 4 + 8, 4, 2, 1, 8, 6, degrees, mirrored, outHeight)
                    assertEquals(expected.width, actual.width)
                    for (i in expected.pixels.indices) {
                        for (shift in intArrayOf(16, 8, 0)) {
                            val difference = (expected.pixels[i] ushr shift and 0xFF) - (actual.pixels[i] ushr shift and 0xFF)
                            assertTrue("$degrees $mirrored $outHeight pixel $i off by $difference", abs(difference) <= 1)
                        }
                        assertEquals(0xFF, actual.pixels[i] ushr 24)
                    }
                }
            }
        }
    }

    @Test
    fun `preview and photo blur the same fraction of the frame`() {
        // A 480 px preview and a 1024 px photo mask: both 1.2 % of the longer side.
        assertEquals(6, blurRadius(480, BACKGROUND_BLUR))
        assertEquals(12, blurRadius(1024, BACKGROUND_BLUR))
        assertEquals(3, blurRadius(256, MASK_FEATHER))
    }
}
