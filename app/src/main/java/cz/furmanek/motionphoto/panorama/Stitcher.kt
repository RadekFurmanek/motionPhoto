package cz.furmanek.motionphoto.panorama

import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.tan

/** How frame i+1 sits relative to frame i: its column x shows frame i's column x + [dx], row y shows row y + [dy]. */
data class Shift(val dx: Int, val dy: Int, val confident: Boolean)

private const val COARSE = 4
private const val REFINE_RADIUS = 4
private const val MIN_OVERLAP = 0.2
private const val CONFIDENCE_MARGIN = 0.1
private const val MAX_PANORAMA_WIDTH = 16_000

fun focalLengthPx(focalMm: Float, sensorMm: Float, imagePx: Int): Double = focalMm.toDouble() / sensorMm * imagePx

/**
 * Projects a frame onto a cylinder of radius [focalPx] around the camera, so frames taken while turning
 * line up by a plain horizontal shift.
 */
fun cylindricalWarp(src: Raster, focalPx: Double): Raster {
    val outWidth = (2 * focalPx * atan(src.width / (2 * focalPx))).roundToInt()
    val centreX = (outWidth - 1) / 2.0
    val srcCentreX = (src.width - 1) / 2.0
    val srcCentreY = (src.height - 1) / 2.0
    return Raster(outWidth, src.height, IntArray(outWidth * src.height) { i ->
        val theta = ((i % outWidth) - centreX) / focalPx
        val xs = focalPx * tan(theta) + srcCentreX
        val ys = ((i / outWidth) - srcCentreY) / cos(theta) + srcCentreY
        src.sample(xs, ys)
    })
}

/**
 * Finds where [right] continues [left]: a coarse search on 4× smaller luminance around [expectedDx] (from the
 * gyroscope), refined at full resolution. When no shift stands out (a blank wall), the sensor's [expectedDx] wins.
 */
fun estimateShift(left: Raster, right: Raster, expectedDx: Int, searchX: Int, searchY: Int): Shift {
    val smallLeft = LumaMap.of(left, COARSE)
    val smallRight = LumaMap.of(right, COARSE)
    val candidates = mutableListOf<Triple<Int, Int, Double>>()
    for (dx in (expectedDx - searchX) / COARSE..(expectedDx + searchX) / COARSE) {
        for (dy in -searchY / COARSE..searchY / COARSE) {
            val score = mismatch(smallLeft, smallRight, dx, dy, step = 1) ?: continue
            candidates += Triple(dx, dy, score)
        }
    }
    val best = candidates.minByOrNull { it.third } ?: return Shift(expectedDx, 0, confident = false)
    val runnerUp = candidates
        .filter { abs(it.first - best.first) > 2 || abs(it.second - best.second) > 2 }
        .minOfOrNull { it.third }
        ?: return Shift(expectedDx, 0, confident = false)
    val confident = (runnerUp - best.third) / maxOf(best.third, 1e-6) > CONFIDENCE_MARGIN
    if (!confident) return Shift(expectedDx, 0, confident = false)

    val fullLeft = LumaMap.of(left, 1)
    val fullRight = LumaMap.of(right, 1)
    var refined = Triple(best.first * COARSE, best.second * COARSE, Double.MAX_VALUE)
    for (dx in best.first * COARSE - REFINE_RADIUS..best.first * COARSE + REFINE_RADIUS) {
        for (dy in best.second * COARSE - REFINE_RADIUS..best.second * COARSE + REFINE_RADIUS) {
            val score = mismatch(fullLeft, fullRight, dx, dy, step = 2) ?: continue
            if (score < refined.third) refined = Triple(dx, dy, score)
        }
    }
    return Shift(refined.first, refined.second, confident = true)
}

/**
 * Lays the frames left to right by the cumulative [shifts] (all dx > 0), cross-fading each overlap linearly,
 * then crops to the rows and columns every frame covers. Wider than 16 000 px is scaled down.
 */
