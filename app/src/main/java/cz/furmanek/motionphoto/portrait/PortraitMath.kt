package cz.furmanek.motionphoto.portrait

import cz.furmanek.motionphoto.panorama.Raster
import kotlin.math.max
import kotlin.math.roundToInt

/** The "standard" portrait resolution: a photo is decoded down by powers of two until it fits this many pixels. */
private const val STANDARD_MAX_PIXELS = 12_600_000L

/** Smallest power-of-two decode sample size at which a [width] x [height] image has at most 12.6 MP. */
fun standardSampleSize(width: Int, height: Int): Int {
    var sampleSize = 1
    while (width.toLong() * height > STANDARD_MAX_PIXELS * sampleSize * sampleSize) sampleSize *= 2
    return sampleSize
}

/** Scales down to fit [maxSide] on the longer side, keeping the aspect ratio; never scales up. */
fun fitInside(width: Int, height: Int, maxSide: Int): Pair<Int, Int> {
    val longest = max(width, height)
    if (longest <= maxSide) return width to height
    fun scaled(side: Int) = ((side.toLong() * maxSide + longest / 2) / longest).toInt().coerceAtLeast(1)
    return scaled(width) to scaled(height)
}

/** Whether the confidence [mask] (0..1) marks at least [minFraction] of the frame as foreground. */
fun hasSubject(mask: FloatArray, threshold: Float = 0.5f, minFraction: Float = 0.01f): Boolean {
    if (mask.isEmpty()) return false
    val foreground = mask.count { it >= threshold }
    return foreground.toFloat() / mask.size >= minFraction
}

/** Mixes two ARGB pixels; [alpha] is the confidence of the foreground, so 1 gives [sharp]. The result is opaque. */
fun blend(sharp: Int, blurred: Int, alpha: Float): Int {
    val a = alpha.coerceIn(0f, 1f)
    // Called once per pixel, so no loop over a channel array here.
    val r = mixChannel(sharp ushr 16 and 0xFF, blurred ushr 16 and 0xFF, a)
    val g = mixChannel(sharp ushr 8 and 0xFF, blurred ushr 8 and 0xFF, a)
    val b = mixChannel(sharp and 0xFF, blurred and 0xFF, a)
    return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}

private fun mixChannel(sharp: Int, blurred: Int, alpha: Float): Int =
    (blurred + (sharp - blurred) * alpha).roundToInt().coerceIn(0, 255)

/** Bilinear value of a [maskWidth] x [maskHeight] mask at ([x], [y]) in mask pixels; clamped to the edge outside. */
fun maskAt(mask: FloatArray, maskWidth: Int, maskHeight: Int, x: Float, y: Float): Float {
    val cx = x.coerceIn(0f, maskWidth - 1f)
    val cy = y.coerceIn(0f, maskHeight - 1f)
    val x0 = cx.toInt()
    val y0 = cy.toInt()
    val x1 = (x0 + 1).coerceAtMost(maskWidth - 1)
    val y1 = (y0 + 1).coerceAtMost(maskHeight - 1)
    val fx = cx - x0
    val fy = cy - y0
    val top = mask[y0 * maskWidth + x0] * (1 - fx) + mask[y0 * maskWidth + x1] * fx
    val bottom = mask[y1 * maskWidth + x0] * (1 - fx) + mask[y1 * maskWidth + x1] * fx
    return top * (1 - fy) + bottom * fy
}

/**
 * Blur planes hold values as fixed point with this many fraction bits, so box sums are exact:
 * a flat area stays flat and an empty one stays empty, with no float drift in the running sum.
 */
private const val FRACTION_BITS = 16
private const val ONE = 1 shl FRACTION_BITS

/** Blurs each colour channel with three box passes (close to a Gaussian); edge pixels repeat. The result is opaque. */
fun boxBlur(src: Raster, radius: Int): Raster {
    val out = IntArray(src.pixels.size) { src.pixels[it] or (0xFF shl 24) }
    if (radius <= 0) return Raster(src.width, src.height, out)
    // One plane is reused for all three channels to keep a 12 MP photo to a single extra array.
    val plane = IntArray(src.pixels.size)
    for (shift in intArrayOf(16, 8, 0)) {
        for (i in plane.indices) plane[i] = (src.pixels[i] ushr shift and 0xFF) shl FRACTION_BITS
        blurPlane(plane, src.width, src.height, radius)
        val keep = (0xFF shl shift).inv()
        for (i in plane.indices) out[i] = (out[i] and keep) or (((plane[i] + ONE / 2) shr FRACTION_BITS) shl shift)
    }
    return Raster(src.width, src.height, out)
}

/** The same blur for a 0..1 confidence mask, to feather the edge of the subject. */
fun blurMask(mask: FloatArray, width: Int, height: Int, radius: Int): FloatArray {
    require(mask.size == width * height) { "Expected ${width * height} mask values, got ${mask.size}" }
    if (radius <= 0) return mask.copyOf()
    val plane = IntArray(mask.size) { (mask[it] * ONE).roundToInt() }
    blurPlane(plane, width, height, radius)
    return FloatArray(plane.size) { plane[it].toFloat() / ONE }
}

/** Blurs [plane] in place: every row, then every column, three box passes each. */
private fun blurPlane(plane: IntArray, width: Int, height: Int, radius: Int) {
    val line = IntArray(max(width, height))
    val scratch = IntArray(line.size)
    for (y in 0 until height) {
        System.arraycopy(plane, y * width, line, 0, width)
        blurLine(line, scratch, width, radius)
        System.arraycopy(line, 0, plane, y * width, width)
    }
    for (x in 0 until width) {
        for (y in 0 until height) line[y] = plane[y * width + x]
        blurLine(line, scratch, height, radius)
        for (y in 0 until height) plane[y * width + x] = line[y]
    }
}

/** Three box passes over the first [n] values of [line], leaving the result there; [scratch] is the same size. */
private fun blurLine(line: IntArray, scratch: IntArray, n: Int, radius: Int) {
    boxPass(line, scratch, n, radius)
    boxPass(scratch, line, n, radius)
    boxPass(line, scratch, n, radius)
    System.arraycopy(scratch, 0, line, 0, n)
}

/** Moving average over a window of 2 * [radius] + 1 values, rounded; values beyond either end repeat the end value. */
private fun boxPass(src: IntArray, dst: IntArray, n: Int, radius: Int) {
    val window = 2 * radius + 1
    var sum = 0L
    for (i in -radius..radius) sum += src[i.coerceIn(0, n - 1)]
    for (i in 0 until n) {
        dst[i] = ((sum + window / 2) / window).toInt()
        sum += src[(i + radius + 1).coerceAtMost(n - 1)] - src[(i - radius).coerceAtLeast(0)]
    }
}
