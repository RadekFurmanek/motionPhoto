package cz.furmanek.motionphoto.camera

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.core.content.ContextCompat
import cz.furmanek.motionphoto.media.MediaStoreWriter.PendingVideo
import cz.furmanek.motionphoto.timelapse.FrameFailures
import cz.furmanek.motionphoto.timelapse.TimelapseEncoder
import cz.furmanek.motionphoto.timelapse.alignedEncoderSize
import cz.furmanek.motionphoto.timelapse.rgbaToI420
import cz.furmanek.motionphoto.timelapse.shouldTakeFrame
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Keeps one analysis frame per interval and encodes it straight away, so memory stays flat over hours.
 * Frames skipped by the interval are closed at once. All encoding happens on one worker thread.
 * The MP4 is muxed straight into a pending MediaStore entry, created with the first frame, so no copy is needed.
 */
class TimelapseRecorder(private val context: Context) {

    /**
     * What a run left behind. [uri] is the still pending entry (null when no frame came), [playable] whether it
     * was finalised with at least one frame, [frames] how many frames the codec got, and [encoderFailed]
     * whether the run was cut short by a broken encoder.
     */
    class Result(val uri: Uri?, val playable: Boolean, val frames: Int, val encoderFailed: Boolean)

    private var worker: ExecutorService? = null
    private var analysis: ImageAnalysis? = null
    private var openOutput: (() -> PendingVideo)? = null
    private var output: PendingVideo? = null
    private var encoder: TimelapseEncoder? = null
    private var lastTakenMs: Long? = null
    private var stopped = false
    private var encoderFailed = false

    /**
     * [openOutput] creates the pending entry on the worker thread when the first frame arrives.
     * [onFrame] gets the number of frames encoded so far and [onEncoderFailed] is called once the encoder
     * cannot go on; the caller should then [stop]. Both run on the main thread.
     */
    fun start(
        analysis: ImageAnalysis,
        intervalMs: Long,
        openOutput: () -> PendingVideo,
        onFrame: (frames: Int) -> Unit,
        onEncoderFailed: () -> Unit,
    ) {
        check(worker == null) { "Time-lapse already running" }
        val worker = Executors.newSingleThreadExecutor().also { worker = it }
        val mainExecutor = ContextCompat.getMainExecutor(context)
        val failures = FrameFailures()
        this.analysis = analysis
        this.openOutput = openOutput
        stopped = false
        encoderFailed = false
        lastTakenMs = null
        analysis.setAnalyzer(worker) { image ->
            image.use {
                if (stopped || encoderFailed) return@use
                val now = SystemClock.elapsedRealtime()
                if (!shouldTakeFrame(now, lastTakenMs, intervalMs)) return@use
                lastTakenMs = now
                try {
                    val frames = encode(it)
                    failures.onSuccess()
                    mainExecutor.execute { onFrame(frames) }
                } catch (e: Exception) {
                    Log.e(TAG, "Encoding a time-lapse frame failed", e)
                    // One bad frame must not end hours of recording, but an encoder that cannot be created
                    // or keeps failing would only waste the rest of the run.
                    if (encoder == null || failures.onFailure()) {
                        encoderFailed = true
                        mainExecutor.execute(onEncoderFailed)
                    }
                }
            }
        }
    }

    /** Stops taking frames and completes the file with what was encoded. Never throws. */
    suspend fun stop(): Result {
        val worker = worker ?: return Result(null, playable = false, frames = 0, encoderFailed = false)
        analysis?.clearAnalyzer()
        analysis = null
        return try {
            withContext(worker.asCoroutineDispatcher()) {
                stopped = true
                val encoder = encoder
                val playable = encoder?.finish() == true
                val output = output
                // The muxer is released by now, so the entry can be closed and then published or deleted.
                output?.descriptor?.let { descriptor ->
                    runCatching { descriptor.close() }.onFailure { Log.w(TAG, "Closing the time-lapse file failed", it) }
                }
                this@TimelapseRecorder.encoder = null
                this@TimelapseRecorder.output = null
                Result(output?.uri, playable, encoder?.frameCount ?: 0, encoderFailed)
            }
        } finally {
            worker.shutdown()
            this.worker = null
            openOutput = null
        }
    }

    private fun encode(image: ImageProxy): Int {
        val plane = image.planes[0]
        val (width, height) = alignedEncoderSize(image.width, image.height)
        val encoder = encoder ?: run {
            val output = output ?: checkNotNull(openOutput)().also { output = it }
            TimelapseEncoder(output.descriptor.fileDescriptor, width, height, image.imageInfo.rotationDegrees)
                .also { encoder = it }
        }
        val buffer = plane.buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        // Centre crop to the encoder's multiple-of-16 size.
        val offset = (image.height - height) / 2 * plane.rowStride + (image.width - width) / 2 * plane.pixelStride
        encoder.addFrame(rgbaToI420(bytes, width, height, plane.rowStride, offset))
        return encoder.frameCount
    }

    private companion object {
        const val TAG = "TimelapseRecorder"
    }
}
