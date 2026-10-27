package cz.furmanek.motionphoto.panorama

import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StitcherTest {

    /** A smooth, non-repeating texture: sums of sines at several frequencies plus a little noise. */
    private fun syntheticScene(width: Int, height: Int, seed: Int): Raster {
        val random = Random(seed)
        val phases = DoubleArray(12) { random.nextDouble() * 6.28 }
        val pixels = IntArray(width * height) { i ->
            val x = i % width
            val y = i / width
            fun channel(k: Int): Int {
                var v = 128.0
                for (f in 0 until 4) {
                    val freq = 0.011 * (f + 1) * (k + 1.3)
                    v += 28 * sin(x * freq + phases[k * 4 + f]) * sin(y * freq * 0.7 + phases[(k * 4 + f + 3) % 12])
                }
                return (v + random.nextInt(-3, 4)).roundToInt().coerceIn(0, 255)
            }
            (0xFF shl 24) or (channel(0) shl 16) or (channel(1) shl 8) or channel(2)
        }
        return Raster(width, height, pixels)
    }

    private fun Raster.crop(left: Int, top: Int, w: Int, h: Int) =
        Raster(w, h, IntArray(w * h) { i -> pixel(left + i % w, top + i / w) })

    private fun meanChannelDiff(a: Raster, b: Raster): Double {
        var sum = 0L
        for (i in a.pixels.indices) {
            for (shift in intArrayOf(16, 8, 0)) {
                sum += abs((a.pixels[i] shr shift and 0xFF) - (b.pixels[i] shr shift and 0xFF))
            }
        }
        return sum.toDouble() / (a.pixels.size * 3)
    }

    private val scene = syntheticScene(2400, 360, seed = 7)

    @Test
    fun `shift between overlapping frames is found including vertical drift`() {
        val left = scene.crop(0, 20, 400, 320)
        val right = scene.crop(150, 23, 400, 320)
        val shift = estimateShift(left, right, expectedDx = 140, searchX = 40, searchY = 8)
        assertTrue(shift.confident)
        assertEquals(150.0, shift.dx.toDouble(), 1.0)
        assertEquals(3.0, shift.dy.toDouble(), 1.0)
    }

    @Test
    fun `featureless frames fall back to the expected shift`() {
        val flat = Raster(400, 320, IntArray(400 * 320) { 0xFFC0C0C0.toInt() })
        val shift = estimateShift(flat, flat, expectedDx = 140, searchX = 40, searchY = 8)
        assertFalse(shift.confident)
        assertEquals(140, shift.dx)
    }

    @Test
    fun `focal length in pixels from lens and sensor size`() {
        assertEquals(750.9, focalLengthPx(4.38f, 5.6f, 960), 0.1)
    }
}
