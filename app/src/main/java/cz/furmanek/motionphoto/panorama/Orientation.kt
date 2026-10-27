package cz.furmanek.motionphoto.panorama

import android.view.Surface
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.roundToInt

class Vec3(val x: Double, val y: Double, val z: Double) {
    fun dot(other: Vec3): Double = x * other.x + y * other.y + z * other.z
    operator fun times(factor: Double) = Vec3(x * factor, y * factor, z * factor)
    operator fun plus(other: Vec3) = Vec3(x + other.x, y + other.y, z + other.z)
}

/** Where the back camera looks and which ways an upright frame's columns and rows run, in world axes (Z up). */
class CameraBasis(val forward: Vec3, val right: Vec3, val down: Vec3)

/**
 * The back camera's directions from a device-to-world [rotation] (row-major 3×3, as from
 * `SensorManager.getRotationMatrixFromVector`). The camera looks along the device's −Z; a frame turned upright
 * for [surfaceRotation] has its right and down along the device axes that rotation puts there.
 */
fun cameraBasis(rotation: FloatArray, surfaceRotation: Int): CameraBasis {
    fun world(x: Double, y: Double, z: Double) = Vec3(
        rotation[0] * x + rotation[1] * y + rotation[2] * z,
        rotation[3] * x + rotation[4] * y + rotation[5] * z,
        rotation[6] * x + rotation[7] * y + rotation[8] * z,
    )
    val (right, down) = when (surfaceRotation) {
        Surface.ROTATION_90 -> world(0.0, -1.0, 0.0) to world(-1.0, 0.0, 0.0)
        Surface.ROTATION_180 -> world(-1.0, 0.0, 0.0) to world(0.0, 1.0, 0.0)
        Surface.ROTATION_270 -> world(0.0, 1.0, 0.0) to world(1.0, 0.0, 0.0)
        else -> world(1.0, 0.0, 0.0) to world(0.0, -1.0, 0.0)
    }
    return CameraBasis(world(0.0, 0.0, -1.0), right, down)
}

/** Compass-style heading of [direction] in radians: 0 along world +Y, growing to the right (clockwise from above). */
fun headingOf(direction: Vec3): Double = atan2(direction.x, direction.y)

/** Angle of [direction] above the horizon, in radians. */
fun elevationOf(direction: Vec3): Double = asin(direction.z.coerceIn(-1.0, 1.0))

/** [rollDeg]: the phone turned clockwise in the user's view is positive. [pitchDeg]: the camera looking up is positive. */
class Attitude(val rollDeg: Float, val pitchDeg: Float)

fun attitudeOf(rotation: FloatArray): Attitude {
    // World up in device axes is the matrix's last row.
    val roll = Math.toDegrees(atan2(-rotation[6].toDouble(), rotation[7].toDouble()))
    // The camera's forward (device −Z) in world axes; its height is the third column's Z, negated.
    val pitch = Math.toDegrees(asin((-rotation[8].toDouble()).coerceIn(-1.0, 1.0)))
    return Attitude(roll.toFloat(), pitch.toFloat())
}

/** How far [rollDeg] is from the nearest upright or sideways hold, in degrees. */
fun levelErrorDeg(rollDeg: Float): Float = rollDeg - (rollDeg / 90f).roundToInt() * 90f
