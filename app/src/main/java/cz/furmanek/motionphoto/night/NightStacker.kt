package cz.furmanek.motionphoto.night

import kotlin.math.abs

/** One YUV 4:2:0 frame, planes packed without padding: full-size [y], half-size [u] and [v]. */
class YuvFrame(val width: Int, val height: Int) {
    val chromaWidth = width / 2
    val chromaHeight = height / 2
    val y = ByteArray(width * height)
    val u = ByteArray(chromaWidth * chromaHeight)
    val v = ByteArray(chromaWidth * chromaHeight)
}

/** A grey image at one level of a pyramid. */
private class Level(val width: Int, val height: Int, val pixels: ByteArray) {
    operator fun get(x: Int, y: Int) = pixels[y * width + x].toInt() and 0xFF

    fun half(): Level {
        val w = width / 2
        val h = height / 2
        val out = ByteArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val sum = get(2 * x, 2 * y) + get(2 * x + 1, 2 * y) + get(2 * x, 2 * y + 1) + get(2 * x + 1, 2 * y + 1)
            out[y * w + x] = ((sum + 2) / 4).toByte()
        }
        return Level(w, h, out)
    }
}

/**
 * Merges a night burst frame by frame, so only the running sums stay in memory. The first frame is the
 * reference; each later one is aligned to it by a shift (coarse to fine over a pyramid), dropped when it is
 * blurred or slid too far, and added tile by tile with less weight where it differs from the reference (something
 * moved there), so moving people and cars leave no ghosts.
 */
class NightStacker(private val width: Int, private val height: Int) {

    private val sumY = IntArray(width * height)
    private val sumU = IntArray((width / 2) * (height / 2))
    private val sumV = IntArray((width / 2) * (height / 2))
    private val tilesX = (width + TILE - 1) / TILE
    private val tilesY = (height + TILE - 1) / TILE
    private val tileWeight = IntArray(tilesX * tilesY)

    private var reference: List<Level>? = null
    private var referenceSharpness = 0f

    /** Frames merged so far. */
    var frames = 0
        private set

    /** Where the last frame added sat against the reference: frame (x, y) shows reference (x + dx, y + dy). */
    var lastShift: Pair<Int, Int> = 0 to 0
        private set

    /** Merges [frame]; false when it was dropped (blurred, or slid further than a hand moves in a burst). */
    fun add(frame: YuvFrame): Boolean {
        require(frame.width == width && frame.height == height) { "frame ${frame.width}×${frame.height}" }
        val pyramid = pyramid(frame.y)
        val sharpness = sharpness(pyramid[1])
        val reference = reference
        if (reference == null) {
            // The reference keeps its own copy of the full-size luma: the caller reuses the frame's buffers.
            this.reference = listOf(Level(width, height, frame.y.copyOf())) + pyramid.drop(1)
            referenceSharpness = sharpness
            lastShift = 0 to 0
            accumulate(frame, 0, 0, FloatArray(tileWeight.size) { 1f })
            frames++
            return true
        }
        if (sharpness < referenceSharpness * KEEP_SHARPNESS) return false
        val (dx, dy) = align(pyramid, reference) ?: return false
        lastShift = dx to dy
        accumulate(frame, dx, dy, tileWeights(pyramid[TILE_LEVEL], reference[TILE_LEVEL], dx, dy))
        frames++
        return true
    }

    /** The weighted average of the frames merged; at least one must have been added. */
    fun result(): YuvFrame {
        check(frames > 0) { "no frames" }
        val out = YuvFrame(width, height)
        for (y in 0 until height) {
            val tileRow = (y / TILE) * tilesX
            for (x in 0 until width) {
                val w = tileWeight[tileRow + x / TILE]
                out.y[y * width + x] = ((sumY[y * width + x] + w / 2) / w).toByte()
            }
        }
        val cw = width / 2
        for (y in 0 until height / 2) {
            val tileRow = (y * 2 / TILE) * tilesX
            for (x in 0 until cw) {
                val w = tileWeight[tileRow + x * 2 / TILE]
                out.u[y * cw + x] = ((sumU[y * cw + x] + w / 2) / w).toByte()
                out.v[y * cw + x] = ((sumV[y * cw + x] + w / 2) / w).toByte()
            }
        }
        return out
    }

