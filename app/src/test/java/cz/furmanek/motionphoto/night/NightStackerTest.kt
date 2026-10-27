package cz.furmanek.motionphoto.night

import kotlin.math.abs
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NightStackerTest {

    private val width = 256
    private val height = 192

    /** A textured scene: smooth blobs plus edges, so shifts are measurable. */
    private fun scene(x: Int, y: Int): Int {
        val v = 128 + 60 * kotlin.math.sin(x / 9.0) * kotlin.math.cos(y / 7.0) + if ((x / 24 + y / 24) % 2 == 0) 30 else -30
        return v.toInt().coerceIn(0, 255)
    }

    private fun frame(dx: Int, dy: Int, noise: Int = 0, seed: Int = 1, patch: Boolean = false): YuvFrame {
        val random = Random(seed)
        val f = YuvFrame(width, height)
        for (y in 0 until height) for (x in 0 until width) {
            var v = scene(x + dx, y + dy)
            if (patch && x in 100..140 && y in 60..100) v = 250
            if (noise > 0) v += random.nextInt(-noise, noise + 1)
            f.y[y * width + x] = v.coerceIn(0, 255).toByte()
        }
        for (i in f.u.indices) {
            f.u[i] = 140.toByte()
            f.v[i] = 110.toByte()
        }
        return f
    }

    private fun YuvFrame.lum(x: Int, y: Int) = this.y[y * width + x].toInt() and 0xFF

    @Test
    fun findsTheShiftBetweenFrames() {
        val stacker = NightStacker(width, height)
        stacker.add(frame(0, 0))
        assertTrue(stacker.add(frame(5, -3)))
        assertEquals(5 to -3, stacker.lastShift)
    }

    @Test
    fun alignedAverageIsSharpAndLessNoisy() {
        val stacker = NightStacker(width, height)
        val shifts = listOf(0 to 0, 3 to 1, -2 to 4, 6 to -2, 1 to -5, -4 to -1)
        shifts.forEachIndexed { i, (dx, dy) -> assertTrue(stacker.add(frame(dx, dy, noise = 30, seed = i))) }
        val result = stacker.result()
        var errorSum = 0.0
        var noisyError = 0.0
        val single = frame(0, 0, noise = 30, seed = 0)
        var n = 0
        for (y in 20 until height - 20) for (x in 20 until width - 20) {
            errorSum += abs(result.lum(x, y) - scene(x, y))
            noisyError += abs(single.lum(x, y) - scene(x, y))
            n++
        }
        // Six frames cut noise by about √6; a misaligned stack would blur the edges and miss far more.
        assertTrue("stacked error ${errorSum / n} vs single ${noisyError / n}", errorSum / n < noisyError / n * 0.6)
    }

    @Test
    fun aMovedObjectLeavesNoGhost() {
        val stacker = NightStacker(width, height)
        stacker.add(frame(0, 0))
        repeat(4) { stacker.add(frame(0, 0, patch = true)) }
        val result = stacker.result()
        // The reference has no patch: frames that differ there count for (almost) nothing.
        assertEquals(scene(120, 80).toDouble(), result.lum(120, 80).toDouble(), 12.0)
    }

    @Test
    fun aBlurredFrameIsDropped() {
        val stacker = NightStacker(width, height)
        stacker.add(frame(0, 0))
        val flat = YuvFrame(width, height).apply { y.fill(128.toByte()); u.fill(128.toByte()); v.fill(128.toByte()) }
        assertFalse(stacker.add(flat))
        assertEquals(1, stacker.frames)
    }

    @Test
    fun chromaIsCarriedThrough() {
        val stacker = NightStacker(width, height)
        stacker.add(frame(0, 0))
        stacker.add(frame(2, 2))
        val result = stacker.result()
        assertEquals(140, result.u[10].toInt() and 0xFF)
        assertEquals(110, result.v[10].toInt() and 0xFF)
    }
}
