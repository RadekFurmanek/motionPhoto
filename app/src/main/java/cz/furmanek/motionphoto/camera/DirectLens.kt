package cz.furmanek.motionphoto.camera

import kotlin.math.roundToInt

/** A hidden lens counts as ultra-wide when its field of view is at least this much wider than the default one's. */
private const val ULTRA_WIDE_FACTOR = 1.3f

/** Field of view as sensor width over focal length; 0 when either is unknown. */
private fun LensInfo.fov(): Float = if (focalMm > 0f && sensorWidthMm > 0f) sensorWidthMm / focalMm else 0f

/** The hidden back lens that sees clearly wider than the default camera [defaultCameraId], if any. */
fun ultraWideLens(lenses: List<LensInfo>, defaultCameraId: String?): LensInfo? {
    val main = lenses.firstOrNull { it.cameraId == defaultCameraId && it.physicalId == null } ?: return null
    if (main.fov() <= 0f) return null
    return lenses
        .filter { it.hidden && it.back && it.fov() >= main.fov() * ULTRA_WIDE_FACTOR }
        .maxByOrNull { it.fov() }
}

/** The zoom [lens] shows at relative to [main], as the chip labels it: one decimal. */
fun zoomLabelRatio(lens: LensInfo, main: LensInfo): Float = (main.fov() / lens.fov() * 10).roundToInt() / 10f

/**
 * The back camera's JPEG rotation: the sensor's mounting plus how far the phone is turned clockwise, which is the
 * opposite of the display rotation [surfaceRotation] (Surface.ROTATION_*).
 */
fun jpegOrientation(sensorOrientation: Int, surfaceRotation: Int): Int {
    val deviceDegrees = (360 - surfaceRotation * 90) % 360
    return (sensorOrientation + deviceDegrees) % 360
}

/**
 * A tap at ([u], [v]) of the upright portrait preview (0..1 each) in the sensor's own coordinates (0..1 each).
 * The preview shows the sensor image turned clockwise by [sensorOrientation].
 */
fun tapToSensor(u: Float, v: Float, sensorOrientation: Int): Pair<Float, Float> = when (sensorOrientation) {
    90 -> v to 1f - u
    180 -> 1f - u to 1f - v
    270 -> 1f - v to u
    else -> u to v
}

/** The centred crop of a [activeWidth]×[activeHeight] sensor that zooms by [zoom]: left, top, right, bottom. */
fun cropRegion(activeWidth: Int, activeHeight: Int, zoom: Float): IntArray {
    val width = (activeWidth / zoom.coerceAtLeast(1f)).roundToInt()
    val height = (activeHeight / zoom.coerceAtLeast(1f)).roundToInt()
    val left = (activeWidth - width) / 2
    val top = (activeHeight - height) / 2
    return intArrayOf(left, top, left + width, top + height)
}

/** The largest 4:3 size (landscape, as cameras list them) whose longer side is at most [maxLong]. */
fun largest43(sizes: List<Pair<Int, Int>>, maxLong: Int = Int.MAX_VALUE): Pair<Int, Int>? = sizes
    .filter { (w, h) -> w * 3 == h * 4 && maxOf(w, h) <= maxLong }
    .maxByOrNull { (w, h) -> w.toLong() * h }