    private fun pyramid(luma: ByteArray): List<Level> {
        val levels = mutableListOf(Level(width, height, luma))
        // Down to the search level: the coarsest at least SEARCH_WIDTH wide (and the tile check's level).
        while (levels.size <= TILE_LEVEL || levels.last().width / 2 >= SEARCH_WIDTH) {
            levels += levels.last().half()
        }
        return levels
    }

    /** Mean absolute gradient: a blurred (shaken) frame has less of it. */
    private fun sharpness(level: Level): Float {
        var sum = 0L
        var n = 0
        val step = sampleStep(level)
        for (y in 1 until level.height step step) for (x in 1 until level.width step step) {
            sum += abs(level[x, y] - level[x - 1, y]) + abs(level[x, y] - level[x, y - 1])
            n++
        }
        return if (n == 0) 0f else sum.toFloat() / n
    }

    /**
     * The shift of [frame] against [reference]: searched over every shift a hand makes ([MAX_SHIFT_DIVISOR]) on a
     * level still detailed enough not to mistake a repeating pattern for the shift, then refined by ±1 per level.
     * Null when the best match sits at the edge of the search: the frame slid further than that.
     */
    private fun align(frame: List<Level>, reference: List<Level>): Pair<Int, Int>? {
        var dx = 0
        var dy = 0
        val search = frame.indexOfLast { it.width >= SEARCH_WIDTH }.coerceAtLeast(0)
        for (level in search downTo 0) {
            val radius = if (level == search) maxOf(2, width / MAX_SHIFT_DIVISOR shr level) else 1
            if (level != search) {
                dx *= 2
                dy *= 2
            }
            var best = Long.MAX_VALUE
            var bestX = dx
            var bestY = dy
            for (sy in dy - radius..dy + radius) for (sx in dx - radius..dx + radius) {
                val cost = difference(frame[level], reference[level], sx, sy)
                if (cost < best) {
                    best = cost
                    bestX = sx
                    bestY = sy
                }
            }
            if (level == search && (abs(bestX) == radius || abs(bestY) == radius)) return null
            dx = bestX
            dy = bestY
        }
        return dx to dy
    }

    /** Mean absolute difference (×1024) of frame (x, y) against reference (x + dx, y + dy), over their overlap. */
    private fun difference(frame: Level, reference: Level, dx: Int, dy: Int): Long {
        val x0 = maxOf(0, -dx)
        val y0 = maxOf(0, -dy)
        val x1 = minOf(frame.width, reference.width - dx)
        val y1 = minOf(frame.height, reference.height - dy)
        if (x1 - x0 < frame.width / 2 || y1 - y0 < frame.height / 2) return Long.MAX_VALUE
        val step = sampleStep(frame)
        var sum = 0L
        var n = 0
        for (y in y0 until y1 step step) for (x in x0 until x1 step step) {
            sum += abs(frame[x, y] - reference[x + dx, y + dy])
            n++
        }
        return sum * 1024 / n
    }

    private fun sampleStep(level: Level) = maxOf(1, kotlin.math.ceil(kotlin.math.sqrt(level.width.toDouble() * level.height / SAMPLES)).toInt())

