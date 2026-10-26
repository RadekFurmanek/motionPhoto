package cz.furmanek.motionphoto.timelapse

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelapseMathTest {

    private fun solid(width: Int, height: Int, r: Int, g: Int, b: Int, rowStride: Int = width * 4): ByteArray {
        val data = ByteArray(rowStride * height)
        for (y in 0 until height) for (x in 0 until width) {
            val i = y * rowStride + x * 4
            data[i] = r.toByte(); data[i + 1] = g.toByte(); data[i + 2] = b.toByte(); data[i + 3] = 255.toByte()
        }
        return data
    }

    private fun ByteArray.u(i: Int) = this[i].toInt() and 0xFF

    @Test
    fun `white maps to limited-range white`() {
        val out = rgbaToI420(solid(4, 2, 255, 255, 255), 4, 2, 16)
        assertEquals(12, out.size)
        (0 until 8).forEach { assertEquals(235, out.u(it)) }
        (8 until 12).forEach { assertEquals(128, out.u(it)) }
    }

    @Test
    fun `black maps to limited-range black`() {
        val out = rgbaToI420(solid(4, 2, 0, 0, 0), 4, 2, 16)
        (0 until 8).forEach { assertEquals(16, out.u(it)) }
    }

    @Test
    fun `pure red follows BT601`() {
        val out = rgbaToI420(solid(2, 2, 255, 0, 0), 2, 2, 8)
        assertEquals(82.0, out.u(0).toDouble(), 1.0)
        assertEquals(90.0, out.u(4).toDouble(), 1.0)
        assertEquals(240.0, out.u(5).toDouble(), 1.0)
    }

    @Test
    fun `row padding is skipped`() {
        val packed = rgbaToI420(solid(4, 2, 30, 140, 200), 4, 2, 16)
        val padded = rgbaToI420(solid(4, 2, 30, 140, 200, rowStride = 24), 4, 2, 24)
        assertArrayEquals(packed, padded)
    }

    @Test
    fun `frames are taken once per interval`() {
        assertTrue(shouldTakeFrame(nowMs = 0, lastMs = null, intervalMs = 1000))
        assertFalse(shouldTakeFrame(nowMs = 999, lastMs = 0, intervalMs = 1000))
        assertTrue(shouldTakeFrame(nowMs = 1000, lastMs = 0, intervalMs = 1000))
    }

    @Test
    fun `timestamps and output length at 30 fps`() {
        assertEquals(1_000_000L, frameTimestampUs(30))
        assertEquals(3000L, timelapseOutputDurationMs(90))
    }

    @Test
    fun `interval choices`() {
        assertEquals(listOf(500L, 1000L, 2000L, 5000L, 10000L), TIMELAPSE_INTERVALS_MS)
    }

    @Test
    fun `offset converts a crop of a larger frame`() {
        // 6x4 frame: a red 4x2 block at (2, 2) inside black; the crop must see only red.
        val rowStride = 6 * 4
        val frame = solid(6, 4, 0, 0, 0)
        for (y in 2 until 4) for (x in 2 until 6) {
            val i = y * rowStride + x * 4
            frame[i] = 255.toByte()
        }
        val crop = rgbaToI420(frame, 4, 2, rowStride, offset = 2 * rowStride + 2 * 4)
        assertArrayEquals(rgbaToI420(solid(4, 2, 255, 0, 0), 4, 2, 16), crop)
    }

    @Test
    fun `aligned size crops to multiples of 16`() {
        assertEquals(1904 to 1072, alignedEncoderSize(1920 - 15, 1080))
        assertEquals(1920 to 1072, alignedEncoderSize(1920, 1080))
    }
}