fun stitch(frames: List<Raster>, shifts: List<Shift>): Raster {
    require(frames.isNotEmpty() && shifts.size == frames.size - 1) { "Need n frames and n-1 shifts" }
    val xs = IntArray(frames.size)
    val ys = IntArray(frames.size)
    for (i in 1 until frames.size) {
        require(shifts[i - 1].dx > 0) { "Frames must be ordered left to right" }
        xs[i] = xs[i - 1] + shifts[i - 1].dx
        ys[i] = ys[i - 1] + shifts[i - 1].dy
    }
    val top = ys.min()
    val canvasWidth = frames.indices.maxOf { xs[it] + frames[it].width }
    val canvasHeight = frames.indices.maxOf { ys[it] + frames[it].height } - top
    val canvas = IntArray(canvasWidth * canvasHeight)

    frames.forEachIndexed { index, frame ->
        val previousEnd = if (index == 0) xs[0] else xs[index - 1] + frames[index - 1].width
        val overlapStart = xs[index]
        val overlapWidth = (previousEnd - overlapStart).coerceAtLeast(1)
        for (y in 0 until frame.height) {
            val canvasRow = (ys[index] - top + y) * canvasWidth
            for (x in 0 until frame.width) {
                val incoming = frame.pixel(x, y)
                if (incoming.alpha() == 0) continue
                val canvasX = xs[index] + x
                val existing = canvas[canvasRow + canvasX]
                canvas[canvasRow + canvasX] = if (existing.alpha() != 0 && canvasX < previousEnd) {
                    blend(existing, incoming, (canvasX - overlapStart + 0.5) / overlapWidth)
                } else {
                    incoming
                }
            }
        }
    }
    return crop(Raster(canvasWidth, canvasHeight, canvas)).let {
        if (it.width > MAX_PANORAMA_WIDTH) it.scaleToHeight(it.height * MAX_PANORAMA_WIDTH / it.width) else it
    }
}

private fun blend(a: Int, b: Int, t: Double): Int {
    var result = 0xFF shl 24
    for (shift in intArrayOf(16, 8, 0)) {
        val value = (a shr shift and 0xFF) * (1 - t) + (b shr shift and 0xFF) * t
        result = result or (value.roundToInt().coerceIn(0, 255) shl shift)
    }
    return result
}

/** The largest band of rows with no empty pixel, then trims empty columns at the ends. */
private fun crop(image: Raster): Raster {
    val fullRows = BooleanArray(image.height) { y -> (0 until image.width).all { image.pixel(it, y).alpha() != 0 } }
    var bestStart = 0
    var bestLength = 0
    var runStart = 0
    for (y in 0..image.height) {
        if (y < image.height && fullRows[y]) continue
        if (y - runStart > bestLength) {
            bestStart = runStart
            bestLength = y - runStart
        }
        runStart = y + 1
    }
    if (bestLength == 0) {
        // No row is complete (strong drift): keep everything rather than return nothing.
        return image
    }
    return Raster(image.width, bestLength, image.pixels.copyOfRange(bestStart * image.width, (bestStart + bestLength) * image.width))
}

/** Luminance at 1/[factor] resolution; a cell counts only when all its pixels have content. */
private class LumaMap(val width: Int, val height: Int, val luma: IntArray, val valid: BooleanArray) {
    companion object {
        fun of(image: Raster, factor: Int): LumaMap {
            val w = image.width / factor
            val h = image.height / factor
            val luma = IntArray(w * h)
            val valid = BooleanArray(w * h)
            for (cy in 0 until h) for (cx in 0 until w) {
                var sum = 0
                var complete = true
                for (dy in 0 until factor) for (dx in 0 until factor) {
                    val p = image.pixel(cx * factor + dx, cy * factor + dy)
                    if (p.alpha() == 0) complete = false
                    sum += p.luma()
                }
                luma[cy * w + cx] = sum / (factor * factor)
                valid[cy * w + cx] = complete
            }
            return LumaMap(w, h, luma, valid)
        }
    }
}

/** Mean absolute luminance difference with [right] placed at ([dx], [dy]) over [left]; null if they barely overlap. */
private fun mismatch(left: LumaMap, right: LumaMap, dx: Int, dy: Int, step: Int): Double? {
    val xFrom = maxOf(0, -dx)
    val xTo = minOf(right.width, left.width - dx)
    val yFrom = maxOf(0, -dy)
    val yTo = minOf(right.height, left.height - dy)
    if (xTo - xFrom < right.width * MIN_OVERLAP || yTo - yFrom < right.height / 2) return null
    var sum = 0L
    var count = 0
    for (y in yFrom until yTo step step) {
        for (x in xFrom until xTo step step) {
            val r = y * right.width + x
            val l = (y + dy) * left.width + x + dx
            if (!right.valid[r] || !left.valid[l]) continue
            sum += abs(right.luma[r] - left.luma[l])
            count++
        }
    }
    return if (count == 0) null else sum.toDouble() / count
}
