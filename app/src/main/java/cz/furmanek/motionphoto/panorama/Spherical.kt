package cz.furmanek.motionphoto.panorama

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

const val SEAM_FEATHER_PX = 32
private const val SEARCH_X_FRACTION = 0.04
private const val SEARCH_Y = 16
private const val MARGIN_Y = 48
private const val INVALID_COST = 255
private const val CENTRE_PULL = 8

/**
 * Rows of the equirectangular panorama: row y looks [topElevation] − (y + 0.5) / [focalPx] radians above the
 * horizon. Columns work the same way with the heading, so one pixel is one focal-length radian step both ways.
 */
class SphereGrid(val focalPx: Double, val topElevation: Double, val height: Int)

/**
 * [frame] as seen on the panorama's sphere: column x looks at heading [centreHeading] + (x + 0.5 − [width]/2) /
 * focalPx, row y as [grid] says. Pixels the frame does not cover are transparent.
 */
fun projectTile(frame: Raster, basis: CameraBasis, centreHeading: Double, grid: SphereGrid, width: Int): Raster {
    val f = grid.focalPx
    val cx = (frame.width - 1) / 2.0
    val cy = (frame.height - 1) / 2.0
    val sinH = DoubleArray(width) { sin(centreHeading + (it + 0.5 - width / 2.0) / f) }
    val cosH = DoubleArray(width) { cos(centreHeading + (it + 0.5 - width / 2.0) / f) }
    val pixels = IntArray(width * grid.height)
    for (y in 0 until grid.height) {
        val elevation = grid.topElevation - (y + 0.5) / f
        val cosE = cos(elevation)
        val sinE = sin(elevation)
        for (x in 0 until width) {
            val dx = cosE * sinH[x]
            val dy = cosE * cosH[x]
            val z = dx * basis.forward.x + dy * basis.forward.y + sinE * basis.forward.z
            if (z <= 1e-6) continue
            val u = cx + f * (dx * basis.right.x + dy * basis.right.y + sinE * basis.right.z) / z
            val v = cy + f * (dx * basis.down.x + dy * basis.down.y + sinE * basis.down.z) / z
            pixels[y * width + x] = frame.sample(u, v)
        }
    }
    return Raster(width, grid.height, pixels)
}

/**
 * Paints [tile] onto [canvas] at ([tileX], [tileY]). Where both have content, a seam runs down the overlap along
 * the smallest difference (so a moving object is taken whole from one side or the other, not ghosted), and the
 * two sides fade over [feather] pixels across it. Elsewhere whichever has content wins.
 */
fun composeWithSeam(canvas: Raster, tile: Raster, tileX: Int, tileY: Int, feather: Int = SEAM_FEATHER_PX) {
    val rows = tile.height
    // The overlap box: from the tile's left edge to the canvas's right-most content in the tile's rows.
    var overlapEnd = tileX
    for (r in 0 until rows) {
        val canvasRow = (tileY + r) * canvas.width
        for (x in minOf(canvas.width, tileX + tile.width) - 1 downTo overlapEnd) {
            if (canvas.pixels[canvasRow + x].alpha() != 0) {
                overlapEnd = x + 1
                break
            }
        }
    }
    val seam = seamColumns(canvas, tile, tileX, tileY, overlapEnd, feather)
    val half = feather / 2.0
    for (r in 0 until rows) {
        val canvasRow = (tileY + r) * canvas.width
        val s = seam?.get(r)
        for (x in 0 until tile.width) {
            val canvasX = tileX + x
            if (canvasX >= canvas.width) break
            val incoming = tile.pixels[r * tile.width + x]
            if (incoming.alpha() == 0) continue
            val index = canvasRow + canvasX
            val existing = canvas.pixels[index]
            canvas.pixels[index] = if (existing.alpha() == 0 || s == null) {
                incoming
            } else {
                val t = ((canvasX - s + half) / feather.coerceAtLeast(1)).coerceIn(0.0, 1.0)
                if (t <= 0.0) existing else if (t >= 1.0) incoming else mix(existing, incoming, t * t * (3 - 2 * t))
            }
        }
    }
}

