package cz.furmanek.motionphoto.panorama

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

/** One kept frame: upright, at most 960 px tall, with the compass heading it was taken at. */
class PanoramaFrame(val image: Raster, val yawDeg: Float)

/** What the capture shows while it runs. [frames] counts the frames kept so far. */
data class PanoramaStatus(val frames: Int, val sweptDeg: Float, val level: Boolean, val yawToNextDeg: Float)

/**
 * Takes panorama frames: the compass decides when ([PanoramaProgress]) and the next analysis frame after that
 * is kept, turned upright and scaled down. Sensor readings and the callbacks run on the main thread, frame
 * conversion on one worker thread.
 */
class PanoramaCapture(private val context: Context) {

    private val sensor = PanoramaSensor(context)
    private var worker: ExecutorService? = null
    private var analysis: ImageAnalysis? = null
    private val frames = mutableListOf<PanoramaFrame>() // guarded by itself

    @Volatile private var stopped = true
    @Volatile private var frameWanted = false
    @Volatile private var latestYaw = 0f

    /** Without a rotation vector sensor there is nothing to time the frames by. */
    val available: Boolean get() = sensor.available

    /**
     * Starts taking a frame after every [stepDeg] of turning. [onChange] reports progress; [onFinished] is called
     * once the turn reaches about 360°, and the caller should then [stop].
     */
    fun start(analysis: ImageAnalysis, stepDeg: Float, onChange: (PanoramaStatus) -> Unit, onFinished: () -> Unit) {
        check(worker == null) { "Panorama already running" }
        val worker = Executors.newSingleThreadExecutor().also { worker = it }
        val mainExecutor = ContextCompat.getMainExecutor(context)
        val progress = PanoramaProgress(stepDeg)
        synchronized(frames) { frames.clear() }
        this.analysis = analysis
        stopped = false
        frameWanted = false
        var level = true
        var finishReported = false
        fun report() {
            if (stopped) return
            onChange(PanoramaStatus(synchronized(frames) { frames.size }, progress.sweptDeg, level, progress.degreesToNext()))
        }

        analysis.setAnalyzer(worker) { image ->
            if (stopped || !frameWanted) {
                image.close()
                return@setAnalyzer
            }
            try {
                val frame = PanoramaFrame(upright(image), latestYaw)
                synchronized(frames) {
                    if (stopped) return@setAnalyzer
                    frames += frame
                    frameWanted = false
                }
                mainExecutor.execute(::report)
            } catch (e: Exception) {
                // The next frame is tried instead; a bad one must not end the panorama or the app.
                Log.e(TAG, "Converting a panorama frame failed", e)
            }
        }
        sensor.start { yawDeg, pitchDeg ->
            latestYaw = yawDeg
            if (progress.onYaw(yawDeg)) frameWanted = true
            level = isLevel(pitchDeg)
            report()
            if (progress.finished && !finishReported) {
                finishReported = true
                onFinished()
            }
        }
    }

    /** Stops taking frames and hands over what was kept, in capture order. */
    suspend fun stop(): List<PanoramaFrame> {
        if (worker == null) return emptyList()
        sensor.stop()
        // The frame the last heading asked for may still be on its way.
        withTimeoutOrNull(LAST_FRAME_WAIT_MS) {
            while (frameWanted) delay(LAST_FRAME_POLL_MS)
        }
        tearDown()
        return synchronized(frames) { frames.toList().also { frames.clear() } }
    }

    /** Stops and throws away everything kept. Safe to call at any time. */
    fun cancel() {
        sensor.stop()
        tearDown()
        synchronized(frames) { frames.clear() }
    }

    private fun tearDown() {
        stopped = true
        frameWanted = false
        analysis?.clearAnalyzer()
        analysis = null
        worker?.shutdown()
        worker = null
    }

    /** Copies the RGBA plane and closes [image] straight away, then turns and scales the copy. */
    private fun upright(image: ImageProxy): Raster {
        val rotation: Int
        val raster = image.use {
            val plane = it.planes[0]
            val buffer = plane.buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            rotation = it.imageInfo.rotationDegrees
            rgbaToRaster(bytes, it.width, it.height, plane.rowStride, plane.pixelStride)
        }
        val turned = raster.rotate(rotation)
        return if (turned.height > MAX_FRAME_HEIGHT) turned.scaleToHeight(MAX_FRAME_HEIGHT) else turned
    }

    private companion object {
        const val TAG = "PanoramaCapture"
        const val MAX_FRAME_HEIGHT = 960
        const val LAST_FRAME_WAIT_MS = 500L
        const val LAST_FRAME_POLL_MS = 16L
    }
}

/** An RGBA_8888 plane as an opaque ARGB raster, skipping any padding at the row ends. */
fun rgbaToRaster(bytes: ByteArray, width: Int, height: Int, rowStride: Int, pixelStride: Int): Raster =
    Raster(width, height, IntArray(width * height) { i ->
        val offset = (i / width) * rowStride + (i % width) * pixelStride
        (0xFF shl 24) or
            ((bytes[offset].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            (bytes[offset + 2].toInt() and 0xFF)
    })

/**
 * How the frames go left to right: [order] lists capture indexes, [expectedDx] the shift between neighbours on
 * the cylinder that the compass predicts. Turning left yields the frames right to left, so they are reversed.
 */
internal class PanoramaLayout(val order: List<Int>, val expectedDx: List<Int>)

internal fun panoramaLayout(yawsDeg: List<Float>, focalPx: Double): PanoramaLayout {
    val turns = yawsDeg.zipWithNext { a, b -> wrapDegrees(b - a) }
    val leftward = turns.sum() < 0
    val order = yawsDeg.indices.toList().let { if (leftward) it.reversed() else it }
    val ordered = if (leftward) turns.reversed() else turns
    return PanoramaLayout(
        order,
        ordered.map { (Math.toRadians(abs(it).toDouble()) * focalPx).roundToInt().coerceAtLeast(1) },
    )
}

/**
 * Projects [frames] onto a cylinder of radius [focalPx], aligns neighbours starting from the compass shift and
 * blends them. [onProgress] gets 0..1; cancellation is checked between frames.
 */
suspend fun stitchPanorama(frames: List<PanoramaFrame>, focalPx: Double, onProgress: (Float) -> Unit): Raster {
    require(frames.size >= 2) { "A panorama needs at least two frames" }
    val layout = panoramaLayout(frames.map { it.yawDeg }, focalPx)
    // Warping each frame and aligning each pair; the blend itself ends at 1.
    val steps = frames.size * 2
    var done = 0
    fun stepDone() {
        done++
        onProgress(done.toFloat() / steps)
    }

    val warped = layout.order.map { index ->
        currentCoroutineContext().ensureActive()
        cylindricalWarp(frames[index].image, focalPx).also { stepDone() }
    }
    val shifts = layout.expectedDx.mapIndexed { i, expectedDx ->
        currentCoroutineContext().ensureActive()
        val shift = estimateShift(warped[i], warped[i + 1], expectedDx, searchX = expectedDx / 4, searchY = SEARCH_Y)
        stepDone()
        // stitch() needs every frame to the right of the previous one.
        if (shift.dx > 0) shift else Shift(expectedDx, 0, confident = false)
    }
    currentCoroutineContext().ensureActive()
    return stitch(warped, shifts).also { onProgress(1f) }
}

private const val SEARCH_Y = 24

/** JPEG bytes of an opaque raster. */
fun Raster.toJpeg(quality: Int): ByteArray {
    val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    try {
        return ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)
            output.toByteArray()
        }
    } finally {
        bitmap.recycle()
    }
}
