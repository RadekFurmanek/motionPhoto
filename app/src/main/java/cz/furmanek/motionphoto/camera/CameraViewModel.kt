package cz.furmanek.motionphoto.camera

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfoUnavailableException
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.MeteringPoint
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.PendingRecording
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.asFlow
import androidx.lifecycle.viewModelScope
import cz.furmanek.motionphoto.media.MediaStoreWriter
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

data class CameraUiState(
    val lensFacing: Int = CameraSelector.LENS_FACING_BACK,
    val canSwitchCamera: Boolean = false,
    val isCameraReady: Boolean = false,
    val isCapturing: Boolean = false,
    val zoomRatio: Float = 1f,
)

sealed interface CameraEvent {
    data class Saved(val uri: Uri) : CameraEvent
    data class CaptureFailed(val message: String) : CameraEvent
    data class CameraUnavailable(val message: String) : CameraEvent
}

class CameraViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(CameraUiState())
    val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()

    private val _events = Channel<CameraEvent>(Channel.BUFFERED)
    val events: Flow<CameraEvent> = _events.receiveAsFlow()

    private val mainExecutor = ContextCompat.getMainExecutor(application)
    private val store = MediaStoreWriter(application.contentResolver)

    private val preview = Preview.Builder()
        .setResolutionSelector(resolutionSelector4by3())
        .build()

    private val imageCapture = ImageCapture.Builder()
        // Minimal shutter lag keeps the still close to the moment it is stamped at in the video.
        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
        .setResolutionSelector(resolutionSelector4by3())
        .build()

    private val videoCapture = VideoCapture.withOutput(
        Recorder.Builder()
            .setQualitySelector(QualitySelector.from(Quality.FHD, FallbackStrategy.lowerQualityOrHigherThan(Quality.FHD)))
            .setAspectRatio(AspectRatio.RATIO_4_3)
            .build(),
    )

    private var camera: Camera? = null
    private var zoomJob: Job? = null
    private var requestedZoomRatio = 1f

    /** Rotation reported while a capture runs; applied afterwards so still and video share one orientation. */
    private var deferredTargetRotation: Int? = null

    init {
        viewModelScope.launch(Dispatchers.IO) { deleteTempFiles() }
    }

    /**
     * Binds Preview, ImageCapture and VideoCapture to [lifecycleOwner] as one group sharing [viewPort],
     * so all three see the same field of view. When the device cannot stream all three at once,
     * CameraX feeds Preview and VideoCapture from a single shared stream (StreamSharing).
     */
    suspend fun bindCamera(
        lifecycleOwner: LifecycleOwner,
        surfaceProvider: Preview.SurfaceProvider,
        viewPort: ViewPort,
    ) {
        _uiState.update { it.copy(isCameraReady = false) }
        val provider = ProcessCameraProvider.awaitInstance(getApplication())
        val hasBack = provider.hasLens(CameraSelector.LENS_FACING_BACK)
        val hasFront = provider.hasLens(CameraSelector.LENS_FACING_FRONT)
        val lensFacing = when {
            _uiState.value.lensFacing == CameraSelector.LENS_FACING_BACK && hasBack -> CameraSelector.LENS_FACING_BACK
            _uiState.value.lensFacing == CameraSelector.LENS_FACING_FRONT && hasFront -> CameraSelector.LENS_FACING_FRONT
            hasBack -> CameraSelector.LENS_FACING_BACK
            hasFront -> CameraSelector.LENS_FACING_FRONT
            else -> {
                _events.send(CameraEvent.CameraUnavailable("no front or back camera"))
                return
            }
        }

        preview.surfaceProvider = surfaceProvider
        val useCases = UseCaseGroup.Builder()
            .setViewPort(viewPort)
            .addUseCase(preview)
            .addUseCase(imageCapture)
            .addUseCase(videoCapture)
            .build()
        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()

        val bound = try {
            provider.unbindAll()
            provider.bindToLifecycle(lifecycleOwner, selector, useCases)
        } catch (e: IllegalArgumentException) {
            onBindFailed(e)
            return
        } catch (e: IllegalStateException) {
            onBindFailed(e)
            return
        }

        camera = bound
        requestedZoomRatio = bound.cameraInfo.zoomState.value?.zoomRatio ?: 1f
        zoomJob?.cancel()
        zoomJob = viewModelScope.launch {
            bound.cameraInfo.zoomState.asFlow().collect { zoom -> _uiState.update { it.copy(zoomRatio = zoom.zoomRatio) } }
        }
        _uiState.update {
            it.copy(lensFacing = lensFacing, canSwitchCamera = hasBack && hasFront, isCameraReady = true)
        }
    }

    /** Releases the preview surface so the use case does not keep the destroyed PreviewView alive. */
    fun detachPreview() {
        preview.surfaceProvider = null
    }

    fun switchCamera() {
        _uiState.update { state ->
            if (state.isCapturing || !state.canSwitchCamera) return@update state
            val next = if (state.lensFacing == CameraSelector.LENS_FACING_BACK) {
                CameraSelector.LENS_FACING_FRONT
            } else {
                CameraSelector.LENS_FACING_BACK
            }
            state.copy(lensFacing = next, zoomRatio = 1f)
        }
    }

    fun focusOn(point: MeteringPoint) {
        val camera = camera ?: return
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .setAutoCancelDuration(FOCUS_AUTO_CANCEL_SECONDS, TimeUnit.SECONDS)
            .build()
        if (camera.cameraInfo.isFocusMeteringSupported(action)) {
            camera.cameraControl.startFocusAndMetering(action)
        }
    }

    /** Multiplies the zoom by [scaleFactor] (a pinch gesture's incremental scale). */
    fun zoomBy(scaleFactor: Float) {
        val camera = camera ?: return
        val zoom = camera.cameraInfo.zoomState.value ?: return
        // Chain from the last requested ratio: the reported one lags behind fast pinches.
        requestedZoomRatio = (requestedZoomRatio * scaleFactor).coerceIn(zoom.minZoomRatio, zoom.maxZoomRatio)
        camera.cameraControl.setZoomRatio(requestedZoomRatio)
    }

    /** Sets the orientation the photo and video are saved in, as a [Surface] rotation constant. */
    fun setTargetRotation(rotation: Int) {
        if (_uiState.value.isCapturing) {
            deferredTargetRotation = rotation
            return
        }
        imageCapture.targetRotation = rotation
        videoCapture.targetRotation = rotation
    }

    fun capture() {
        val state = _uiState.value
        if (camera == null || state.isCapturing || !state.isCameraReady) return
        _uiState.value = state.copy(isCapturing = true)

        viewModelScope.launch {
            try {
                _events.send(CameraEvent.Saved(captureMotionPhoto()))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Motion Photo capture failed", e)
                _events.send(CameraEvent.CaptureFailed(e.message ?: e.javaClass.simpleName))
            } finally {
                _uiState.update { it.copy(isCapturing = false) }
                deferredTargetRotation?.let {
                    deferredTargetRotation = null
                    setTargetRotation(it)
                }
            }
        }
    }

    private suspend fun captureMotionPhoto(): Uri {
        val capturedAtMillis = System.currentTimeMillis()
        val cacheDir = getApplication<Application>().cacheDir
        val (videoFile, stillFile) = withContext(Dispatchers.IO) {
            File.createTempFile(TEMP_VIDEO_PREFIX, ".mp4", cacheDir) to File.createTempFile(TEMP_STILL_PREFIX, ".jpg", cacheDir)
        }
        try {
            val presentationTimestampUs = recordClipWithStill(videoFile, stillFile)
            return withContext(Dispatchers.IO) {
                store.saveMotionPhoto(stillFile, videoFile, presentationTimestampUs, capturedAtMillis, location = null)
            }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                videoFile.delete()
                stillFile.delete()
            }
        }
    }

    /**
     * Records a [CLIP_DURATION_MS] clip into [videoFile] and takes a full-resolution still into [stillFile]
     * [STILL_OFFSET_MS] after recording starts. Returns the still's position in the clip, in microseconds.
     *
     * Both moments are measured on the elapsed-realtime clock. The video's t = 0 is derived from its end:
     * the Recorder cuts the clip at the stop request, whereas the encoder may deliver its first frame
     * noticeably later than the Start event.
     */
    private suspend fun recordClipWithStill(videoFile: File, stillFile: File): Long = coroutineScope {
        val started = CompletableDeferred<Long>()
        val finalized = CompletableDeferred<VideoRecordEvent.Finalize>()
        val recording = videoCapture.output
            .prepareRecording(getApplication(), FileOutputOptions.Builder(videoFile).build())
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

            val finalize = withTimeout(RECORDING_FINALIZE_TIMEOUT_MS) { finalized.await() }
            if (finalize.hasError()) throw RecordingFailedException(finalize)
            val clipDurationNs = finalize.recordingStats.recordedDurationNanos
            val firstFrameNs = stopNs - clipDurationNs
            TimeUnit.NANOSECONDS.toMicros((stillNs.await() - firstFrameNs).coerceIn(0L, clipDurationNs))
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
        val granted = ContextCompat.checkSelfPermission(getApplication(), Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        return if (granted) withAudioEnabled() else this
    }

    private suspend fun onBindFailed(error: Exception) {
        Log.e(TAG, "Binding camera use cases failed", error)
        camera = null
        _events.send(CameraEvent.CameraUnavailable(error.message ?: error.javaClass.simpleName))
    }

    /** Removes temp files left behind by a capture the process did not finish (crash, kill, late CameraX write). */
    private fun deleteTempFiles() {
        getApplication<Application>().cacheDir
            .listFiles { file -> file.name.startsWith(TEMP_VIDEO_PREFIX) || file.name.startsWith(TEMP_STILL_PREFIX) }
            ?.forEach { it.delete() }
    }

    private class RecordingFailedException(event: VideoRecordEvent.Finalize) :
        Exception("Video recording failed (error ${event.error})", event.cause)

    companion object {
        const val CLIP_DURATION_MS = 2_000L
        const val STILL_OFFSET_MS = CLIP_DURATION_MS / 2

        private const val TAG = "CameraViewModel"
        private const val TEMP_VIDEO_PREFIX = "motion_clip_"
        private const val TEMP_STILL_PREFIX = "motion_still_"
        private const val RECORDING_START_TIMEOUT_MS = 3_000L
        private const val RECORDING_FINALIZE_TIMEOUT_MS = 5_000L
        private const val FOCUS_AUTO_CANCEL_SECONDS = 3L

        private fun resolutionSelector4by3() = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
            .build()

        private fun ProcessCameraProvider.hasLens(lensFacing: Int): Boolean =
            try {
                hasCamera(CameraSelector.Builder().requireLensFacing(lensFacing).build())
            } catch (_: CameraInfoUnavailableException) {
                false
            }

        private suspend fun delayUntil(elapsedRealtimeNs: Long) {
            val remainingMs = TimeUnit.NANOSECONDS.toMillis(elapsedRealtimeNs - SystemClock.elapsedRealtimeNanos())
            if (remainingMs > 0) delay(remainingMs)
        }
    }
}