/** The seam's column for each tile row, by dynamic programming over the overlap; null when nothing overlaps. */
private fun seamColumns(canvas: Raster, tile: Raster, tileX: Int, tileY: Int, overlapEnd: Int, feather: Int): IntArray? {
    // Keep room for the fade on both sides when the overlap allows it.
    val margin = minOf(feather / 2, (overlapEnd - tileX) / 4)
    val from = tileX + margin
    val to = overlapEnd - margin
    if (to <= from) return null
    val width = to - from
    val rows = tile.height
    val cost = IntArray(width * rows)
    for (r in 0 until rows) {
        for (c in 0 until width) {
            val canvasX = from + c
            val a = canvas.pixels[(tileY + r) * canvas.width + canvasX]
            val b = tile.pixels[r * tile.width + canvasX - tileX]
            // A slight pull to the middle: on matching content the seam stays central rather than hugging an edge.
            val pull = CENTRE_PULL * abs(2 * c - width) / width
            cost[r * width + c] = pull + if (a.alpha() == 0 || b.alpha() == 0) INVALID_COST else abs(a.luma() - b.luma())
        }
    }
    val total = LongArray(width * rows)
    for (c in 0 until width) total[c] = cost[c].toLong()
    for (r in 1 until rows) {
        for (c in 0 until width) {
            var best = total[(r - 1) * width + c]
            if (c > 0) best = minOf(best, total[(r - 1) * width + c - 1])
            if (c < width - 1) best = minOf(best, total[(r - 1) * width + c + 1])
            total[r * width + c] = best + cost[r * width + c]
        }
    }
    val seam = IntArray(rows)
    var c = (0 until width).minByOrNull { total[(rows - 1) * width + it] } ?: return null
    for (r in rows - 1 downTo 0) {
        seam[r] = from + c
        if (r == 0) break
        val above = (r - 1) * width
        var next = c
        if (c > 0 && total[above + c - 1] < total[above + next]) next = c - 1
        if (c < width - 1 && total[above + c + 1] < total[above + next]) next = c + 1
        c = next
    }
    return seam
}

private fun mix(a: Int, b: Int, t: Double): Int {
    var result = 0xFF shl 24
    for (shift in intArrayOf(16, 8, 0)) {
        val value = (a shr shift and 0xFF) * (1 - t) + (b shr shift and 0xFF) * t
        result = result or (value.roundToInt().coerceIn(0, 255) shl shift)
    }
    return result
}

/**
 * A stitched panorama and where it sits in a full 360°×180° equirectangular image ([fullWidth]×[fullHeight]):
 * the GPano numbers. [headingAtLeft] and [elevationAtTop] are the angles at the image's first column and row
 * edges, in radians, relative to the first frame's heading.
 */
class Panorama(
    val image: Raster,
    val fullWidth: Int,
    val fullHeight: Int,
    val left: Int,
    val top: Int,
    val headingAtLeft: Double,
    val elevationAtTop: Double,
)

/** A panorama frame before stitching: [load] gives the upright image; orientation as for [cameraBasis]. */
class OrientedFrame(val rotation: FloatArray, val surfaceRotation: Int, val load: () -> Raster)

/**
 * Stitches [frames] taken while turning: each is projected onto the sphere by its orientation, aligned to its
 * neighbour by image matching (starting from the sensor) and painted in with a seam. [focalPx] is the focal
 * length in pixels of the loaded frames. [onProgress] gets 0..1; cancellation is checked between frames.
 */
suspend fun stitchFrames(frames: List<OrientedFrame>, focalPx: Double, onProgress: (Float) -> Unit): Panorama {
    require(frames.size >= 2) { "A panorama needs at least two frames" }
    val bases = frames.map { cameraBasis(it.rotation, it.surfaceRotation) }
    // Headings relative to the first frame, unwrapped so a full turn keeps growing.
    val headings = DoubleArray(frames.size)
    for (i in 1 until frames.size) {
        var turn = headingOf(bases[i].forward) - headingOf(bases[i - 1].forward)
        while (turn > PI) turn -= 2 * PI
        while (turn < -PI) turn += 2 * PI
        headings[i] = headings[i - 1] + turn
    }
    val layout = panoramaLayout(headings.map { Math.toDegrees(it).toFloat() }, focalPx)

    val first = frames[layout.order.first()].load()
    val halfDiagonal = atan(hypot(first.width.toDouble(), first.height.toDouble()) / (2 * focalPx))
    val tileWidth = ceil(2 * halfDiagonal * focalPx).toInt()
    val elevations = bases.map { elevationOf(it.forward) }
    val grid = SphereGrid(
        focalPx,
        topElevation = elevations.max() + halfDiagonal,
        height = ceil((elevations.max() - elevations.min() + 2 * halfDiagonal) * focalPx).toInt(),
    )
    val searchX = (first.width * SEARCH_X_FRACTION).roundToInt().coerceAtLeast(4)
    val canvasWidth = tileWidth + layout.expectedDx.sum() + searchX * layout.expectedDx.size
    val canvas = Raster(canvasWidth, grid.height + 2 * MARGIN_Y, IntArray(canvasWidth * (grid.height + 2 * MARGIN_Y)))

    var previous: Raster? = null
    var x = 0
    var y = MARGIN_Y
    layout.order.forEachIndexed { step, index ->
        currentCoroutineContext().ensureActive()
        val image = if (step == 0) first else frames[index].load()
        val tile = projectTile(image, bases[index], headings[index], grid, tileWidth)
        previous?.let { left ->
            val expectedDx = layout.expectedDx[step - 1]
            val shift = estimateShift(left, tile, expectedDx, searchX, SEARCH_Y)
            x += if (shift.dx > 0) shift.dx else expectedDx
            y = (y + shift.dy).coerceIn(0, 2 * MARGIN_Y)
        }
        composeWithSeam(canvas, tile, x, y)
        previous = tile
        onProgress((step + 1f) / frames.size)
    }
    return framePanorama(
        canvas,
        focalPx,
        grid.topElevation,
        headingAtCanvasLeft = headings[layout.order.first()] - tileWidth / 2.0 / focalPx,
        // Three quarters of the frame's height as an angle: rows the frames' curved corners still reach.
        minHeight = (1.5 * atan(first.height / (2 * focalPx)) * focalPx).toInt(),
        maxTrim = tileWidth / 2,
    )
}

