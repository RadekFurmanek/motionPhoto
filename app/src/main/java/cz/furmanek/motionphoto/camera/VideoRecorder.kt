package cz.furmanek.motionphoto.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import cz.furmanek.motionphoto.media.MediaStoreWriter

enum class FinalizeOutcome { SAVED, SAVED_INTERRUPTED, FAILED }

/**
 * How a finished recording ends up. Interruptions (storage full, camera taken away, limits) still leave
 * a valid file with what was recorded; the rest leave nothing usable.
 */
fun classifyFinalize(error: Int): FinalizeOutcome = when (error) {
    VideoRecordEvent.Finalize.ERROR_NONE -> FinalizeOutcome.SAVED
    VideoRecordEvent.Finalize.ERROR_INSUFFICIENT_STORAGE,
    VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE,
    VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED,
    VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED,
    -> FinalizeOutcome.SAVED_INTERRUPTED
    else -> FinalizeOutcome.FAILED
}

/** Android 14+ refuses a microphone-typed foreground service without RECORD_AUDIO, so the type follows the audio. */
fun foregroundServiceTypes(withAudio: Boolean): Int =
    if (withAudio) {
        ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
    } else {
        ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
    }

/** One video recording at a time, written straight into MediaStore. */
class VideoRecorder(private val context: Context, private val writer: MediaStoreWriter) {

    private var recording: Recording? = null

    fun hasAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Starts recording; [onEvent] receives every [VideoRecordEvent] on the main thread. */
    @SuppressLint("MissingPermission")
    fun start(
        videoCapture: VideoCapture<Recorder>,
        withAudio: Boolean,
        location: Location?,
        onEvent: (VideoRecordEvent) -> Unit,
    ) {
        check(recording == null) { "A recording is already running" }
        val pending = videoCapture.output.prepareRecording(
            context,
            writer.videoOutputOptions(System.currentTimeMillis(), location),
        )
        recording = (if (withAudio && hasAudioPermission()) pending.withAudioEnabled() else pending)
            .start(ContextCompat.getMainExecutor(context)) { event ->
                if (event is VideoRecordEvent.Finalize) recording = null
                onEvent(event)
            }
    }

    fun pause() {
        recording?.pause()
    }

    fun resume() {
        recording?.resume()
    }

    fun stop() {
        recording?.stop()
    }
}