    /** Per tile: 1 where the aligned frame matches the reference, falling to 0 where it differs (motion). */
    private fun tileWeights(frame: Level, reference: Level, dx: Int, dy: Int): FloatArray {
        val scale = 1 shl TILE_LEVEL
        val tile = TILE / scale
        val sx = Math.floorDiv(dx, scale)
        val sy = Math.floorDiv(dy, scale)
        return FloatArray(tilesX * tilesY) { index ->
            val tx = index % tilesX
            val ty = index / tilesX
            var sum = 0
            var n = 0
            for (y in ty * tile until minOf((ty + 1) * tile, frame.height)) {
                // Reference pixels the frame shows here, kept inside the reference.
                val ry = (y + sy).coerceIn(0, reference.height - 1)
                for (x in tx * tile until minOf((tx + 1) * tile, frame.width)) {
                    sum += abs(frame[x, y] - reference[(x + sx).coerceIn(0, reference.width - 1), ry])
                    n++
                }
            }
            val diff = if (n == 0) 0f else sum.toFloat() / n
            ((GHOST_DIFF_MAX - diff) / (GHOST_DIFF_MAX - GHOST_DIFF_MIN)).coerceIn(0f, 1f)
        }
    }

    /** Adds [frame] at reference coordinates: reference (x, y) takes frame (x − dx, y − dy). */
    private fun accumulate(frame: YuvFrame, dx: Int, dy: Int, weights: FloatArray) {
        val w = IntArray(weights.size) { (weights[it] * WEIGHT_ONE + 0.5f).toInt() }
        for (t in w.indices) tileWeight[t] += w[t]
        for (y in 0 until height) {
            val src = (y - dy).coerceIn(0, height - 1) * width
            val tileRow = (y / TILE) * tilesX
            val dst = y * width
            for (x in 0 until width) {
                val weight = w[tileRow + x / TILE]
                if (weight == 0) continue
                sumY[dst + x] += weight * (frame.y[src + (x - dx).coerceIn(0, width - 1)].toInt() and 0xFF)
            }
        }
        val cw = width / 2
        val ch = height / 2
        val cdx = Math.floorDiv(dx + 1, 2)
        val cdy = Math.floorDiv(dy + 1, 2)
        for (y in 0 until ch) {
            val src = (y - cdy).coerceIn(0, ch - 1) * cw
            val tileRow = (y * 2 / TILE) * tilesX
            val dst = y * cw
            for (x in 0 until cw) {
                val weight = w[tileRow + x * 2 / TILE]
                if (weight == 0) continue
                val s = src + (x - cdx).coerceIn(0, cw - 1)
                sumU[dst + x] += weight * (frame.u[s].toInt() and 0xFF)
                sumV[dst + x] += weight * (frame.v[s].toInt() and 0xFF)
            }
        }
    }

    private companion object {
        /** Merge tiles, in full-size pixels; the motion check runs on [TILE_LEVEL]. */
        const val TILE = 32
        const val TILE_LEVEL = 2
        const val WEIGHT_ONE = 16

        /** Mean difference (0..255, on [TILE_LEVEL]) up to which a tile counts fully, and from which not at all. */
        const val GHOST_DIFF_MIN = 10f
        const val GHOST_DIFF_MAX = 30f

        const val KEEP_SHARPNESS = 0.5f

        /** A hand moves the picture by up to 1/16 of its width during a burst. */
        const val MAX_SHIFT_DIVISOR = 16
        const val SEARCH_WIDTH = 256
        const val SAMPLES = 4_096
    }
}

/**
 * Runs the merge and render once on a small made-up burst, so the runtime has compiled them before a real burst:
 * cold, the first full-size frame takes long enough for the next ones to be skipped.
 */
fun warmUpNight() {
    val width = 640
    val height = 480
    val stacker = NightStacker(width, height)
    repeat(3) { k ->
        val frame = YuvFrame(width, height)
        for (i in frame.y.indices) frame.y[i] = ((i % width) / 3 + (i / width) / 2 + k * 7).toByte()
        frame.u.fill(128.toByte())
        frame.v.fill(128.toByte())
        stacker.add(frame)
    }
    renderNight(stacker.result(), 1f) { _, _ -> }
}