/**
 * Crops [canvas] to a rectangle with no empty pixel, at most 360° wide, and works out the GPano numbers. Frame
 * edges curve on the sphere, so the ends are trimmed step by step until the full rows are at least
 * [minHeight] tall (or the trimming runs out).
 */
private fun framePanorama(
    canvas: Raster,
    focalPx: Double,
    topElevation: Double,
    headingAtCanvasLeft: Double,
    minHeight: Int,
    maxTrim: Int,
): Panorama {
    val fullWidth = (2 * PI * focalPx).roundToInt()
    val fullHeight = fullWidth / 2
    // The painted columns of the best covered row.
    val counts = IntArray(canvas.height) { y -> (0 until canvas.width).count { canvas.pixels[y * canvas.width + it].alpha() != 0 } }
    val middle = counts.indices.maxByOrNull { counts[it] } ?: 0
    val painted = (0 until canvas.width).filter { canvas.pixels[middle * canvas.width + it].alpha() != 0 }
    var from = painted.firstOrNull() ?: 0
    var to = (painted.lastOrNull() ?: (canvas.width - 1)) + 1
    if (to - from > fullWidth) {
        from += (to - from - fullWidth) / 2
        to = from + fullWidth
    }
    val step = maxOf(1, maxTrim / 20)
    val runs = RowRuns(canvas, (from + to) / 2)
    var rows = runs.fullRows(from, to)
    var trimmed = 0
    while ((rows?.count() ?: 0) < minHeight && trimmed + step <= maxTrim && to - from > 2 * step) {
        from += step
        to -= step
        trimmed += step
        rows = runs.fullRows(from, to)
    }
    val band = rows ?: (0 until canvas.height)
    val width = to - from
    val firstRow = band.first
    val rowCount = band.count()
    val image = Raster(width, rowCount, IntArray(width * rowCount) { i ->
        canvas.pixels[(firstRow + i / width) * canvas.width + from + i % width] or (0xFF shl 24)
    })
    // Canvas row MARGIN_Y is the grid's first row.
    val elevationAtTop = topElevation - (firstRow - MARGIN_Y) / focalPx
    val top = (fullHeight / 2.0 - elevationAtTop * focalPx).roundToInt().coerceIn(0, (fullHeight - rowCount).coerceAtLeast(0))
    return Panorama(
        image = image,
        fullWidth = fullWidth,
        fullHeight = fullHeight,
        left = (fullWidth - width) / 2,
        top = top,
        headingAtLeft = headingAtCanvasLeft + from / focalPx,
        elevationAtTop = elevationAtTop,
    )
}

/** For each row, the unbroken painted run through column [through]: [start] inclusive, [end] exclusive. */
private class RowRuns(image: Raster, through: Int) {
    private val start = IntArray(image.height)
    private val end = IntArray(image.height)

    init {
        for (y in 0 until image.height) {
            val row = y * image.width
            if (image.pixels[row + through].alpha() == 0) continue
            var a = through
            while (a > 0 && image.pixels[row + a - 1].alpha() != 0) a--
            var b = through + 1
            while (b < image.width && image.pixels[row + b].alpha() != 0) b++
            start[y] = a
            end[y] = b
        }
    }

    /** The longest run of rows painted in every column of [from] until [to]. */
    fun fullRows(from: Int, to: Int): IntRange? {
        var best: IntRange? = null
        var runStart = -1
        for (y in 0..start.size) {
            val full = y < start.size && end[y] > start[y] && start[y] <= from && end[y] >= to
            if (full && runStart < 0) runStart = y
            if (!full && runStart >= 0) {
                if (best == null || y - runStart > best.count()) best = runStart until y
                runStart = -1
            }
        }
        return best
    }
}
