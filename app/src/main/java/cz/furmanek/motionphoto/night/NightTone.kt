package cz.furmanek.motionphoto.night

/** How much the darkest tones are lifted on top of the burst's exposure; white stays white. */
const val SHADOW_GAIN = 2f

/** Most a pixel's colour is scaled along with its brightness: more would bring out the chroma noise. */
private const val MAX_CHROMA_SCALE = 2f

/**
 * Brightens [x] (0..1) by up to [gain] in the shadows and less towards white, which stays 1: an extended
 * Reinhard curve with its white point at [gain]. Never darkens for gain ≥ 1.
 */
fun toneCurve(x: Float, gain: Float): Float {
    val g = x * gain
    return g * (1f + g / (gain * gain)) / (1f + g)
}

/** Full-range BT.601 (as in JPEG) to opaque ARGB. */
fun yuvToArgb(y: Int, u: Int, v: Int): Int {
    val cb = u - 128
    val cr = v - 128
    val r = (y + 1.402f * cr).toInt().coerceIn(0, 255)
    val g = (y - 0.344136f * cb - 0.714136f * cr).toInt().coerceIn(0, 255)
    val b = (y + 1.772f * cb).toInt().coerceIn(0, 255)
    return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}

/**
 * Turns the merged [frame] into ARGB rows, brightened by [toneCurve] at [SHADOW_GAIN] × [digitalGain], the colour
 * scaled with the brightness so it does not wash out. Hands each row to [row] (reused between calls).
 */
fun renderNight(frame: YuvFrame, digitalGain: Float, row: (y: Int, pixels: IntArray) -> Unit) {
    val gain = SHADOW_GAIN * digitalGain
    val lut = IntArray(256) { (toneCurve(it / 255f, gain) * 255f + 0.5f).toInt().coerceIn(0, 255) }
    val chromaScale = FloatArray(256) { if (it == 0) 1f else (lut[it].toFloat() / it).coerceIn(1f, MAX_CHROMA_SCALE) }
    val width = frame.width
    val chromaWidth = frame.chromaWidth
    val pixels = IntArray(width)
    for (y in 0 until frame.height) {
        val cy = minOf(y / 2, frame.chromaHeight - 1)
        for (x in 0 until width) {
            val lum = frame.y[y * width + x].toInt() and 0xFF
            val c = cy * chromaWidth + minOf(x / 2, chromaWidth - 1)
            val scale = chromaScale[lum]
            val u = 128 + ((frame.u[c].toInt() and 0xFF) - 128) * scale
            val v = 128 + ((frame.v[c].toInt() and 0xFF) - 128) * scale
            pixels[x] = yuvToArgb(lut[lum], u.toInt(), v.toInt())
        }
        row(y, pixels)
    }
}
