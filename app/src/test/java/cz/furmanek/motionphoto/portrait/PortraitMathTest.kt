package cz.furmanek.motionphoto.portrait

import androidx.exifinterface.media.ExifInterface
import cz.furmanek.motionphoto.panorama.Raster
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PortraitMathTest {

    @Test
    fun `a 50 MP sensor is halved to stay near 12 MP`() = assertEquals(2, standardSampleSize(8160, 6120))

    @Test
    fun `a 12 MP sensor is kept whole`() = assertEquals(1, standardSampleSize(4080, 3072))

    @Test
    fun `fitting never upscales`() = assertEquals(800 to 600, fitInside(800, 600, 1024))

    @Test
    fun `fitting keeps the aspect ratio`() = assertEquals(1024 to 768, fitInside(4000, 3000, 1024))

    @Test
    fun `an empty mask has no subject`() = assertFalse(hasSubject(FloatArray(10_000)))

    @Test
    fun `under one percent of the frame is no subject`() =
        assertFalse(hasSubject(FloatArray(10_000) { if (it < 50) 1f else 0f }))

    @Test
    fun `a confident region over one percent is a subject`() =
        assertTrue(hasSubject(FloatArray(10_000) { if (it < 500) 0.9f else 0f }))

    @Test
    fun `full foreground confidence keeps the sharp pixel`() =
        assertEquals(0xFF102030.toInt(), blend(0xFF102030.toInt(), 0xFFFFFFFF.toInt(), 1f))

    @Test
    fun `zero foreground confidence takes the blurred pixel`() =
        assertEquals(0xFFFFFFFF.toInt(), blend(0xFF102030.toInt(), 0xFFFFFFFF.toInt(), 0f))

    @Test
    fun `halfway confidence mixes the channels evenly and stays opaque`() {
        val mixed = blend(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0.5f)

        assertEquals(0xFF, mixed ushr 24)
        for (shift in intArrayOf(16, 8, 0)) {
            assertTrue((mixed ushr shift and 0xFF) in 127..128)
        }
    }

    @Test
    fun `blurring a uniform image changes nothing`() {
        val blurred = boxBlur(Raster(20, 20, IntArray(400) { 0xFF404040.toInt() }), 4)

        assertTrue(blurred.pixels.all { it == 0xFF404040.toInt() })
    }

    @Test
    fun `blurring spreads a single point without reaching far corners`() {
        val pixels = IntArray(21 * 21) { 0xFF000000.toInt() }
        pixels[10 * 21 + 10] = 0xFFFFFFFF.toInt()

        val blurred = boxBlur(Raster(21, 21, pixels), 3)

        assertTrue((blurred.pixel(10, 10) and 0xFF) < 255)
        assertTrue((blurred.pixel(13, 10) and 0xFF) > 0)
        assertEquals(0xFF000000.toInt(), blurred.pixel(0, 0))
    }

    @Test
    fun `feathering softens a hard mask edge`() {
        val mask = FloatArray(20) { if (it < 10) 1f else 0f }

        val soft = blurMask(mask, 20, 1, 2)

        assertTrue(soft[9] > 0.3f && soft[9] < 0.9f)
        assertTrue(soft[10] > 0.1f && soft[10] < 0.7f)
        assertEquals(1f, soft[0], 0f)
        assertEquals(0f, soft[19], 0f)
    }

    @Test
    fun `a mask is interpolated between pixel centres`() =
        assertEquals(0.5f, maskAt(floatArrayOf(0f, 1f), 2, 1, 0.5f, 0f), 1e-6f)

    @Test
    fun `a mask is clamped to its edge outside the frame`() =
        assertEquals(0f, maskAt(floatArrayOf(0f, 1f), 2, 1, -3f, 5f), 0f)

    @Test
    fun `a photo pixel centre maps onto the matching mask pixel centre`() {
        // 4000 px photo, 1000 px mask: photo pixels 0..3 cover mask pixel 0, whose centre lies between 1 and 2.
        assertEquals(-0.375f, toMaskCoordinate(0, 0.25f), 1e-6f)
        assertEquals(0f, (toMaskCoordinate(1, 0.25f) + toMaskCoordinate(2, 0.25f)) / 2, 1e-6f)
        assertEquals(999.375f, toMaskCoordinate(3999, 0.25f), 1e-3f)
    }

    @Test
    fun `equal sizes map pixels onto themselves`() = assertEquals(7f, toMaskCoordinate(7, 1f), 1e-6f)

    @Test
    fun `argb sampling interpolates each channel and is opaque`() {
        val pixels = intArrayOf(0x00000000, 0x00FF8040)
        assertEquals(0xFF804020.toInt(), argbAt(pixels, 2, 1, 0.5f, 0f))
    }

    @Test
    fun `argb sampling clamps to the edge`() {
        val pixels = intArrayOf(0xFF102030.toInt(), 0xFF405060.toInt(), 0xFF708090.toInt(), 0xFFA0B0C0.toInt())
        assertEquals(0xFF102030.toInt(), argbAt(pixels, 2, 2, -3f, -0.4f))
        assertEquals(0xFFA0B0C0.toInt(), argbAt(pixels, 2, 2, 5f, 1.6f))
    }

    // A 2 x 3 mask, row by row:  0 1 / 2 3 / 4 5
    private val tall = FloatArray(6) { it.toFloat() }

    @Test
    fun `a 2x3 mask turned 90 degrees is 3x2 with the left column on top`() {
        assertEquals(3 to 2, turnedSize(2, 3, 90))
        assertArrayEquals(floatArrayOf(4f, 2f, 0f, 5f, 3f, 1f), rotateMask(tall, 2, 3, 90), 0f)
    }

    @Test
    fun `a 2x3 mask turned 180 and 270 degrees`() {
        assertArrayEquals(floatArrayOf(5f, 4f, 3f, 2f, 1f, 0f), rotateMask(tall, 2, 3, 180), 0f)
        assertArrayEquals(floatArrayOf(1f, 3f, 5f, 0f, 2f, 4f), rotateMask(tall, 2, 3, 270), 0f)
    }

    @Test
    fun `turning by 90 then 270 is the identity`() =
        assertArrayEquals(tall, rotateMask(rotateMask(tall, 2, 3, 90), 3, 2, 270), 0f)

    @Test
    fun `upright pixels match the rotated mask`() {
        val pixels = IntArray(6) { it }
        assertArrayEquals(intArrayOf(4, 2, 0, 5, 3, 1), storedToUpright(pixels, 2, 3, 90, false))
    }

    /**
     * How each EXIF orientation displays the stored 2 x 3 image `0 1 / 2 3 / 4 5`, straight from the EXIF
     * definitions (e.g. transpose: upright(x, y) = stored(y, x)), driven through [exifTurn] as the processor does.
     */
    @Test
    fun `every exif orientation turns stored pixels upright`() {
        val expected = mapOf(
            ExifInterface.ORIENTATION_NORMAL to intArrayOf(0, 1, 2, 3, 4, 5),
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL to intArrayOf(1, 0, 3, 2, 5, 4),
            ExifInterface.ORIENTATION_ROTATE_180 to intArrayOf(5, 4, 3, 2, 1, 0),
            ExifInterface.ORIENTATION_FLIP_VERTICAL to intArrayOf(4, 5, 2, 3, 0, 1),
            ExifInterface.ORIENTATION_TRANSPOSE to intArrayOf(0, 2, 4, 1, 3, 5),
            ExifInterface.ORIENTATION_ROTATE_90 to intArrayOf(4, 2, 0, 5, 3, 1),
            ExifInterface.ORIENTATION_TRANSVERSE to intArrayOf(5, 3, 1, 4, 2, 0),
            ExifInterface.ORIENTATION_ROTATE_270 to intArrayOf(1, 3, 5, 0, 2, 4),
        )
        for ((orientation, upright) in expected) {
            val (degrees, flip) = exifTurn(orientation)
            assertArrayEquals("orientation $orientation", upright, storedToUpright(IntArray(6) { it }, 2, 3, degrees, flip))
        }
    }

    @Test
    fun `exif turns match ExifInterface rotationDegrees and isFlipped`() {
        assertEquals(270 to true, exifTurn(ExifInterface.ORIENTATION_TRANSPOSE))
        assertEquals(90 to true, exifTurn(ExifInterface.ORIENTATION_TRANSVERSE))
        assertEquals(0 to false, exifTurn(ExifInterface.ORIENTATION_UNDEFINED))
    }

    @Test
    fun `a mask of the upright image maps back onto the stored pixels for every orientation`() {
        for (degrees in intArrayOf(0, 90, 180, 270)) for (flip in booleanArrayOf(false, true)) {
            val upright = storedToUpright(IntArray(6) { it }, 2, 3, degrees, flip)
            val (uw, uh) = turnedSize(2, 3, degrees)
            // A mask that holds, at each upright pixel, the stored index it came from.
            val mask = FloatArray(upright.size) { upright[it].toFloat() }
            assertArrayEquals("$degrees $flip", tall, uprightToStored(mask, uw, uh, degrees, flip), 0f)
        }
    }
}
