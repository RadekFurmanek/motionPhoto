package cz.furmanek.motionphoto.panorama

import android.view.Surface
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OrientationTest {

    /** Row-major 3×3 whose columns are where the device's X, Y and Z axes point in the world. */
    private fun fromColumns(x: Vec3, y: Vec3, z: Vec3) =
        doubleArrayOf(x.x, y.x, z.x, x.y, y.y, z.y, x.z, y.z, z.z).map { it.toFloat() }.toFloatArray()

    private fun times(a: FloatArray, b: FloatArray) = FloatArray(9) { i ->
        val r = i / 3
        val c = i % 3
        (0 until 3).sumOf { (a[r * 3 + it] * b[it * 3 + c]).toDouble() }.toFloat()
    }

    /** Rotation by [deg] around an axis (counterclockwise seen from its tip). */
    private fun about(axis: Int, deg: Double): FloatArray {
        val c = cos(deg * PI / 180).toFloat()
        val s = sin(deg * PI / 180).toFloat()
        return when (axis) {
            0 -> floatArrayOf(1f, 0f, 0f, 0f, c, -s, 0f, s, c)
            1 -> floatArrayOf(c, 0f, s, 0f, 1f, 0f, -s, 0f, c)
            else -> floatArrayOf(c, -s, 0f, s, c, 0f, 0f, 0f, 1f)
        }
    }

    /** Portrait phone, camera facing north (world +Y), screen up = world up. */
    private val upright = fromColumns(Vec3(1.0, 0.0, 0.0), Vec3(0.0, 0.0, 1.0), Vec3(0.0, -1.0, 0.0))

    private fun assertVec(expected: Vec3, actual: Vec3) {
        assertEquals(expected.x, actual.x, 1e-5)
        assertEquals(expected.y, actual.y, 1e-5)
        assertEquals(expected.z, actual.z, 1e-5)
    }

    private fun deg(rad: Double) = rad * 180 / PI

    @Test
    fun uprightPhoneLooksAtHorizon() {
        val basis = cameraBasis(upright, Surface.ROTATION_0)
        assertVec(Vec3(0.0, 1.0, 0.0), basis.forward)
        assertVec(Vec3(1.0, 0.0, 0.0), basis.right)
        assertVec(Vec3(0.0, 0.0, -1.0), basis.down)
        assertEquals(0.0, headingOf(basis.forward), 1e-6)
        assertEquals(0.0, elevationOf(basis.forward), 1e-6)
    }

    @Test
    fun turnedRightRaisesHeading() {
        // Turning right is clockwise seen from above: negative about world Z.
        val basis = cameraBasis(times(about(2, -30.0), upright), Surface.ROTATION_0)
        assertEquals(30.0, deg(headingOf(basis.forward)), 1e-4)
    }

    @Test
    fun tiltUpRaisesElevation() {
        // Camera up = turning about the device X axis so that −Z rises.
        val rotation = times(upright, about(0, 10.0))
        assertEquals(10.0, deg(elevationOf(cameraBasis(rotation, Surface.ROTATION_0).forward)), 1e-4)
        assertEquals(10f, attitudeOf(rotation).pitchDeg, 1e-3f)
    }

    @Test
    fun rollClockwiseIsPositive() {
        // Clockwise as the user sees the screen: negative about the device Z axis, which points at the user.
        val attitude = attitudeOf(times(upright, about(2, -5.0)))
        assertEquals(5f, attitude.rollDeg, 1e-3f)
        assertEquals(5f, levelErrorDeg(attitude.rollDeg), 1e-3f)
        assertEquals(0f, attitude.pitchDeg, 1e-3f)
    }

    @Test
    fun landscapeLevelHasNoError() {
        val attitude = attitudeOf(times(upright, about(2, 90.0)))
        assertEquals(90f, abs(attitude.rollDeg), 1e-3f)
        assertEquals(0f, levelErrorDeg(attitude.rollDeg), 1e-3f)
    }

    @Test
    fun surfaceRotation90AxesStayPerpendicular() {
        for (rotation in listOf(Surface.ROTATION_0, Surface.ROTATION_90, Surface.ROTATION_180, Surface.ROTATION_270)) {
            val b = cameraBasis(times(upright, about(2, 90.0 * rotation)), rotation)
            assertEquals(0.0, b.forward.dot(b.right), 1e-6)
            assertEquals(0.0, b.forward.dot(b.down), 1e-6)
            assertEquals(0.0, b.right.dot(b.down), 1e-6)
            // A phone turned to match its display rotation still shows an upright, level view.
            assertTrue("rotation $rotation: down ${b.down.z}", b.down.z < -0.999)
        }
    }
}
