package cz.furmanek.motionphoto.panorama

import kotlin.math.abs
import kotlin.math.roundToInt

/** How frame i+1 sits relative to frame i: its column x shows frame i's column x + [dx], row y shows row y + [dy]. */
data class Shift(val dx: Int, val dy: Int, val confident: Boolean)

private const val COARSE = 4
private const val REFINE_RADIUS = 4
private const val MIN_OVERLAP = 0.2
private const val CONFIDENCE_MARGIN = 0.1

fun focalLengthPx(focalMm: Float, sensorMm: Float, imagePx: Int): Double = focalMm.toDouble() / sensorMm * imagePx

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
