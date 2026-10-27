package cz.furmanek.motionphoto.panorama

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One kept frame: upright, as JPEG (a 360° turn would not fit in memory as pixels), with the device-to-world
 * [rotation] it was taken at and the [surfaceRotation] it was turned upright for.
 */
class PanoramaFrame(val jpeg: ByteArray, val width: Int, val rotation: FloatArray, val surfaceRotation: Int)

/** What the capture shows while it runs. [frames] counts the frames kept so far; [direction] as in [PanoramaProgress]. */
data class PanoramaStatus(val frames: Int, val sweptDeg: Float, val level: Boolean, val yawToNextDeg: Float, val direction: Int)

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
    @Volatile private var latestRotation = FloatArray(9)

    /** Without a (game) rotation vector sensor there is nothing to time the frames by. */
    val available: Boolean get() = sensor.available

    /**
     * Starts taking a frame after every [stepDeg] of turning. [onChange] reports progress; [onFinished] is called
     * once the turn reaches about 360°, and the caller should then [stop].
     */
    fun start(
        analysis: ImageAnalysis,
        stepDeg: Float,
        surfaceRotation: Int,
        onChange: (PanoramaStatus) -> Unit,
        onFinished: () -> Unit,
    ) {
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
            onChange(PanoramaStatus(synchronized(frames) { frames.size }, progress.sweptDeg, level, progress.degreesToNext(), progress.direction))
        }

        analysis.setAnalyzer(worker) { image ->
            if (stopped || !frameWanted) {
                image.close()
                return@setAnalyzer
            }
            try {
                val rotation = latestRotation
                val upright = upright(image)
                val frame = PanoramaFrame(upright.toJpeg(FRAME_JPEG_QUALITY), upright.width, rotation, surfaceRotation)
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
        sensor.start { yawDeg, pitchDeg, rotation ->
            latestRotation = rotation.copyOf()
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
        return raster.rotate(rotation)
    }

    private companion object {
        const val TAG = "PanoramaCapture"
        const val FRAME_JPEG_QUALITY = 95
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
 * the sphere that the sensor predicts. Turning left yields the frames right to left, so they are reversed.
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
 * Stitches [frames] at [scale] of their size (below 1 when memory ran short). [focalPx] is the focal length in
 * pixels of the frames as taken. [onProgress] gets 0..1; cancellation is checked between frames.
 */
suspend fun stitchPanorama(frames: List<PanoramaFrame>, focalPx: Double, scale: Double = 1.0, onProgress: (Float) -> Unit): Panorama {
    val oriented = frames.map { frame ->
        OrientedFrame(frame.rotation, frame.surfaceRotation) {
            val image = decodeJpeg(frame.jpeg)
            if (scale < 1.0) image.scaleToHeight((image.height * scale).roundToInt()) else image
        }
    }
    return stitchFrames(oriented, focalPx * scale, onProgress)
}

/** An opaque raster from JPEG bytes. */
private fun decodeJpeg(jpeg: ByteArray): Raster {
    val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: error("Panorama frame did not decode")
    try {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return Raster(bitmap.width, bitmap.height, pixels)
    } finally {
        bitmap.recycle()
    }
}

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
