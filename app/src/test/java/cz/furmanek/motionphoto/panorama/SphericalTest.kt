package cz.furmanek.motionphoto.panorama

import android.view.Surface
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SphericalTest {

    /** A smooth texture over the sphere, by heading and elevation (radians). */
    private fun world(heading: Double, elevation: Double): Int {
        fun channel(k: Int): Int {
            var v = 128.0
            for (f in 1..4) v += 25 * sin(heading * f * (7 + k) + k) * cos(elevation * f * (5 + k) + f)
            return v.roundToInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (channel(0) shl 16) or (channel(1) shl 8) or channel(2)
    }

    private fun flat(@Suppress("UNUSED_PARAMETER") heading: Double, @Suppress("UNUSED_PARAMETER") elevation: Double) = 0xFFC0C0C0.toInt()

    /** Device-to-world matrix of a portrait phone whose camera points at [headingDeg] and [pitchDeg], rolled [rollDeg]. */
    private fun pose(headingDeg: Double, pitchDeg: Double = 0.0, rollDeg: Double = 0.0): FloatArray {
        val h = Math.toRadians(headingDeg)
        val p = Math.toRadians(pitchDeg)
        val r = Math.toRadians(rollDeg)
        val forward = Vec3(cos(p) * sin(h), cos(p) * cos(h), sin(p))
        val right0 = Vec3(cos(h), -sin(h), 0.0)
        val up0 = Vec3(-sin(p) * sin(h), -sin(p) * cos(h), cos(p))
        // Rolled clockwise in the user's view: right dips, up leans right.
        val right = right0 * cos(r) + up0 * -sin(r)
        val up = up0 * cos(r) + right0 * sin(r)
        val z = forward * -1.0
        return floatArrayOf(
            right.x.toFloat(), up.x.toFloat(), z.x.toFloat(),
            right.y.toFloat(), up.y.toFloat(), z.y.toFloat(),
            right.z.toFloat(), up.z.toFloat(), z.z.toFloat(),
        )
    }

    /** What a pinhole camera of [focalPx] at [rotation] sees of [scene]. */
    private fun render(scene: (Double, Double) -> Int, rotation: FloatArray, width: Int, height: Int, focalPx: Double): Raster {
        val b = cameraBasis(rotation, Surface.ROTATION_0)
        return Raster(width, height, IntArray(width * height) { i ->
            val u = i % width - (width - 1) / 2.0
            val v = i / width - (height - 1) / 2.0
            val d = b.forward * focalPx + b.right * u + b.down * v
            val n = Math.sqrt(d.dot(d))
            scene(atan2(d.x, d.y), asin(d.z / n))
        })
    }

    private fun meanDiff(a: Int, b: Int): Int =
        intArrayOf(16, 8, 0).sumOf { abs((a shr it and 0xFF) - (b shr it and 0xFF)) } / 3

    @Test
    fun tileCentreShowsFrameCentre() {
        val frame = Raster(101, 81, IntArray(101 * 81) { if (it == 40 * 101 + 50) -1 else 0xFF000000.toInt() })
        val grid = SphereGrid(focalPx = 100.0, topElevation = 0.5, height = 100)
        val tile = projectTile(frame, cameraBasis(pose(0.0), Surface.ROTATION_0), 0.0, grid, width = 120)
        val brightest = tile.pixels.indices.maxBy { tile.pixels[it] and 0xFF }
        assertEquals(60.0, (brightest % 120).toDouble(), 1.0)
        assertEquals(50.0, (brightest / 120).toDouble(), 1.0)
    }

    @Test
    fun neighbourTilesDifferByHeadingShift() {
        val f = 300.0
        val grid = SphereGrid(f, topElevation = 0.6, height = 360)
        val tiles = listOf(0.0, 20.0).map { h ->
            projectTile(render(::world, pose(h), 320, 400, f), cameraBasis(pose(h), Surface.ROTATION_0), Math.toRadians(h), grid, 420)
        }
        val expected = (Math.toRadians(20.0) * f).roundToInt()
        val shift = estimateShift(tiles[0], tiles[1], expected + 7, searchX = 16, searchY = 8)
        assertTrue(shift.confident)
        assertEquals(expected.toDouble(), shift.dx.toDouble(), 2.0)
        assertEquals(0.0, shift.dy.toDouble(), 1.0)
    }

    @Test
    fun seamAvoidsMovingObject() {
        val f = 300.0
        val grid = SphereGrid(f, topElevation = 0.6, height = 360)
        val basis = cameraBasis(pose(0.0), Surface.ROTATION_0)
        val left = projectTile(render(::world, pose(0.0), 320, 400, f), basis, 0.0, grid, 420)
        val canvas = Raster(700, 360, IntArray(700 * 360))
        composeWithSeam(canvas, left, 0, 0)
        // The next tile overlaps from column 200; a dark object stands in the left part of the overlap.
        val right = Raster(420, 360, IntArray(420 * 360) { i ->
            val x = i % 420 + 200
            val y = i / 420
            val base = canvas.pixels[y * 700 + minOf(x, 699)]
            if (base.alpha() == 0) world(Math.toRadians(0.0) + (x + 0.5 - 210) / f, 0.6 - (y + 0.5) / f)
            else if (i % 420 in 20..60) 0xFF000000.toInt() else base
        })
        composeWithSeam(canvas, right, 200, 0)
        var dark = 0
        for (y in 0 until 360) for (x in 220..260) {
            val p = canvas.pixels[y * 700 + x]
            if (p.alpha() != 0 && (p and 0xFF) < 10 && (p shr 8 and 0xFF) < 10) dark++
        }
        assertEquals(0, dark)
    }

    private fun frames(scene: (Double, Double) -> Int, headings: List<Double>, f: Double, rolls: List<Double> = headings.map { 0.0 }) =
        headings.mapIndexed { i, h ->
            val rotation = pose(h, rollDeg = rolls[i])
            OrientedFrame(rotation, Surface.ROTATION_0) { render(scene, rotation, 240, 320, f) }
        }

    @Test
    fun noGhostOnStaticScene() = runTest {
        val f = 250.0
        val panorama = stitchFrames(frames(::world, listOf(0.0, 35.0, 70.0, 105.0), f, rolls = listOf(0.0, 3.0, -2.0, 0.0)), f) {}
        val image = panorama.image
        assertTrue("width ${image.width}", image.width > (Math.toRadians(105.0) * f).toInt())
        var sum = 0L
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val expected = world(panorama.headingAtLeft + (x + 0.5) / f, panorama.elevationAtTop - (y + 0.5) / f)
            sum += meanDiff(image.pixel(x, y), expected)
        }
        val mean = sum.toDouble() / (image.width * image.height)
        assertTrue("mean difference $mean", mean < 6.0)
    }

    @Test
    fun fullTurnIsNotWiderThanFullPano() = runTest {
        val f = 150.0
        val panorama = stitchFrames(frames(::world, (0..10).map { it * 38.0 }, f), f) {}
        assertTrue(panorama.image.width <= panorama.fullWidth)
        assertEquals(0, panorama.left)
    }

    @Test
    fun gpanoNumbersConsistent() = runTest {
        val f = 250.0
        val panorama = stitchFrames(frames(::world, listOf(0.0, 35.0, 70.0), f), f) {}
        assertEquals((2 * PI * f).roundToInt(), panorama.fullWidth)
        assertEquals(panorama.fullWidth / 2, panorama.fullHeight)
        assertEquals((panorama.fullHeight / 2.0 - panorama.elevationAtTop * f).roundToInt(), panorama.top)
        assertEquals((panorama.fullWidth - panorama.image.width) / 2, panorama.left)
        // Level frames: the horizon sits inside the image, near its middle.
        val horizonRow = panorama.fullHeight / 2 - panorama.top
        assertTrue(horizonRow in panorama.image.height / 4..panorama.image.height * 3 / 4)
    }

    @Test
    fun blankWallFallsBackToSensor() = runTest {
        val f = 250.0
        val panorama = stitchFrames(frames(::flat, listOf(0.0, 35.0, 70.0), f), f) {}
        val span = (Math.toRadians(70.0) * f).roundToInt()
        assertTrue("width ${panorama.image.width}", panorama.image.width >= span)
        assertTrue("width ${panorama.image.width}", panorama.image.width <= span + 2 * 240)
    }
}
