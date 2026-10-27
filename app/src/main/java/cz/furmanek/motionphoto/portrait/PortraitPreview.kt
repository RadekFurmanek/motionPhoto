package cz.furmanek.motionphoto.portrait

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import cz.furmanek.motionphoto.panorama.Raster
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The live portrait viewfinder: analysis frames turned like the preview, their background blurred as the photo
 * will be. [frame] holds only the blurred background, transparent over the subject, so the sharp subject is the
 * live preview underneath. Frames are converted on one worker thread; segmentation runs in [scope].
 */
class PortraitPreview(private val masker: SubjectMasker, private val scope: CoroutineScope) {

    private val _frame = MutableStateFlow<ImageBitmap?>(null)

    /** The blurred background to draw over the preview; null draws the preview alone (no mask or no subject). */
    val frame: StateFlow<ImageBitmap?> = _frame.asStateFlow()

    private var worker: ExecutorService? = null
    private var analysis: ImageAnalysis? = null
    @Volatile private var maskJob: Job? = null

    /** Guards publishing to [frame] against [stop], so no frame is left on screen after it. */
    private val lock = Any()

    /** The latest feathered mask, upright and mirrored like the preview; null when there is none or no subject. */
    @Volatile private var mask: Mask? = null
    @Volatile private var running = false

    /** Bumped by every start and stop, so a segmentation of an earlier run is dropped. */
    @Volatile private var generation = 0
    private val segmenting = AtomicBoolean(false)

    // Touched only on the worker thread.
    private var nextFrameAt = 0L
    @Volatile private var nextMaskAt = 0L
    private val timing = FrameTiming()
    @Volatile private var failureLogged = false
    private var bytes = ByteArray(0)
    private var alpha: IntArray? = null
    private var alphaMask: Mask? = null

    /** Starts drawing [analysis] frames; [mirrored] for a front camera, which the preview shows mirrored. */
    fun start(analysis: ImageAnalysis, mirrored: Boolean) {
        stop()
        synchronized(lock) {
            generation++
            running = true
        }
        val worker = Executors.newSingleThreadExecutor().also { worker = it }
        this.analysis = analysis
        nextFrameAt = 0L
        nextMaskAt = 0L
        failureLogged = false
        analysis.setAnalyzer(worker) { image -> onImage(image, mirrored) }
    }

    /** Stops drawing and forgets the mask; the plain preview shows again. Safe to call at any time. */
    fun stop() {
        synchronized(lock) {
            generation++
            running = false
            mask = null
            _frame.value = null
            segmenting.set(false)
        }
        analysis?.clearAnalyzer()
        analysis = null
        worker?.shutdown()
        worker = null
        maskJob?.cancel()
        maskJob = null
    }

    private fun onImage(image: ImageProxy, mirrored: Boolean) {
        val now = SystemClock.elapsedRealtime()
        timing.incoming++
        val wantsMask = now >= nextMaskAt && !segmenting.get()
        val wantsFrame = mask != null && now >= nextFrameAt
        if (!running || (!wantsMask && !wantsFrame)) {
            image.close()
            return
        }
        try {
            val started = SystemClock.elapsedRealtimeNanos()
            val small = upright(image, mirrored)
            val converted = SystemClock.elapsedRealtimeNanos()
            if (wantsMask) requestMask(small)
            val current = mask
            if (wantsFrame && current != null) {
                // On schedule rather than 50 ms after the last frame: a 30 fps camera would otherwise give 15.
                nextFrameAt = if (now - nextFrameAt >= FRAME_INTERVAL_MS) now + FRAME_INTERVAL_MS else nextFrameAt + FRAME_INTERVAL_MS
                val blurred = boxBlur(small, blurRadius(max(small.width, small.height), BACKGROUND_BLUR))
                val blurredAt = SystemClock.elapsedRealtimeNanos()
                val layer = backgroundLayer(blurred, alphaFor(current, small.width, small.height))
                val bitmap = Bitmap.createBitmap(layer, small.width, small.height, Bitmap.Config.ARGB_8888)
                synchronized(lock) {
                    if (running && mask === current) _frame.value = bitmap.asImageBitmap()
                }
                timing.add(converted - started, blurredAt - converted, SystemClock.elapsedRealtimeNanos() - blurredAt, now)
            }
        } catch (e: Exception) {
            // The next frame is tried instead; a bad one must not break the viewfinder.
            Log.e(TAG, "Drawing a portrait preview frame failed", e)
        }
    }

