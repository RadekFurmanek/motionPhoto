package cz.furmanek.motionphoto.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.PendingRecording
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import cz.furmanek.motionphoto.media.MediaStoreWriter
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/** Still and Motion Photo capture with the use cases the session currently has bound. */
class PhotoCapture(private val context: Context, private val writer: MediaStoreWriter) {

    private val mainExecutor = ContextCompat.getMainExecutor(context)

    suspend fun captureMotionPhoto(
        imageCapture: ImageCapture,
        videoCapture: VideoCapture<Recorder>,
        capturedAtMillis: Long,
        location: Location?,
    ): Uri {
        val (videoFile, stillFile) = withContext(Dispatchers.IO) {
            File.createTempFile(TEMP_VIDEO_PREFIX, ".mp4", context.cacheDir) to
                File.createTempFile(TEMP_STILL_PREFIX, ".jpg", context.cacheDir)
        }
        try {
            val presentationTimestampUs = recordClipWithStill(imageCapture, videoCapture, videoFile, stillFile)
            return withContext(Dispatchers.IO) {
                if (presentationTimestampUs != null) {
                    writer.saveMotionPhoto(stillFile, videoFile, presentationTimestampUs, capturedAtMillis, location)
                } else {
                    // The clip failed but the still is fine: keep the shot as an ordinary photo.
                    writer.savePhoto(stillFile, capturedAtMillis, location)
                }
            }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                videoFile.delete()
                stillFile.delete()
            }
        }
    }

    suspend fun capturePhoto(imageCapture: ImageCapture, capturedAtMillis: Long, location: Location?): Uri {
        val stillFile = withContext(Dispatchers.IO) { File.createTempFile(TEMP_STILL_PREFIX, ".jpg", context.cacheDir) }
        try {
            imageCapture.takePictureTo(stillFile)
            return withContext(Dispatchers.IO) { writer.savePhoto(stillFile, capturedAtMillis, location) }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { stillFile.delete() }
        }
    }

    /**
     * Records a [CLIP_DURATION_MS] clip into [videoFile] and takes a full-resolution still into [stillFile]
     * [STILL_OFFSET_MS] after recording starts. Returns the still's position in the clip, in microseconds,
     * or null when the still was taken but the clip failed. Throws when the still itself failed.
     *
     * Both moments are measured on the elapsed-realtime clock. The video's t = 0 is derived from its end:
     * the Recorder cuts the clip at the stop request, whereas the encoder may deliver its first frame
     * noticeably later than the Start event.
     */
    private suspend fun recordClipWithStill(
        imageCapture: ImageCapture,
        videoCapture: VideoCapture<Recorder>,
        videoFile: File,
        stillFile: File,
    ): Long? = coroutineScope {
        val started = CompletableDeferred<Long>()
        val finalized = CompletableDeferred<VideoRecordEvent.Finalize>()
        val recording = videoCapture.output
            .prepareRecording(context, FileOutputOptions.Builder(videoFile).build())
            .withAudioIfPermitted()
            .start(mainExecutor) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> started.complete(SystemClock.elapsedRealtimeNanos())
                    is VideoRecordEvent.Finalize -> {
                        finalized.complete(event)
                        started.completeExceptionally(RecordingFailedException(event))
                    }
                }
            }

        try {
            val startNs = withTimeout(RECORDING_START_TIMEOUT_MS) { started.await() }
            delayUntil(startNs + TimeUnit.MILLISECONDS.toNanos(STILL_OFFSET_MS))
            val stillNs = async { imageCapture.takePictureTo(stillFile) }
            delayUntil(startNs + TimeUnit.MILLISECONDS.toNanos(CLIP_DURATION_MS))
            val stopNs = SystemClock.elapsedRealtimeNanos()
            recording.stop()

            val finalize = withTimeoutOrNull(RECORDING_FINALIZE_TIMEOUT_MS) { finalized.await() }
            val stillAtNs = stillNs.await()
            if (finalize == null || finalize.hasError()) {
                Log.w(TAG, "Motion Photo clip failed (error ${finalize?.error}); saving the still alone", finalize?.cause)
                return@coroutineScope null
            }
            val clipDurationNs = finalize.recordingStats.recordedDurationNanos
            val firstFrameNs = stopNs - clipDurationNs
            TimeUnit.NANOSECONDS.toMicros((stillAtNs - firstFrameNs).coerceIn(0L, clipDurationNs))
        } finally {
            recording.close()
            if (!finalized.isCompleted) {
                withContext(NonCancellable) { withTimeoutOrNull(RECORDING_FINALIZE_TIMEOUT_MS) { finalized.await() } }
            }
        }
    }

    /** Saves a JPEG to [file] and returns the elapsed-realtime moment its capture started. */
    private suspend fun ImageCapture.takePictureTo(file: File): Long = suspendCancellableCoroutine { continuation ->
        val requestedAtNs = SystemClock.elapsedRealtimeNanos()
        var captureStartedNs = -1L
        takePicture(
            ImageCapture.OutputFileOptions.Builder(file).build(),
            mainExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onCaptureStarted() {
                    captureStartedNs = SystemClock.elapsedRealtimeNanos()
                }

                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    continuation.resume(if (captureStartedNs >= 0) captureStartedNs else requestedAtNs)
                }

                override fun onError(exception: ImageCaptureException) {
                    continuation.resumeWithException(exception)
                }
            },
        )
    }

    // RECORD_AUDIO is optional: without it the clip is recorded silent.
    @SuppressLint("MissingPermission")
    private fun PendingRecording.withAudioIfPermitted(): PendingRecording {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        return if (granted) withAudioEnabled() else this
    }

    private class RecordingFailedException(event: VideoRecordEvent.Finalize) :
        Exception("Video recording failed (error ${event.error})", event.cause)

    companion object {
        const val CLIP_DURATION_MS = 2_000L
        const val STILL_OFFSET_MS = CLIP_DURATION_MS / 2

        private const val TAG = "PhotoCapture"
        private const val TEMP_VIDEO_PREFIX = "motion_clip_"
        private const val TEMP_STILL_PREFIX = "motion_still_"
        private const val RECORDING_START_TIMEOUT_MS = 3_000L
        private const val RECORDING_FINALIZE_TIMEOUT_MS = 5_000L

        /** Removes temp files left behind by a capture the process did not finish (crash, kill, late CameraX write). */
        fun deleteTempFiles(cacheDir: File) {
            cacheDir
                .listFiles { file -> file.name.startsWith(TEMP_VIDEO_PREFIX) || file.name.startsWith(TEMP_STILL_PREFIX) }
                ?.forEach { it.delete() }
        }

        private suspend fun delayUntil(elapsedRealtimeNs: Long) {
            val remainingMs = TimeUnit.NANOSECONDS.toMillis(elapsedRealtimeNs - SystemClock.elapsedRealtimeNanos())
            if (remainingMs > 0) delay(remainingMs)
        }
    }
}
