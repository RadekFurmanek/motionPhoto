package cz.furmanek.motionphoto.timelapse

/** Interval choices for the time-lapse quick setting. */
val TIMELAPSE_INTERVALS_MS: List<Long> = listOf(500L, 1000L, 2000L, 5000L, 10000L)

/**
 * RGBA (4 bytes per pixel, rows of [rowStride] bytes) to I420: the Y plane, then U, then V at half resolution.
 * [width] and [height] must be even. BT.601 limited range in the usual 8-bit fixed-point form.
 */
fun rgbaToI420(rgba: ByteArray, width: Int, height: Int, rowStride: Int): ByteArray {
    require(width % 2 == 0 && height % 2 == 0) { "I420 needs even dimensions, got ${width}x$height" }
    val ySize = width * height
    val chromaWidth = width / 2
    val out = ByteArray(ySize * 3 / 2)
    val uOffset = ySize
    val vOffset = ySize + ySize / 4
    for (y in 0 until height) {
        val row = y * rowStride
        for (x in 0 until width) {
            val i = row + x * 4
            val r = rgba[i].toInt() and 0xFF
            val g = rgba[i + 1].toInt() and 0xFF
            val b = rgba[i + 2].toInt() and 0xFF
            out[y * width + x] = (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).toByte()
        }
    }
    for (cy in 0 until height / 2) {
        for (cx in 0 until chromaWidth) {
            var r = 0
            var g = 0
            var b = 0
            for (dy in 0..1) for (dx in 0..1) {
                val i = (cy * 2 + dy) * rowStride + (cx * 2 + dx) * 4
                r += rgba[i].toInt() and 0xFF
                g += rgba[i + 1].toInt() and 0xFF
                b += rgba[i + 2].toInt() and 0xFF
            }
            r /= 4
            g /= 4
            b /= 4
            val index = cy * chromaWidth + cx
            out[uOffset + index] = (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).toByte()
            out[vOffset + index] = (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).toByte()
        }
    }
    return out
}

/** Whether a frame arriving at [nowMs] should be kept when the last kept one came at [lastMs] (null: none yet). */
fun shouldTakeFrame(nowMs: Long, lastMs: Long?, intervalMs: Long): Boolean =
    lastMs == null || nowMs - lastMs >= intervalMs

fun frameTimestampUs(index: Int, fps: Int = TIMELAPSE_FPS): Long = index * 1_000_000L / fps

fun timelapseOutputDurationMs(frames: Int, fps: Int = TIMELAPSE_FPS): Long = frames * 1_000L / fps

const val TIMELAPSE_FPS = 30
