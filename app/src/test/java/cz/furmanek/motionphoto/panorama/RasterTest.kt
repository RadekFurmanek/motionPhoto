package cz.furmanek.motionphoto.panorama

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class RasterTest {

    // 2 wide, 3 tall; pixel value = 10*y + x so positions are readable.
    private val grid = Raster(2, 3, IntArray(6) { i -> 10 * (i / 2) + i % 2 })

    @Test
    fun `rotating 90 degrees clockwise swaps dimensions and moves corners`() {
        val rotated = grid.rotate(90)
        assertEquals(3, rotated.width)
        assertEquals(2, rotated.height)
        // Clockwise: the bottom-left corner (0,2)=20 ends up top-left; top-left (0,0)=0 ends up top-right.
        assertEquals(20, rotated.pixel(0, 0))
        assertEquals(0, rotated.pixel(2, 0))
        assertEquals(21, rotated.pixel(0, 1))
        assertEquals(1, rotated.pixel(2, 1))
    }

    @Test
    fun `rotating by 270 undoes 90`() {
        assertArrayEquals(grid.pixels, grid.rotate(90).rotate(270).pixels)
    }

    @Test
    fun `scaling to a height keeps the aspect ratio`() {
        val scaled = Raster(400, 200, IntArray(400 * 200) { OPAQUE_GREY }).scaleToHeight(100)
        assertEquals(200, scaled.width)
        assertEquals(100, scaled.height)
        assertEquals(OPAQUE_GREY, scaled.pixel(100, 50))
    }

    private companion object {
        const val OPAQUE_GREY = 0xFF808080.toInt()
    }
}
