package cz.furmanek.motionphoto.night

import kotlin.math.roundToInt

/** The whole burst must fit in this time: the user holds the phone still for at most 2 s. */
const val BURST_BUDGET_NS = 2_000_000_000L

/** Longest single frame held steady (1/4 s), and while the phone moves (1/8 s). */
const val MAX_FRAME_STEADY_NS = 250_000_000L
const val MAX_FRAME_MOVING_NS = 125_000_000L

const val MIN_FRAMES = 6
const val MAX_FRAMES = 15

/** Frames of a burst on auto exposure, when the camera cannot be set by hand. */
const val AUTO_FRAMES = 8
private const val AUTO_FRAME_ESTIMATE_MS = 100L

/** Each frame gets this much more light than the preview's auto exposure; the average then removes the noise. */
const val NIGHT_BRIGHTEN = 2f

/** Light the sensor could not reach (ISO at its maximum) is made up digitally, up to this much. */
private const val MAX_DIGITAL_GAIN = 4f

/**
 * What the camera allows by hand: [manual] (MANUAL_SENSOR), and the exposure time (ns, already capped by the
 * longest frame duration) and ISO ranges; both null without manual control.
 */
data class NightSensor(val manual: Boolean, val exposureNs: LongRange?, val iso: IntRange?)

/** A night burst: [frames] frames of [exposureNs] at [iso] (both null: auto exposure), then [digitalGain]. */
data class NightPlan(val frames: Int, val exposureNs: Long?, val iso: Int?, val digitalGain: Float) {
    /** How long the user holds still. */
    val durationMs: Long get() = frames * (exposureNs?.let { it / 1_000_000L } ?: AUTO_FRAME_ESTIMATE_MS)
}

/**
 * Splits [NIGHT_BRIGHTEN] times the light of the preview's auto exposure ([aeExposureNs] at [aeIso]) into
 * frames as long as the hand allows ([steady]), the rest by ISO, and as many frames as fit in [BURST_BUDGET_NS].
 */
fun planNight(aeExposureNs: Long, aeIso: Int, sensor: NightSensor, steady: Boolean): NightPlan {
    val exposureRange = sensor.exposureNs
    val isoRange = sensor.iso
    if (!sensor.manual || exposureRange == null || isoRange == null) return NightPlan(AUTO_FRAMES, null, null, 1f)
    val target = aeExposureNs.toDouble() * aeIso * NIGHT_BRIGHTEN
    val longest = minOf(if (steady) MAX_FRAME_STEADY_NS else MAX_FRAME_MOVING_NS, exposureRange.last)
    val exposure = minOf(longest.toDouble(), target / isoRange.first).toLong().coerceIn(exposureRange.first, longest)
    val iso = (target / exposure).roundToInt().coerceIn(isoRange.first, isoRange.last)
    val gain = (target / (exposure.toDouble() * iso)).toFloat().coerceIn(1f, MAX_DIGITAL_GAIN)
    val frames = (BURST_BUDGET_NS / exposure).toInt().coerceIn(MIN_FRAMES, MAX_FRAMES)
    return NightPlan(frames, exposure, iso, gain)
}
