package cz.furmanek.motionphoto.panorama

import kotlin.math.floor
import kotlin.math.roundToInt

/** An ARGB image; alpha 0 marks pixels with no content (outside a projection, not yet painted). */
class Raster(val width: Int, val height: Int, val pixels: IntArray) {
    init {
        require(pixels.size == width * height) { "Expected ${width * height} pixels, got ${pixels.size}" }
    }

    fun pixel(x: Int, y: Int): Int = pixels[y * width + x]
}

/** Rotates clockwise by a multiple of 90 degrees. */
fun Raster.rotate(degrees: Int): Raster {
    val turns = ((degrees % 360) + 360) % 360
    require(turns % 90 == 0) { "Only multiples of 90 degrees, got $degrees" }
    return when (turns) {
        0 -> Raster(width, height, pixels.copyOf())
        90 -> Raster(height, width, IntArray(pixels.size) { i ->
            val x = i % height
            val y = i / height
            pixel(y, height - 1 - x)
        })
        180 -> Raster(width, height, IntArray(pixels.size) { i -> pixels[pixels.size - 1 - i] })
        else -> Raster(height, width, IntArray(pixels.size) { i ->
            val x = i % height
            val y = i / height
            pixel(width - 1 - y, x)
        })
    }
}

fun Raster.scaleToHeight(targetHeight: Int): Raster {
    if (targetHeight == height) return this
    val targetWidth = (width.toLong() * targetHeight / height).toInt().coerceAtLeast(1)
    val sx = width.toDouble() / targetWidth
    val sy = height.toDouble() / targetHeight
    return Raster(targetWidth, targetHeight, IntArray(targetWidth * targetHeight) { i ->
        // Sample at the centre of the target pixel.
        sample(((i % targetWidth) + 0.5) * sx - 0.5, ((i / targetWidth) + 0.5) * sy - 0.5)
    })
}

/** Bilinear sample at ([x], [y]) in pixel coordinates; transparent outside the image. */
internal fun Raster.sample(x: Double, y: Double): Int {
    if (x < -0.5 || y < -0.5 || x > width - 0.5 || y > height - 0.5) return 0
    val cx = x.coerceIn(0.0, width - 1.0)
    val cy = y.coerceIn(0.0, height - 1.0)
    val x0 = floor(cx).toInt()
    val y0 = floor(cy).toInt()
    val x1 = (x0 + 1).coerceAtMost(width - 1)
    val y1 = (y0 + 1).coerceAtMost(height - 1)
    val fx = cx - x0
    val fy = cy - y0
    val p00 = pixel(x0, y0)
    val p10 = pixel(x1, y0)
    val p01 = pixel(x0, y1)
    val p11 = pixel(x1, y1)
    var result = 0
    for (shift in intArrayOf(24, 16, 8, 0)) {
        val top = (p00 ushr shift and 0xFF) * (1 - fx) + (p10 ushr shift and 0xFF) * fx
        val bottom = (p01 ushr shift and 0xFF) * (1 - fx) + (p11 ushr shift and 0xFF) * fx
        result = result or ((top * (1 - fy) + bottom * fy).roundToInt().coerceIn(0, 255) shl shift)
    }
    return result
}

internal fun Int.alpha(): Int = this ushr 24

internal fun Int.luma(): Int = ((this shr 16 and 0xFF) * 77 + (this shr 8 and 0xFF) * 150 + (this and 0xFF) * 29) shr 8
