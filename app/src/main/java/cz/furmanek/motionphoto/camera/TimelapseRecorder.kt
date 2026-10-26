package cz.furmanek.motionphoto.camera

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.core.content.ContextCompat
import cz.furmanek.motionphoto.timelapse.TimelapseEncoder
import cz.furmanek.motionphoto.timelapse.alignedEncoderSize
import cz.furmanek.motionphoto.timelapse.rgbaToI420
import cz.furmanek.motionphoto.timelapse.shouldTakeFrame
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Keeps one analysis frame per interval and encodes it straight away, so memory stays flat over hours.
 * Frames skipped by the interval are closed at once. All encoding happens on one worker thread.
 */
class TimelapseRecorder(private val context: Context) {

    private var worker: ExecutorService? = null
    private var analysis: ImageAnalysis? = null
    private var output: File? = null
    private var encoder: TimelapseEncoder? = null
    private var lastTakenMs: Long? = null
    private var stopped = false

    /** [onFrame] gets the number of frames encoded so far, on the main thread. */
    fun start(analysis: ImageAnalysis, intervalMs: Long, onFrame: (frames: Int) -> Unit) {
        check(worker == null) { "Time-lapse already running" }
        val worker = Executors.newSingleThreadExecutor().also { worker = it }
        val mainExecutor = ContextCompat.getMainExecutor(context)
        this.analysis = analysis
        output = File.createTempFile(TEMP_PREFIX, ".mp4", context.cacheDir)
        stopped = false
        lastTakenMs = null
        analysis.setAnalyzer(worker) { image ->
            image.use {
                if (stopped) return@use
                val now = SystemClock.elapsedRealtime()
                if (!shouldTakeFrame(now, lastTakenMs, intervalMs)) return@use
                lastTakenMs = now
                try {
                    val frames = encode(it)
                    mainExecutor.execute { onFrame(frames) }
                } catch (e: RuntimeException) {
                    // One bad frame (codec hiccup) must not end hours of recording.
                    Log.e(TAG, "Encoding a time-lapse frame failed", e)
                }
            }
        }
    }

    /** Stops taking frames and completes the file; null when no frame was recorded. */
    suspend fun stop(): File? {
        val worker = worker ?: return null
        analysis?.clearAnalyzer()
        analysis = null
        return try {
            withContext(worker.asCoroutineDispatcher()) {
                stopped = true
                val written = encoder?.finish() == true
                encoder = null
                output?.takeIf { written } ?: run {
                    output?.delete()
                    null
                }
            }
        } finally {
            worker.shutdown()
            this.worker = null
            output = null
        }
    }

    private fun encode(image: ImageProxy): Int {
        val plane = image.planes[0]
        val encoder = encoder ?: run {
            val (width, height) = alignedEncoderSize(image.width, image.height)
            TimelapseEncoder(checkNotNull(output), width, height, image.imageInfo.rotationDegrees).also { encoder = it }
        }
        val (width, height) = alignedEncoderSize(image.width, image.height)
        val buffer = plane.buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        // Centre crop to the encoder's multiple-of-16 size.
        val offset = (image.height - height) / 2 * plane.rowStride + (image.width - width) / 2 * plane.pixelStride
        encoder.addFrame(rgbaToI420(bytes, width, height, plane.rowStride, offset))
        return encoder.frameCount
    }

    companion object {
        private const val TAG = "TimelapseRecorder"
        private const val TEMP_PREFIX = "timelapse_"

        fun deleteTempFiles(cacheDir: File) {
            cacheDir.listFiles { file -> file.name.startsWith(TEMP_PREFIX) }?.forEach { it.delete() }
        }
    }
}
