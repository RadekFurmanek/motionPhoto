package cz.furmanek.motionphoto.camera

import kotlin.math.ceil
import kotlin.math.pow

/**
 * Zoom ratios for a smooth move from [from] to [to], one per frame, easing out. The ratio changes
 * geometrically, so 1×→2× looks as fast as 5×→10×. The last step is exactly [to].
 */
fun zoomAnimationSteps(from: Float, to: Float, durationMs: Long, frameMs: Long): List<Float> {
    if (from == to || from <= 0f || to <= 0f) return listOf(to)
    val frames = ceil(durationMs.toDouble() / frameMs).toInt().coerceAtLeast(1)
    val ratio = (to / from).toDouble()
    return List(frames) { index ->
        if (index == frames - 1) {
            to
        } else {
            val t = (index + 1).toDouble() / frames
            val eased = 1 - (1 - t).pow(3)
            (from * ratio.pow(eased)).toFloat()
        }
    }
}