    /** The mask's alpha at the preview size, computed once per mask rather than every frame. */
    private fun alphaFor(mask: Mask, width: Int, height: Int): IntArray {
        val cached = alpha
        if (alphaMask === mask && cached != null && cached.size == width * height) return cached
        return maskAlpha(mask.confidence, mask.width, mask.height, width, height).also {
            alpha = it
            alphaMask = mask
        }
    }

    /** Copies the RGBA plane and closes [image] straight away, then turns, mirrors and scales the copy. */
    private fun upright(image: ImageProxy, mirrored: Boolean): Raster {
        val rotation: Int
        val crop: android.graphics.Rect
        val rowStride: Int
        val pixelStride: Int
        image.use {
            val plane = it.planes[0]
            val buffer = plane.buffer
            if (bytes.size != buffer.remaining()) bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            rowStride = plane.rowStride
            pixelStride = plane.pixelStride
            rotation = it.imageInfo.rotationDegrees
            crop = it.cropRect
        }
        return rgbaPreview(
            bytes, rowStride, pixelStride, crop.left, crop.top, crop.width(), crop.height(), rotation, mirrored, PREVIEW_HEIGHT,
        )
    }

    /** At most one segmentation at a time, on a copy of [frame] fitted inside 256 px. */
    private fun requestMask(frame: Raster) {
        if (!segmenting.compareAndSet(false, true)) return
        val (width, height) = fitInside(frame.width, frame.height, MASK_MAX_SIDE)
        val pixels = previewRaster(frame, 0, 0, frame.width, frame.height, 0, false, height).pixels
        nextMaskAt = SystemClock.elapsedRealtime() + MASK_INTERVAL_MS
        val run = generation
        maskJob = scope.launch(Dispatchers.Default) {
            // Not recycled: after a cancel ML Kit may still be reading it.
            val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
            var next: Mask? = null
            try {
                val result = masker.mask(bitmap)
                if (result == null) {
                    // The model is still downloading: ask again later, not every frame.
                    nextMaskAt = SystemClock.elapsedRealtime() + RETRY_INTERVAL_MS
                } else if (hasSubject(result.confidence)) {
                    next = feathered(result)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                segmentationFailed(e)
            } catch (e: LinkageError) {
                segmentationFailed(e)
            } finally {
                synchronized(lock) {
                    if (run == generation) {
                        mask = next
                        if (next == null) _frame.value = null
                        segmenting.set(false)
                    }
                }
            }
        }
    }

    /** Retried later; the stack trace is logged once per run, as a failing model fails every time. */
    private fun segmentationFailed(e: Throwable) {
        nextMaskAt = SystemClock.elapsedRealtime() + RETRY_INTERVAL_MS
        if (failureLogged) {
            Log.w(TAG, "Segmenting a preview frame failed again: $e")
        } else {
            failureLogged = true
            Log.w(TAG, "Segmenting a preview frame failed", e)
        }
    }

    private fun feathered(mask: Mask): Mask {
        val radius = blurRadius(max(mask.width, mask.height), MASK_FEATHER)
        return Mask(mask.width, mask.height, blurMask(mask.confidence, mask.width, mask.height, radius))
    }

    /** Logs the average time per stage and the frame rate once in a while. */
    private class FrameTiming {
        private var frames = 0
        var incoming = 0
        private var convertNs = 0L
        private var blurNs = 0L
        private var composeNs = 0L
        private var since = 0L

        fun add(convert: Long, blur: Long, compose: Long, nowMs: Long) {
            if (frames == 0) since = nowMs
            frames++
            convertNs += convert
            blurNs += blur
            composeNs += compose
            val elapsed = nowMs - since
            if (elapsed >= LOG_INTERVAL_MS) {
                Log.d(
                    TAG,
                    "Preview frames: %.1f fps of %.1f from the camera, convert %.1f ms, blur %.1f ms, composite %.1f ms".format(
                        (frames - 1) * 1000f / elapsed,
                        incoming * 1000f / elapsed,
                        convertNs / 1e6 / frames,
                        blurNs / 1e6 / frames,
                        composeNs / 1e6 / frames,
                    ),
                )
                frames = 0
                incoming = 0
                convertNs = 0
                blurNs = 0
                composeNs = 0
            }
        }
    }

    private companion object {
        const val TAG = "PortraitPreview"
        const val PREVIEW_HEIGHT = 480
        const val MASK_MAX_SIDE = 256
        const val FRAME_INTERVAL_MS = 50L // At most 20 frames a second.
        const val MASK_INTERVAL_MS = 250L
        const val RETRY_INTERVAL_MS = 2_000L
        const val LOG_INTERVAL_MS = 5_000L
    }
}

/**
 * The [cropWidth] x [cropHeight] region of [src] at ([cropLeft], [cropTop]) turned clockwise by [degrees],
 * then mirrored left to right if [mirrored], and scaled bilinearly to [outHeight] rows.
 */
internal fun previewRaster(
    src: Raster,
    cropLeft: Int,
    cropTop: Int,
    cropWidth: Int,
    cropHeight: Int,
    degrees: Int,
    mirrored: Boolean,
    outHeight: Int,
): Raster {
    val turn = ((degrees % 360) + 360) % 360
    val (uprightWidth, uprightHeight) = turnedSize(cropWidth, cropHeight, turn)
    val outWidth = (uprightWidth.toLong() * outHeight / uprightHeight).toInt().coerceAtLeast(1)
    val scaleX = uprightWidth.toFloat() / outWidth
    val scaleY = uprightHeight.toFloat() / outHeight
    val out = IntArray(outWidth * outHeight)
    for (y in 0 until outHeight) {
        // Clamped to the crop, so nothing outside it bleeds in at the edges.
        val uy = toMaskCoordinate(y, scaleY).coerceIn(0f, uprightHeight - 1f)
        for (x in 0 until outWidth) {
            val ux = toMaskCoordinate(if (mirrored) outWidth - 1 - x else x, scaleX).coerceIn(0f, uprightWidth - 1f)
            // Where (ux, uy) of the turned crop lies in the crop; see Raster.rotate.
            val cx: Float
            val cy: Float
            when (turn) {
                0 -> { cx = ux; cy = uy }
                90 -> { cx = uy; cy = cropHeight - 1 - ux }
                180 -> { cx = cropWidth - 1 - ux; cy = cropHeight - 1 - uy }
                else -> { cx = cropWidth - 1 - uy; cy = ux }
            }
            out[y * outWidth + x] = argbAt(src.pixels, src.width, src.height, cropLeft + cx, cropTop + cy)
        }
    }
    return Raster(outWidth, outHeight, out)
}

/**
 * The [cropWidth] x [cropHeight] region at ([cropLeft], [cropTop]) of an RGBA_8888 plane, as [previewRaster]
 * makes it from the whole frame: turned by [degrees], mirrored if [mirrored], bilinearly scaled to [outHeight]
 * rows. Reads the plane directly in fixed point, as it runs on every preview frame.
 */
internal fun rgbaPreview(
    bytes: ByteArray,
    rowStride: Int,
    pixelStride: Int,
    cropLeft: Int,
    cropTop: Int,
    cropWidth: Int,
    cropHeight: Int,
    degrees: Int,
    mirrored: Boolean,
    outHeight: Int,
): Raster {
    val turn = ((degrees % 360) + 360) % 360
    val (uprightWidth, uprightHeight) = turnedSize(cropWidth, cropHeight, turn)
    val outWidth = (uprightWidth.toLong() * outHeight / uprightHeight).toInt().coerceAtLeast(1)
    // Byte offset of the crop's (0, 0) and the steps for one upright pixel right and down.
    val origin: Int
    val stepRight: Int
    val stepDown: Int
    when (turn) {
        0 -> {
            origin = cropTop * rowStride + cropLeft * pixelStride
            stepRight = pixelStride
            stepDown = rowStride
        }
        90 -> {
            origin = (cropTop + cropHeight - 1) * rowStride + cropLeft * pixelStride
            stepRight = -rowStride
            stepDown = pixelStride
        }
        180 -> {
            origin = (cropTop + cropHeight - 1) * rowStride + (cropLeft + cropWidth - 1) * pixelStride
            stepRight = -pixelStride
            stepDown = -rowStride
        }
        else -> {
            origin = cropTop * rowStride + (cropLeft + cropWidth - 1) * pixelStride
            stepRight = rowStride
            stepDown = -pixelStride
        }
    }
    // Source columns and rows with 8-bit weights, worked out once per column and once per row.
    val columnStart = IntArray(outWidth)
    val columnNext = IntArray(outWidth)
    val columnWeight = IntArray(outWidth)
    for (x in 0 until outWidth) {
        val ux = toMaskCoordinate(if (mirrored) outWidth - 1 - x else x, uprightWidth.toFloat() / outWidth)
            .coerceIn(0f, uprightWidth - 1f)
        val u0 = ux.toInt()
        columnStart[x] = u0 * stepRight
        columnNext[x] = (u0 + 1).coerceAtMost(uprightWidth - 1) * stepRight
        columnWeight[x] = ((ux - u0) * 256).roundToInt()
    }
    val out = IntArray(outWidth * outHeight)
    for (y in 0 until outHeight) {
        val uy = toMaskCoordinate(y, uprightHeight.toFloat() / outHeight).coerceIn(0f, uprightHeight - 1f)
        val v0 = uy.toInt()
        val top = origin + v0 * stepDown
        val bottom = origin + (v0 + 1).coerceAtMost(uprightHeight - 1) * stepDown
        val wy = ((uy - v0) * 256).roundToInt()
        for (x in 0 until outWidth) {
            val wx = columnWeight[x]
            val p00 = top + columnStart[x]
            val p10 = top + columnNext[x]
            val p01 = bottom + columnStart[x]
            val p11 = bottom + columnNext[x]
            var argb = 0xFF shl 24
            for (c in 0..2) {
                val upper = (bytes[p00 + c].toInt() and 0xFF) * (256 - wx) + (bytes[p10 + c].toInt() and 0xFF) * wx
                val lower = (bytes[p01 + c].toInt() and 0xFF) * (256 - wx) + (bytes[p11 + c].toInt() and 0xFF) * wx
                argb = argb or (((upper * (256 - wy) + lower * wy + (1 shl 15)) shr 16) shl (16 - 8 * c))
            }
            out[y * outWidth + x] = argb
        }
    }
    return Raster(outWidth, outHeight, out)
}

/**
 * Alpha (in the top byte) at [width] x [height] for a [maskWidth] x [maskHeight] foreground [mask], sampled
 * bilinearly: 1 minus the confidence, so the subject is clear and the background covered.
 */
internal fun maskAlpha(mask: FloatArray, maskWidth: Int, maskHeight: Int, width: Int, height: Int): IntArray {
    val scaleX = maskWidth.toFloat() / width
    val scaleY = maskHeight.toFloat() / height
    val out = IntArray(width * height)
    for (y in 0 until height) {
        val my = toMaskCoordinate(y, scaleY)
        for (x in 0 until width) {
            val confidence = maskAt(mask, maskWidth, maskHeight, toMaskCoordinate(x, scaleX), my)
            out[y * width + x] = ((1f - confidence) * 255f).roundToInt().coerceIn(0, 255) shl 24
        }
    }
    return out
}

/** The [blurred] background with the subject cut out by [alpha] from [maskAlpha]; over the sharp preview this is [blend]. */
internal fun backgroundLayer(blurred: Raster, alpha: IntArray): IntArray =
    IntArray(blurred.pixels.size) { alpha[it] or (blurred.pixels[it] and 0xFFFFFF) }
