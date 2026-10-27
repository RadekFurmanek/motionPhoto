package cz.furmanek.motionphoto.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
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
import cz.furmanek.motionphoto.media.MediaKind
import cz.furmanek.motionphoto.media.MediaStoreWriter
import cz.furmanek.motionphoto.media.SavedMedia
import cz.furmanek.motionphoto.portrait.PORTRAIT_TEMP_PREFIX
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Position of the still in the clip, in microseconds. The clip's first frame is derived from its end:
 * the Recorder cuts at the stop request, while the encoder may start noticeably after the Start event.
 */
fun motionPhotoPresentationTimestampUs(stillNs: Long, stopNs: Long, clipDurationNs: Long): Long {
    val firstFrameNs = stopNs - clipDurationNs
    return TimeUnit.NANOSECONDS.toMicros((stillNs - firstFrameNs).coerceIn(0L, clipDurationNs))
}

/** Still and Motion Photo capture with the use cases the session currently has bound. */
class PhotoCapture(private val context: Context, private val writer: MediaStoreWriter) {

    private val mainExecutor = ContextCompat.getMainExecutor(context)

    /**
     * [location] is awaited only when saving, so a slow fix never delays the shutter.
     * [mirrored] flips the still horizontally (front camera with mirroring on); the clip is mirrored by its use case.
     */
    suspend fun captureMotionPhoto(
        imageCapture: ImageCapture,
        videoCapture: VideoCapture<Recorder>,
        capturedAtMillis: Long,
        mirrored: Boolean,
        location: Deferred<Location?>,
    ): SavedMedia {
        val (videoFile, stillFile) = withContext(Dispatchers.IO) {
            File.createTempFile(TEMP_VIDEO_PREFIX, ".mp4", context.cacheDir) to
                File.createTempFile(TEMP_STILL_PREFIX, ".jpg", context.cacheDir)
        }
        try {
            val presentationTimestampUs = recordClipWithStill(imageCapture, videoCapture, videoFile, stillFile, mirrored)
            val location = location.await()
            return withContext(Dispatchers.IO) {
                if (presentationTimestampUs != null) {
                    val uri = writer.saveMotionPhoto(stillFile, videoFile, presentationTimestampUs, capturedAtMillis, location)
                    SavedMedia(uri, MediaKind.MOTION_PHOTO)
                } else {
                    // The clip failed but the still is fine: keep the shot as an ordinary photo.
                    SavedMedia(writer.savePhoto(stillFile, capturedAtMillis, location), MediaKind.PHOTO)
                }
            }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                videoFile.delete()
                stillFile.delete()
            }
        }
    }

    /**
     * [process], when given, may rewrite the still in place before it is saved (custom portrait). Once the still
     * is taken, processing and saving finish even if the caller is cancelled, so the shot is never lost.
     */
    suspend fun capturePhoto(
        imageCapture: ImageCapture,
        capturedAtMillis: Long,
        mirrored: Boolean,
        location: Deferred<Location?>,
        process: (suspend (File) -> Unit)? = null,
    ): SavedMedia {
        val stillFile = withContext(Dispatchers.IO) { File.createTempFile(TEMP_STILL_PREFIX, ".jpg", context.cacheDir) }
        try {
            imageCapture.takePictureTo(stillFile, mirrored)
            if (process == null) {
                val location = location.await()
                return withContext(Dispatchers.IO) {
                    SavedMedia(writer.savePhoto(stillFile, capturedAtMillis, location), MediaKind.PHOTO)
                }
            }
            return withContext(NonCancellable) {
                process(stillFile)
                // A cancelled caller cancels the location lookup too; the photo is then saved without it.
                val savedLocation = try {
                    location.await()
                } catch (_: CancellationException) {
                    null
                }
                withContext(Dispatchers.IO) {
                    SavedMedia(writer.savePhoto(stillFile, capturedAtMillis, savedLocation), MediaKind.PHOTO)
                }
            }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { stillFile.delete() }
        }
    }

    /**
     * Records a [CLIP_DURATION_MS] clip into [videoFile] and takes a full-resolution still into [stillFile]
     * [STILL_OFFSET_MS] after recording starts. Returns the still's position in the clip, in microseconds,
     * or null when the still was taken but the clip failed. Throws when the still itself failed.
     * Both moments are measured on the elapsed-realtime clock.
     */
    private suspend fun recordClipWithStill(
        imageCapture: ImageCapture,
        videoCapture: VideoCapture<Recorder>,
        videoFile: File,
        stillFile: File,
        mirrored: Boolean,
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
            val stillNs = async { imageCapture.takePictureTo(stillFile, mirrored) }
            delayUntil(startNs + TimeUnit.MILLISECONDS.toNanos(CLIP_DURATION_MS))
            val stopNs = SystemClock.elapsedRealtimeNanos()
            recording.stop()

            val finalize = withTimeoutOrNull(RECORDING_FINALIZE_TIMEOUT_MS) { finalized.await() }
            val stillAtNs = stillNs.await()
            if (finalize == null || finalize.hasError()) {
                Log.w(TAG, "Motion Photo clip failed (error ${finalize?.error}); saving the still alone", finalize?.cause)
                return@coroutineScope null
            }
            motionPhotoPresentationTimestampUs(stillAtNs, stopNs, finalize.recordingStats.recordedDurationNanos)
        } finally {
            recording.close()
            if (!finalized.isCompleted) {
                withContext(NonCancellable) { withTimeoutOrNull(RECORDING_FINALIZE_TIMEOUT_MS) { finalized.await() } }
            }
        }
    }

    /** Saves a JPEG to [file] and returns the elapsed-realtime moment its capture started. */
    private suspend fun ImageCapture.takePictureTo(file: File, mirrored: Boolean): Long = suspendCancellableCoroutine { continuation ->
        val requestedAtNs = SystemClock.elapsedRealtimeNanos()
        var captureStartedNs = -1L
        val metadata = ImageCapture.Metadata().apply { isReversedHorizontal = mirrored }
        takePicture(
            ImageCapture.OutputFileOptions.Builder(file).setMetadata(metadata).build(),
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
        private val TEMP_PREFIXES = listOf(TEMP_VIDEO_PREFIX, TEMP_STILL_PREFIX, PORTRAIT_TEMP_PREFIX)
        private const val RECORDING_START_TIMEOUT_MS = 3_000L
        private const val RECORDING_FINALIZE_TIMEOUT_MS = 5_000L

        /** Removes temp files left behind by a capture the process did not finish (crash, kill, late CameraX write). */
        fun deleteTempFiles(cacheDir: File) {
            cacheDir
                .listFiles { file -> TEMP_PREFIXES.any { file.name.startsWith(it) } }
                ?.forEach { it.delete() }
        }

        private suspend fun delayUntil(elapsedRealtimeNs: Long) {
            val remainingMs = TimeUnit.NANOSECONDS.toMillis(elapsedRealtimeNs - SystemClock.elapsedRealtimeNanos())
            if (remainingMs > 0) delay(remainingMs)
        }
    }
}
