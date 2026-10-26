package cz.furmanek.motionphoto.panorama

import kotlin.math.abs
import kotlin.math.sign

/**
 * Decides when to take the next panorama frame from the compass heading. The first move of at least half
 * a step sets the direction; turning back takes nothing. Headings wrap at ±180°.
 */
class PanoramaProgress(private val stepDeg: Float, private val maxDeg: Float = 360f) {

    private var lastYaw: Float? = null
    private var turned = 0f
    private var lastCapturedAt = 0f
    private var direction = 0f

    var capturedCount = 0
        private set

    /** Degrees covered in the chosen direction. */
    var sweptDeg = 0f
        private set

    val finished: Boolean get() = sweptDeg >= maxDeg - stepDeg

    /** Feeds a new heading (−180..180); true means take a frame now. */
    fun onYaw(yawDeg: Float): Boolean {
        val previous = lastYaw
        lastYaw = yawDeg
        if (previous == null) {
            capturedCount = 1
            return true
        }
        turned += wrap(yawDeg - previous)
        if (direction == 0f && abs(turned) >= stepDeg / 2) direction = sign(turned)
        if (direction == 0f) return false
        sweptDeg = maxOf(sweptDeg, turned * direction)
        if ((turned - lastCapturedAt) * direction < stepDeg) return false
        lastCapturedAt = turned
        capturedCount++
        return true
    }

    /** The heading change to the next frame, for the on-screen target; positive in the turning direction. */
    fun degreesToNext(): Float = if (direction == 0f) stepDeg else stepDeg - (turned - lastCapturedAt) * direction

    private fun wrap(deg: Float): Float = ((deg + 540f) % 360f) - 180f
}

fun isLevel(pitchDeg: Float, toleranceDeg: Float = 6f): Boolean = abs(pitchDeg) <= toleranceDeg
