package cz.furmanek.motionphoto.camera

import android.app.Application
import android.media.MediaActionSound
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.util.Rational
import android.view.Surface
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ExperimentalZeroShutterLag
import androidx.camera.core.ExtendableBuilder
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.MeteringPointFactory
import androidx.camera.core.MirrorMode
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.extensions.ExtensionMode
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.HighSpeedVideoSessionConfig
import androidx.camera.lifecycle.awaitInstance
import androidx.core.content.ContextCompat
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.asFlow
import androidx.compose.ui.graphics.ImageBitmap
import cz.furmanek.motionphoto.R
import cz.furmanek.motionphoto.media.MediaKind
import cz.furmanek.motionphoto.media.MediaStoreWriter
import cz.furmanek.motionphoto.media.SavedMedia
import cz.furmanek.motionphoto.panorama.Attitude
import cz.furmanek.motionphoto.panorama.PanoramaCapture
import cz.furmanek.motionphoto.panorama.PanoramaSensor
import cz.furmanek.motionphoto.panorama.PanoramaStatus
import cz.furmanek.motionphoto.panorama.attitudeOf
import cz.furmanek.motionphoto.panorama.focalLengthPx
import cz.furmanek.motionphoto.panorama.stitchPanorama
import cz.furmanek.motionphoto.panorama.toJpeg
import cz.furmanek.motionphoto.portrait.MlKitSubjectMasker
import cz.furmanek.motionphoto.portrait.PortraitOutcome
import cz.furmanek.motionphoto.livephoto.PreRollRecorder
import cz.furmanek.motionphoto.portrait.PortraitPreview
import cz.furmanek.motionphoto.portrait.PortraitProcessor
import cz.furmanek.motionphoto.portrait.clampBlur
import cz.furmanek.motionphoto.settings.CameraSettings
import cz.furmanek.motionphoto.settings.CaptureMode
import cz.furmanek.motionphoto.settings.FlashSetting
import cz.furmanek.motionphoto.settings.LENS_FACING_BACK
import cz.furmanek.motionphoto.settings.LENS_FACING_FRONT
import cz.furmanek.motionphoto.settings.PhotoAspect
import cz.furmanek.motionphoto.settings.SettingsRepository
import cz.furmanek.motionphoto.settings.VideoQualitySetting
import cz.furmanek.motionphoto.settings.effectiveLivePhoto
import cz.furmanek.motionphoto.settings.shutterSoundRequired
import cz.furmanek.motionphoto.timelapse.TimelapseOutcome
import cz.furmanek.motionphoto.timelapse.classifyTimelapse
import cz.furmanek.motionphoto.location.LocationProvider
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.atan
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class SessionStatus { CLOSED, OPENING, READY, ERROR }

enum class RecordingKind { VIDEO, TIMELAPSE }

data class RecordingState(
    val durationNanos: Long,
    val paused: Boolean,
    val withAudio: Boolean,
    val kind: RecordingKind = RecordingKind.VIDEO,
    /** Time-lapse frames recorded so far. */
    val frames: Int = 0,
)

/** A panorama being taken ([capturing]) or stitched ([stitchingProgress] 0..1). */
data class PanoramaUiState(
    val capturing: Boolean,
    val count: Int = 0,
    val sweptDeg: Float = 0f,
    val level: Boolean = true,
    /** Degrees to turn on to the next frame; positive in the turning direction. */
    val yawToNextDeg: Float = 0f,
    /** 1 sweeping right, −1 left, 0 before the first move. */
    val direction: Int = 0,
    /** The camera's tilt in degrees when the capture started: the height the target dot holds. */
    val startPitchDeg: Float? = null,
    val stitchingProgress: Float? = null,
)

data class SessionState(
    val status: SessionStatus = SessionStatus.CLOSED,
    val errorMessage: String? = null,
    val capabilities: CameraCapabilities? = null,
    val activeCameraId: String? = null,
    val lensFacing: Int = LENS_FACING_BACK,
    val zoomRatio: Float = 1f,
    val exposureIndex: Int = 0,
    val recording: RecordingState? = null,
    val busy: Boolean = false,
    val countdown: Int? = null,
    val lastCapture: Uri? = null,
    /** False when this camera cannot stream preview, still and video together; photos are then plain. */
    val livePhotoAvailable: Boolean = true,
    /** The live photo clip starts before the press: after it only [PreRollRecorder.POST_ROLL_MS] is left to record. */
    val livePhotoPreRoll: Boolean = false,
    val panorama: PanoramaUiState? = null,
    /** Macro is on (Photo mode, back camera). */
    val macro: Boolean = false,
) {
    /** A capture, recording or panorama runs: mode, camera and capture settings must not change. */
    val controlsLocked: Boolean get() = busy || recording != null || panorama != null
}

sealed interface SessionEvent {
    /** [notice] explains a shot saved plainer than asked (a custom portrait that came out as an ordinary photo). */
    data class Saved(val media: SavedMedia, val notice: String? = null) : SessionEvent
    data class CaptureFailed(val message: String) : SessionEvent
    /** The recording stopped early (storage full, camera taken away); what was recorded is saved at [uri]. */
    data class RecordingInterrupted(val uri: Uri?) : SessionEvent
}

/**
 * Owns the camera for the whole app process. Its lifecycle is STARTED while the camera UI is visible
 * or a recording runs, so a recording outlives the Activity. UIs only attach a preview surface.
 */
class CameraSession(
    private val app: Application,
    private val settingsRepository: SettingsRepository,
    private val writer: MediaStoreWriter,
) : LifecycleOwner {

    private val registry = LifecycleRegistry(this).apply { currentState = Lifecycle.State.CREATED }
    override val lifecycle: Lifecycle get() = registry

    private val scope = MainScope()
    private val photoCapture = PhotoCapture(app, writer)
    private val locationProvider = LocationProvider(app)
    private val videoRecorder = VideoRecorder(app, writer)
    private val shutterSound = MediaActionSound().apply {
        load(MediaActionSound.SHUTTER_CLICK)
        load(MediaActionSound.START_VIDEO_RECORDING)
        load(MediaActionSound.STOP_VIDEO_RECORDING)
    }

    /** True where the law requires the shutter sound (e.g. Japan); the setting cannot turn it off there. */
    val shutterSoundMandatory: Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && MediaActionSound.mustPlayShutterSound()

    private val _state = MutableStateFlow(SessionState())
    val state: StateFlow<SessionState> = _state.asStateFlow()

    // Emitted with tryEmit: a slow consumer must never hold up a capture.
    private val _events = MutableSharedFlow<SessionEvent>(extraBufferCapacity = EVENT_BUFFER)
    val events: SharedFlow<SessionEvent> = _events.asSharedFlow()

    private var settings = CameraSettings()
    private var settingsLoaded = false
    private var provider: ProcessCameraProvider? = null
    private var extensionsManager: ExtensionsManager? = null
    private var extensionsLoaded = false
    private var camera: Camera? = null
    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var imageAnalysis: ImageAnalysis? = null
    private val timelapseRecorder = TimelapseRecorder(app)
    private var timelapseClock: Job? = null
    private val panoramaCapture = PanoramaCapture(app)
    private var panoramaJob: Job? = null

    /** Feeds [panoramaAttitude] while Panorama mode is on screen, before and during a capture. */
    private val levelSensor = PanoramaSensor(app)
    private var levelSensorRunning = false
    private val _panoramaAttitude = MutableStateFlow<Attitude?>(null)

    /** The phone's sideways roll and the camera's tilt for the panorama level; null outside Panorama mode. */
    val panoramaAttitude: StateFlow<Attitude?> = _panoramaAttitude.asStateFlow()

    // Built on first use, so modes other than Portrait never pay for ML Kit. Lives as long as the session,
    // which is the app process; there is no point at which to close it.
    private val subjectMasker by lazy { MlKitSubjectMasker(app) }
    private val portraitProcessor by lazy { PortraitProcessor(subjectMasker) }

    /** Built the first time a custom portrait is bound, for the same reason as [subjectMasker]. */
    private val portraitPreview = MutableStateFlow<PortraitPreview?>(null)

    /** The analysis stream [portraitPreview] draws from; null while it is stopped. */
    private var portraitAnalysis: ImageAnalysis? = null

    /**
     * The custom portrait's background blur: set at once by [setPortraitBlur], so the live preview follows the
     * slider without waiting for the settings store, which [persistPortraitBlur] updates when the slider is let go.
     */
    private val _portraitBlur = MutableStateFlow(CameraSettings().portraitBlur)
    val portraitBlur: StateFlow<Float> = _portraitBlur.asStateFlow()
    private var portraitBlurWrite: Job? = null

    /** The custom portrait's blurred background, drawn over the preview; null shows the preview alone. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val portraitFrame: Flow<ImageBitmap?> = portraitPreview.flatMapLatest { it?.frame ?: flowOf(null) }

    /**
     * Whether the portrait model can run (the segmenter builds and Google Play services are present); checked
     * once, off the main thread. A failure of ML Kit counts as unavailable, so it never fails a bind.
     */
    private var customPortraitAvailable: Boolean? = null

    /** The bound Portrait mode is the app's own (no maker bokeh): photos are processed before saving. */
    private var customPortraitBound = false

    /** The bound live photo feeds its analysis stream to a continuous encoder, so its clip can start before the press. */
    private var preRollBound = false

    /** Encoding the live photo's pre-roll while it is bound and the app is on screen; null otherwise. */
    private var preRoll: PreRollRecorder? = null
    private var preRollAnalysis: ImageAnalysis? = null
    private var preRollWatch: Job? = null
    private val preRollExecutor = Executors.newSingleThreadExecutor()

    /** The pre-roll encoder broke on this camera; live photos then record after the press until the app restarts. */
    private var preRollBroken = false
    private var target: PreviewTarget? = null
    private var boundKey: BindingKey? = null
    private var bindJob: Job? = null
    private var photoJob: Job? = null
    private var cameraObservers: Job? = null
    private var zoomJob: Job? = null
    private val uiVisibility = UiVisibility()
    private val discardingPreview = DiscardingSurfaceProvider()

    /** What the bound Preview currently draws into (CameraX keeps its getter internal). */
    private var routedProvider: Preview.SurfaceProvider? = null
    private val uiVisible: Boolean get() = uiVisibility.anyVisible
    private var selectedBackCameraId: String? = null

    /** Every camera and physical camera the phone lists; read once, when the camera first opens. */
    private var lenses: List<LensInfo>? = null
    private var macroSetup: MacroSetup? = null
    private var macroOn = false

    /** Binding the close-focusing camera failed once; Macro zooms the default camera instead. */
    private var macroLensBroken = false
    private var targetRotation = Surface.ROTATION_0
    private var requestedZoomRatio = 1f

    private class PreviewTarget(
        val surfaceProvider: Preview.SurfaceProvider,
        val displayRotation: Int,
        val portrait: Boolean,
        val meteringFactory: MeteringPointFactory,
    )

    /** Everything that needs a new set of use cases when it changes. */
    private data class BindingKey(
        val mode: CaptureMode,
        val livePhoto: Boolean,
        val lensFacing: Int,
        val backCameraId: String?,
        val aspect: PhotoAspect,
        val videoQuality: VideoQualitySetting,
        val videoFps60: Boolean,
        val videoStabilization: Boolean,
        val mirrorFrontCamera: Boolean,
        val displayRotation: Int,
        val portrait: Boolean,
        val hdr: Boolean,
        val slowMotionFps: Int,
        val macro: MacroSetup?,
    )

    init {
        scope.launch(Dispatchers.IO) {
            PhotoCapture.deleteTempFiles(app.cacheDir)
        }
        scope.launch {
            val latest = withContext(Dispatchers.IO) { runCatching { writer.latestOwnCapture() }.getOrNull() }
            if (latest != null) _state.update { it.copy(lastCapture = it.lastCapture ?: latest) }
        }
        scope.launch {
            settingsRepository.settings.collect { new ->
                if (_state.value.panorama?.capturing == true && endsPanorama(settings, new)) cancelPanorama()
                // Macro belongs to the back camera's Photo mode and is off again on return.
                if (new.mode != CaptureMode.PHOTO || new.lensFacing != LENS_FACING_BACK) setMacroOff()
                settings = new
                settingsLoaded = true
                // While the slider's write is still landing, the older value must not pull it back.
                if (portraitBlurWrite?.isActive != true) _portraitBlur.value = new.portraitBlur
                applyCameraControls()
                requestBind()
            }
        }
    }

    fun attachPreview(
        surfaceProvider: Preview.SurfaceProvider,
        displayRotation: Int,
        portrait: Boolean,
        meteringFactory: MeteringPointFactory,
    ) {
        target = PreviewTarget(surfaceProvider, displayRotation, portrait, meteringFactory)
        routePreview()
        requestBind()
    }

    /** Detaches [surfaceProvider] if it is still the attached one; a newer UI may already have replaced it. */
    fun detachPreview(surfaceProvider: Preview.SurfaceProvider) {
        if (target?.surfaceProvider !== surfaceProvider) return
        target = null
        routePreview()
    }

    fun onUiVisible(visible: Boolean) {
        if (visible) uiVisibility.shown() else uiVisibility.hidden()
        // A panorama and the live portrait run only while the app is on screen.
        if (!uiVisible) {
            cancelPanorama()
            setMacroOff()
        }
        updatePortraitPreview()
        updatePreRoll()
        updatePanoramaLevel()
        routePreview()
        // Another screen (capture for another app) may have bound the camera meanwhile, which unbinds ours.
        val preview = preview
        if (visible && preview != null && provider?.isBound(preview) == false) {
            camera = null
            boundKey = null
        }
        updateLifecycle()
        if (visible) requestBind()
    }

    /**
     * Sends preview frames to the screen while it shows, and to [discardingPreview] while a recording runs
     * in the background: the screen's surface is gone then, and a stream without a consumer stalls the camera.
     */
    private fun routePreview() {
        val preview = preview ?: return
        val provider = (if (uiVisible) target?.surfaceProvider else null)
            ?: discardingPreview.takeIf { _state.value.recording != null }
        if (routedProvider !== provider) {
            routedProvider = provider
            preview.surfaceProvider = provider
        }
    }

    /** Switches to a separately exposed back camera (ultra-wide, telephoto); null returns to the default one. */
    fun selectBackCamera(cameraId: String?) {
        if (isLocked()) return
        selectedBackCameraId = cameraId
        requestBind()
    }

    /** Turns Macro on or off; it takes effect in the back camera's Photo mode. */
    fun setMacro(on: Boolean) {
        if (isLocked()) return
        macroOn = on
        _state.update { it.copy(macro = on) }
        requestBind()
    }

    private fun setMacroOff() {
        macroOn = false
        _state.update { it.copy(macro = false) }
    }

    /** What Macro binds now: null while it is off or outside Photo on the back camera. */
    private fun activeMacro(): MacroSetup? {
        if (!macroOn || settings.mode != CaptureMode.PHOTO || settings.lensFacing != LENS_FACING_BACK) return null
        val setup = macroSetup ?: return null
        return if (setup is MacroSetup.Lens && macroLensBroken) MacroSetup.Zoomed(MACRO_ZOOM) else setup
    }

    /** Glides to [ratio] (a zoom preset), one zoom step per frame. */
    fun animateZoomTo(ratio: Float) {
        // A panorama is pinned to 1×: its optics are fixed at start and zoom multiplies frames and memory.
        if (_state.value.panorama != null) return
        val camera = camera ?: return
        val zoom = camera.cameraInfo.zoomState.value ?: return
        val target = ratio.coerceIn(zoom.minZoomRatio, zoom.maxZoomRatio)
        zoomJob?.cancel()
        zoomJob = scope.launch {
            for (step in zoomAnimationSteps(requestedZoomRatio, target, ZOOM_ANIMATION_MS, ZOOM_FRAME_MS)) {
                applyZoom(camera, step)
                delay(ZOOM_FRAME_MS)
            }
        }
    }

    /** Multiplies the zoom by [scaleFactor] (pinch); stops a running glide so the fingers win. */
    fun zoomBy(scaleFactor: Float) {
        if (_state.value.panorama != null) return
        val camera = camera ?: return
        val zoom = camera.cameraInfo.zoomState.value ?: return
        zoomJob?.cancel()
        // Chains from the last requested ratio, which leads the reported one during fast pinches.
        applyZoom(camera, (requestedZoomRatio * scaleFactor).coerceIn(zoom.minZoomRatio, zoom.maxZoomRatio))
    }

    private fun applyZoom(camera: Camera, ratio: Float) {
        requestedZoomRatio = ratio
        camera.cameraControl.setZoomRatio(ratio)
    }

    fun focusAt(x: Float, y: Float) {
        // Re-metering mid-sweep shows as a brightness band and refocusing changes the scale.
        if (_state.value.panorama != null) return
        val camera = camera ?: return
        val factory = target?.meteringFactory ?: return
        val action = FocusMeteringAction.Builder(
            factory.createPoint(x, y),
            FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE,
        )
            .setAutoCancelDuration(FOCUS_AUTO_CANCEL_SECONDS, TimeUnit.SECONDS)
            .build()
        if (camera.cameraInfo.isFocusMeteringSupported(action)) {
            camera.cameraControl.startFocusAndMetering(action)
        }
    }

    fun setExposureIndex(index: Int) {
        if (_state.value.panorama != null) return
        val camera = camera ?: return
        val range = _state.value.capabilities?.exposureRange ?: return
        val clamped = index.coerceIn(range)
        camera.cameraControl.setExposureCompensationIndex(clamped)
        _state.update { it.copy(exposureIndex = clamped) }
    }

    /** The custom portrait's background blur, a fraction of the longer side; the live preview follows at once. */
    fun setPortraitBlur(fraction: Float) {
        _portraitBlur.value = clampBlur(fraction)
    }

    /** Stores the blur once the slider is let go; every move would rewrite the settings and rerun the camera controls. */
    fun persistPortraitBlur() {
        val blur = _portraitBlur.value
        portraitBlurWrite?.cancel()
        portraitBlurWrite = scope.launch { settingsRepository.update { it.copy(portraitBlur = blur) } }
    }

    /** Orientation photos and videos are saved in, as a [Surface] rotation; held while a capture runs. */
    fun setTargetRotation(rotation: Int) {
        targetRotation = rotation
        if (!isLocked()) applyTargetRotation()
    }

    /** Starts the self-timer or takes the photo right away; during a countdown it cancels the countdown. */
    fun takePhoto() {
        if (_state.value.countdown != null) {
            photoJob?.cancel()
            return
        }
        val current = _state.value
        val imageCapture = imageCapture
        if (current.status != SessionStatus.READY || current.busy || current.recording != null || imageCapture == null) {
            return
        }
        _state.update { it.copy(busy = true) }
        photoJob = scope.launch {
            try {
                for (remaining in settings.timer.seconds downTo 1) {
                    _state.update { it.copy(countdown = remaining) }
                    delay(COUNTDOWN_STEP_MS)
                }
                _state.update { it.copy(countdown = null) }
                val shot = shoot(imageCapture)
                _state.update { it.copy(lastCapture = shot.media.uri) }
                _events.tryEmit(SessionEvent.Saved(shot.media, shot.notice))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Capture failed", e)
                _events.tryEmit(SessionEvent.CaptureFailed(e.message ?: e.javaClass.simpleName))
            } finally {
                _state.update { it.copy(busy = false, countdown = null) }
                applyTargetRotation()
                requestBind()
            }
        }
    }

    /** A saved shot; [notice] says why it came out plainer than asked (a custom portrait that did not work). */
    private class Shot(val media: SavedMedia, val notice: String? = null)

    private suspend fun shoot(imageCapture: ImageCapture): Shot = coroutineScope {
        val pressUs = SystemClock.elapsedRealtimeNanos() / 1_000
        val capturedAt = System.currentTimeMillis()
        val location = async {
            if (settings.saveLocation) runCatching { locationProvider.currentOrNull() }.getOrNull() else null
        }
        playSound(MediaActionSound.SHUTTER_CLICK)
        val mirrored = settings.mirrorFrontCamera && _state.value.lensFacing == LENS_FACING_FRONT
        val videoCapture = videoCapture
        val preRoll = preRoll
        when {
            settings.mode == CaptureMode.PORTRAIT && customPortraitBound -> {
                val resolution = settings.portraitResolution
                val blur = _portraitBlur.value
                var outcome = PortraitOutcome.FAILED
                val media = photoCapture.capturePhoto(imageCapture, capturedAt, mirrored, location) { file ->
                    outcome = portraitProcessor.process(file, resolution, blur)
                }
                Shot(media, portraitNotice(outcome)?.let(app::getString))
            }
            settings.mode == CaptureMode.PHOTO && settings.effectiveLivePhoto() && preRoll != null ->
                Shot(photoCapture.captureMotionPhotoPreRoll(imageCapture, preRoll, capturedAt, pressUs, mirrored, location))
            settings.mode == CaptureMode.PHOTO && settings.effectiveLivePhoto() && videoCapture != null ->
                Shot(photoCapture.captureMotionPhoto(imageCapture, videoCapture, capturedAt, mirrored, location))
            else -> Shot(photoCapture.capturePhoto(imageCapture, capturedAt, mirrored, location))
        }
    }

    /** The shutter of the current mode: photo (or self-timer), start/stop a video or a panorama. */
    fun onShutter() {
        val current = _state.value
        when (shutterAction(settings.mode, current.recording != null, current.panorama)) {
            ShutterAction.FINISH_PANORAMA -> finishPanorama()
            ShutterAction.START_PANORAMA -> startPanorama()
            ShutterAction.PHOTO -> takePhoto()
            ShutterAction.STOP_RECORDING -> stopRecording()
            ShutterAction.START_RECORDING -> startRecording()
            ShutterAction.NONE -> Unit
        }
    }

    /** Set by the camera screen while its viewfinder is showing (not under settings or the permission screen). */
    var shutterKeysActive: Boolean = false

    /** Whether the volume and camera keys should fire the shutter right now. */
    val volumeKeyShutter: Boolean get() = settings.volumeKeyShutter && shutterKeysActive

    fun startRecording() {
        val current = _state.value
        if (!settings.mode.isVideo() || current.status != SessionStatus.READY || current.busy || current.recording != null) {
            return
        }
        if (settings.mode == CaptureMode.TIMELAPSE) {
            startTimelapse()
            return
        }
        val videoCapture = videoCapture ?: return
        // High-speed sessions record no sound.
        val withAudio = settings.mode == CaptureMode.VIDEO && videoRecorder.hasAudioPermission()
        val location = if (settings.saveLocation) locationProvider.lastKnownOrNull() else null
        // The service goes first: it must hold the process before the Activity can go away.
        RecordingService.start(app, withAudio)
        try {
            playSound(MediaActionSound.START_VIDEO_RECORDING)
            videoRecorder.start(videoCapture, withAudio, location, ::onRecordEvent)
        } catch (e: RuntimeException) {
            Log.e(TAG, "Starting the recording failed", e)
            RecordingService.stop(app)
            _events.tryEmit(SessionEvent.CaptureFailed(e.message ?: e.javaClass.simpleName))
            return
        }
        _state.update { it.copy(recording = RecordingState(durationNanos = 0, paused = false, withAudio = withAudio)) }
        updateLifecycle()
    }

    fun pauseRecording() = videoRecorder.pause()

    fun resumeRecording() = videoRecorder.resume()

    fun stopRecording() {
        if (_state.value.recording?.kind == RecordingKind.TIMELAPSE) stopTimelapse() else videoRecorder.stop()
    }

    private fun startTimelapse() {
        val analysis = imageAnalysis ?: return
        // No sound in a time-lapse, so the service only needs the camera type.
        RecordingService.start(app, withAudio = false, timelapse = true)
        playSound(MediaActionSound.START_VIDEO_RECORDING)
        val startedAtNs = SystemClock.elapsedRealtimeNanos()
        val startedAtMillis = System.currentTimeMillis()
        timelapseRecorder.start(
            analysis,
            settings.timelapseIntervalMs,
            openOutput = { writer.createPendingVideo(startedAtMillis) },
            onFrame = { frames -> updateRecording { it.copy(frames = frames) } },
            onEncoderFailed = { if (_state.value.recording?.kind == RecordingKind.TIMELAPSE) stopTimelapse() },
        )
        timelapseClock = scope.launch {
            while (true) {
                updateRecording { it.copy(durationNanos = SystemClock.elapsedRealtimeNanos() - startedAtNs) }
                delay(TIMELAPSE_CLOCK_MS)
            }
        }
        _state.update {
            it.copy(recording = RecordingState(0, paused = false, withAudio = false, kind = RecordingKind.TIMELAPSE))
        }
        updateLifecycle()
    }

    private var timelapseStopping = false

    private fun stopTimelapse() {
        if (timelapseStopping) return
        timelapseStopping = true
        timelapseClock?.cancel()
        scope.launch {
            try {
                val result = timelapseRecorder.stop()
                val outcome = classifyTimelapse(result.playable, result.frames, result.encoderFailed)
                val uri = result.uri
                if (uri == null || outcome == TimelapseOutcome.EMPTY || outcome == TimelapseOutcome.FAILED) {
                    // Nothing playable: the pending entry would only leave a broken file behind.
                    if (uri != null) {
                        withContext(Dispatchers.IO) {
                            runCatching { writer.discardVideo(uri) }.onFailure { Log.w(TAG, "Discarding $uri failed", it) }
                        }
                    }
                    val empty = outcome == TimelapseOutcome.EMPTY
                    reportRecordingProblem(app.getString(if (empty) R.string.timelapse_empty else R.string.recording_failed))
                } else {
                    withContext(Dispatchers.IO) { writer.publishVideo(uri) }
                    _state.update { it.copy(lastCapture = uri) }
                    if (outcome == TimelapseOutcome.SAVED) {
                        _events.tryEmit(SessionEvent.Saved(SavedMedia(uri, MediaKind.VIDEO)))
                    } else {
                        // The encoder broke mid-run; what it wrote before is in the gallery.
                        _events.tryEmit(SessionEvent.RecordingInterrupted(uri))
                        if (!uiVisible) RecordingService.notifyResult(app, app.getString(R.string.recording_interrupted))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Saving the time-lapse failed", e)
                reportRecordingProblem(app.getString(R.string.recording_failed))
            } finally {
                timelapseStopping = false
                _state.update { it.copy(recording = null) }
                RecordingService.stop(app)
                playSound(MediaActionSound.STOP_VIDEO_RECORDING)
                updateLifecycle()
                applyTargetRotation()
                applyCameraControls()
                requestBind()
            }
        }
    }

    /** Lens geometry for one panorama, fixed when it starts. */
    private class PanoramaOptics(
        val focalMm: Float,
        val sensorMm: Float,
        val fovDeg: Float,
        val stepDeg: Float,
        val startedAtMillis: Long,
    )

    private var panoramaOptics: PanoramaOptics? = null

    private fun startPanorama() {
        val current = _state.value
        val analysis = imageAnalysis ?: return
        val camera = camera ?: return
        val capabilities = current.capabilities ?: return
        if (current.status != SessionStatus.READY || current.busy || current.panorama != null || !uiVisible) return
        // Right after a mode switch the analysis stream may still be the time-lapse one.
        if (boundKey?.mode != CaptureMode.PANORAMA) return
        // Upright frames turned by 90° or 270° run along the sensor's short side.
        val sensorMm = if (camera.cameraInfo.getSensorRotationDegrees(targetRotation) % 180 == 0) {
            capabilities.sensorWidthMm
        } else {
            capabilities.sensorHeightMm
        }
        // Zoom multiplies the frame count and the canvas, so the panorama runs at 1× (or the nearest the camera
        // allows). Zoom crops every stream, which acts like a longer lens.
        val zoom = camera.cameraInfo.zoomState.value
        val zoomRatio = zoom?.let { 1f.coerceIn(it.minZoomRatio, it.maxZoomRatio) } ?: 1f
        val focalMm = capabilities.focalMm * zoomRatio
        val fovDeg = Math.toDegrees(2 * atan(sensorMm / (2.0 * focalMm))).toFloat()
        val stepDeg = fovDeg / PANORAMA_STEPS_PER_FOV
        val optics = PanoramaOptics(focalMm, sensorMm, fovDeg, stepDeg, System.currentTimeMillis())
        panoramaOptics = optics
        val startPitchDeg = _panoramaAttitude.value?.pitchDeg
        _state.update { it.copy(panorama = PanoramaUiState(capturing = true, yawToNextDeg = stepDeg, startPitchDeg = startPitchDeg)) }
        zoomJob?.cancel()
        requestedZoomRatio = zoomRatio
        // Frames are taken only once the zoom has settled; a stop or cancel in between leaves nothing to start.
        val zoomed = camera.cameraControl.setZoomRatio(zoomRatio)
        zoomed.addListener({
            if (panoramaOptics === optics && _state.value.panorama?.capturing == true) {
                panoramaCapture.start(analysis, stepDeg, targetRotation, ::onPanoramaStatus, onFinished = ::finishPanorama)
            }
        }, ContextCompat.getMainExecutor(app))
    }

    private fun onPanoramaStatus(status: PanoramaStatus) {
        _state.update { state ->
            val panorama = state.panorama?.takeIf { it.capturing } ?: return@update state
            state.copy(
                panorama = panorama.copy(
                    count = status.frames,
                    sweptDeg = status.sweptDeg,
                    level = status.level,
                    yawToNextDeg = status.yawToNextDeg,
                    direction = status.direction,
                ),
            )
        }
    }

    /** Ends the capture and stitches what was taken on a background thread. */
    private fun finishPanorama() {
        if (_state.value.panorama?.capturing != true) return
        val optics = panoramaOptics ?: return
        _state.update { it.copy(panorama = it.panorama?.copy(capturing = false, stitchingProgress = 0f)) }
        panoramaJob = scope.launch {
            try {
                val frames = panoramaCapture.stop()
                if (frames.size < 2) {
                    _events.tryEmit(SessionEvent.CaptureFailed(app.getString(R.string.panorama_too_short)))
                    return@launch
                }
                val location = async {
                    if (settings.saveLocation) runCatching { locationProvider.currentOrNull() }.getOrNull() else null
                }
                val (panorama, jpeg) = withContext(Dispatchers.Default) {
                    val focalPx = focalLengthPx(optics.focalMm, optics.sensorMm, frames.first().width)
                    // The focal length is the first one the lens lists; this lets a phone test check it.
                    Log.i(
                        TAG,
                        "Panorama optics: focalMm=${optics.focalMm} sensorMm=${optics.sensorMm} fovDeg=${optics.fovDeg} " +
                            "stepDeg=${optics.stepDeg} focalPx=$focalPx",
                    )
                    val onProgress = { progress: Float ->
                        _state.update { it.copy(panorama = it.panorama?.copy(stitchingProgress = progress)) }
                    }
                    val stitched = try {
                        stitchPanorama(frames, focalPx, onProgress = onProgress)
                    } catch (_: OutOfMemoryError) {
                        // A wide turn on a small heap: a smaller panorama beats none.
                        Log.w(TAG, "Stitching the panorama ran out of memory; retrying smaller")
                        stitchPanorama(frames, focalPx, PANORAMA_RETRY_SCALE, onProgress)
                    }
                    stitched to stitched.image.toJpeg(PANORAMA_JPEG_QUALITY)
                }
                val savedLocation = location.await()
                // Once writing starts it completes, so a late cancel cannot leave a half-written entry.
                val uri = withContext(NonCancellable + Dispatchers.IO) {
                    writer.savePanorama(jpeg, panorama, optics.startedAtMillis, savedLocation)
                }
                _state.update { it.copy(lastCapture = uri) }
                _events.tryEmit(SessionEvent.Saved(SavedMedia(uri, MediaKind.PANORAMA)))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Saving the panorama failed", e)
                _events.tryEmit(SessionEvent.CaptureFailed(app.getString(R.string.panorama_failed)))
            } catch (e: OutOfMemoryError) {
                // A very wide panorama on a small heap: report it rather than crash.
                Log.e(TAG, "Stitching the panorama ran out of memory", e)
                _events.tryEmit(SessionEvent.CaptureFailed(app.getString(R.string.panorama_failed)))
            } finally {
                panoramaCapture.cancel()
                endPanorama()
            }
        }
    }

    /** Throws away a panorama being taken or stitched; nothing is saved. */
    private fun cancelPanorama() {
        val job = panoramaJob
        when {
            // Its finally block cleans up.
            job != null && job.isActive -> job.cancel()
            _state.value.panorama != null -> {
                panoramaCapture.cancel()
                endPanorama()
            }
        }
    }

    private fun endPanorama() {
        panoramaJob = null
        panoramaOptics = null
        _state.update { it.copy(panorama = null) }
        applyTargetRotation()
        requestBind()
    }

    private fun reportRecordingProblem(message: String) {
        _events.tryEmit(SessionEvent.CaptureFailed(message))
        if (!uiVisible) RecordingService.notifyResult(app, message)
    }

    private fun onRecordEvent(event: VideoRecordEvent) {
        when (event) {
            is VideoRecordEvent.Pause -> updateRecording { it.copy(paused = true) }
            is VideoRecordEvent.Resume -> updateRecording { it.copy(paused = false) }
            is VideoRecordEvent.Finalize -> onRecordingFinalized(event)
            else -> updateRecording { it.copy(durationNanos = event.recordingStats.recordedDurationNanos) }
        }
    }

    private fun updateRecording(transform: (RecordingState) -> RecordingState) {
        _state.update { state -> state.recording?.let { state.copy(recording = transform(it)) } ?: state }
    }

    private fun onRecordingFinalized(event: VideoRecordEvent.Finalize) {
        val outcome = classifyFinalize(event.error)
        val uri = event.outputResults.outputUri.takeIf { it != Uri.EMPTY }
        if (outcome != FinalizeOutcome.SAVED) Log.w(TAG, "Recording finalized with error ${event.error}", event.cause)
        _state.update {
            it.copy(recording = null, lastCapture = if (outcome != FinalizeOutcome.FAILED && uri != null) uri else it.lastCapture)
        }
        RecordingService.stop(app)
        playSound(MediaActionSound.STOP_VIDEO_RECORDING)
        when (outcome) {
            FinalizeOutcome.SAVED -> uri?.let { _events.tryEmit(SessionEvent.Saved(SavedMedia(it, MediaKind.VIDEO))) }
            FinalizeOutcome.SAVED_INTERRUPTED -> {
                _events.tryEmit(SessionEvent.RecordingInterrupted(uri))
                if (!uiVisible) RecordingService.notifyResult(app, app.getString(R.string.recording_interrupted))
            }
            FinalizeOutcome.FAILED -> {
                val message = app.getString(R.string.recording_failed)
                _events.tryEmit(SessionEvent.CaptureFailed(message))
                if (!uiVisible) RecordingService.notifyResult(app, message)
            }
        }
        updateLifecycle()
        applyTargetRotation()
        applyCameraControls()
        requestBind()
    }

    private fun playSound(sound: Int) {
        if (settings.shutterSoundRequired(shutterSoundMandatory)) shutterSound.play(sound)
    }

    private fun isLocked(): Boolean = _state.value.controlsLocked

    private fun updateLifecycle() {
        registry.currentState = if (uiVisible || _state.value.recording != null) {
            Lifecycle.State.STARTED
        } else {
            Lifecycle.State.CREATED
        }
    }

    private fun bindingKey(target: PreviewTarget) = BindingKey(
        mode = settings.mode,
        livePhoto = settings.effectiveLivePhoto(),
        lensFacing = settings.lensFacing,
        backCameraId = selectedBackCameraId.takeIf { settings.lensFacing == LENS_FACING_BACK },
        aspect = settings.aspect,
        videoQuality = settings.videoQuality,
        videoFps60 = settings.videoFps60,
        videoStabilization = settings.videoStabilization,
        mirrorFrontCamera = settings.mirrorFrontCamera,
        displayRotation = target.displayRotation,
        portrait = target.portrait,
        hdr = settings.hdr,
        slowMotionFps = settings.slowMotionFps,
        macro = activeMacro(),
    )

    /** Rebinds when the binding-relevant settings changed; postponed while a capture or recording runs. */
    private fun requestBind() {
        val target = target ?: return
        if (!settingsLoaded || isLocked()) return
        val key = bindingKey(target)
        if (key == boundKey && camera != null) return
        bindJob?.cancel()
        bindJob = scope.launch {
            try {
                bind(key, target)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // CameraX init failures (no usable camera, camera service down) must not kill the app.
                Log.e(TAG, "Opening the camera failed", e)
                fail(app.getString(R.string.camera_error_bind, e.message ?: e.javaClass.simpleName))
            }
        }
    }

    @OptIn(ExperimentalZeroShutterLag::class)
    private suspend fun bind(key: BindingKey, target: PreviewTarget) {
        _state.update { it.copy(status = SessionStatus.OPENING, errorMessage = null) }
        // The analysis stream is about to be replaced (or dropped by another mode).
        stopPortraitPreview()
        stopPreRoll()
        val provider = provider ?: ProcessCameraProvider.awaitInstance(app).also { provider = it }
        val lenses = lenses ?: findLenses(provider).also { lenses = it }

        val selector = selectorFor(provider, key) ?: run {
            fail(app.getString(R.string.camera_error_no_camera))
            return
        }
        if (key.macro is MacroSetup.Lens && macroLensBroken) {
            Log.w(TAG, "The macro camera cannot be selected; zooming the default camera instead")
            requestBind()
            return
        }
        val cameraInfo = provider.getCameraInfo(selector)
        val extensions = extensions(provider)
        val capabilities = CapabilityProbe.probe(
            provider,
            cameraInfo,
            selector,
            extensions,
            hasRotationVector = panoramaCapture.available,
            customPortrait = customPortraitAvailable(),
        ).copy(lenses = lenses, macro = macroSetup.takeIf { cameraInfo.lensFacing == CameraSelector.LENS_FACING_BACK })
        val lensFacing = cameraInfo.lensFacing

        // A stored mode or HDR this camera cannot do (other phone, front camera) is dropped; the settings
        // change starts a new bind.
        val mode = fallbackMode(key.mode, availableModes(capabilities, lensFacing))
        val hdrAvailable = ExtensionMode.HDR in capabilities.extensionModes
        if (mode != key.mode || (key.hdr && !hdrAvailable)) {
            settingsRepository.update { it.copy(mode = mode, hdr = it.hdr && hdrAvailable) }
            return
        }
        if (key.mode == CaptureMode.SLOW_MOTION) {
            bindSlowMotion(key, target, provider, selector, cameraInfo, capabilities)
            return
        }
        val customPortrait = usesCustomPortrait(key.mode, capabilities)
        if (customPortrait) startPortraitModelDownload()
        val extensionMode = when {
            key.mode == CaptureMode.PORTRAIT && !customPortrait -> ExtensionMode.BOKEH
            key.mode == CaptureMode.NIGHT -> ExtensionMode.NIGHT
            key.mode == CaptureMode.PHOTO && key.hdr && key.macro == null -> ExtensionMode.HDR
            else -> null
        }
        val bindSelector = if (extensionMode != null && extensions != null) {
            extensions.getExtensionEnabledCameraSelector(selector, extensionMode)
        } else {
            selector
        }

        val photoAspect = when (key.mode) {
            CaptureMode.VIDEO, CaptureMode.TIMELAPSE -> AspectRatio.RATIO_16_9
            CaptureMode.PORTRAIT, CaptureMode.NIGHT, CaptureMode.PANORAMA -> AspectRatio.RATIO_4_3
            else -> key.aspect.streamAspect()
        }
        val videoQuality = if (key.mode == CaptureMode.VIDEO) {
            resolveVideoQuality(key.videoQuality, capabilities.videoQualities) ?: run {
                fail(app.getString(R.string.camera_error_no_video))
                return
            }
        } else {
            null
        }

        var lastError: RuntimeException? = null
        // A macro lens inside the logical camera: every stream comes from it.
        val physicalId = (key.macro as? MacroSetup.Lens)?.physicalId
        val wantsVideoExtras = key.videoFps60 || key.videoStabilization
        val attempts = bindingAttempts(key.mode, key.livePhoto, wantsVideoExtras, customPortrait)
        for (attempt in attempts) {
            if (attempt.preRoll && preRollBroken) continue
            val kinds = attempt.kinds
            val preview = Preview.Builder().setResolutionSelector(resolutionSelector(photoAspect))
                .apply { usePhysicalCamera(physicalId) }
                .build()
                .also {
                    it.surfaceProvider = target.surfaceProvider
                    routedProvider = target.surfaceProvider
                }
            val imageCapture = if (UseCaseKind.IMAGE in kinds) {
                ImageCapture.Builder()
                    // Minimal shutter lag keeps a live photo's still close to its stamped moment in the clip;
                    // portrait (the maker's or ours), night and HDR are about quality instead.
                    .setCaptureMode(
                        if (attempt.preRoll && cameraInfo.isZslSupported) {
                            // The still should be the moment of the press, which the pre-roll clip is built around.
                            ImageCapture.CAPTURE_MODE_ZERO_SHUTTER_LAG
                        } else if (extensionMode == null && !customPortrait) {
                            ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
                        } else {
                            ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY
                        },
                    )
                    .setResolutionSelector(resolutionSelector(photoAspect))
                    .apply { usePhysicalCamera(physicalId) }
                    .build()
            } else {
                null
            }
            val videoCapture = when {
                UseCaseKind.VIDEO !in kinds -> null
                videoQuality != null -> buildVideoCapture(
                    quality = videoQuality,
                    aspect = AspectRatio.RATIO_16_9,
                    fps60 = attempt.videoExtras && key.videoFps60 && videoQuality in capabilities.fps60Qualities,
                    stabilization = attempt.videoExtras && key.videoStabilization && capabilities.videoStabilization,
                    mirror = key.mirrorFrontCamera,
                    physicalId = physicalId,
                )
                else -> buildVideoCapture(
                    quality = VideoQualitySetting.FHD,
                    aspect = photoAspect,
                    fps60 = false,
                    stabilization = false,
                    mirror = key.mirrorFrontCamera,
                    physicalId = physicalId,
                )
            }
            val imageAnalysis = if (attempt.preRoll) {
                // YUV for the encoder, at most 1080p; the default backpressure drops frames the encoder cannot take.
                ImageAnalysis.Builder()
                    .setResolutionSelector(analysisResolution(photoAspect, preRollSize(photoAspect)))
                    .apply { usePhysicalCamera(physicalId) }
                    .build()
            } else if (UseCaseKind.ANALYSIS in kinds) {
                ImageAnalysis.Builder()
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setResolutionSelector(analysisResolution(photoAspect, analysisSize(key.mode)))
                    .apply { usePhysicalCamera(physicalId) }
                    .build()
            } else {
                null
            }
            val group = UseCaseGroup.Builder().setViewPort(viewPort(key)).addUseCase(preview)
            imageCapture?.let(group::addUseCase)
            videoCapture?.let(group::addUseCase)
            imageAnalysis?.let(group::addUseCase)

            val bound = try {
                provider.unbindAll()
                provider.bindToLifecycle(this, bindSelector, group.build())
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Binding $kinds failed", e)
                lastError = e
                continue
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Binding $kinds failed", e)
                lastError = e
                continue
            }
            // A live photo that had to drop its video stream is not available on this camera.
            val livePhotoAvailable = !(key.mode == CaptureMode.PHOTO && key.livePhoto && UseCaseKind.VIDEO !in kinds && !attempt.preRoll)
            this.imageAnalysis = imageAnalysis
            customPortraitBound = customPortrait
            preRollBound = attempt.preRoll
            onBound(key, bound, preview, imageCapture, videoCapture, capabilities, lensFacing, livePhotoAvailable)
            return
        }
        Log.e(TAG, "Binding use cases failed", lastError)
        if (key.macro is MacroSetup.Lens) {
            // The phone listed the close-focusing camera but will not open it for us: zoom the default one.
            macroLensBroken = true
            requestBind()
            return
        }
        fallBackOrFail(lastError)
    }

    /**
     * A maker mode, HDR or slow motion that the probe offered but the camera then refused falls back to a
     * simpler setup (the settings change rebinds) instead of leaving the user on an error screen.
     */
    private suspend fun fallBackOrFail(error: Throwable?) {
        if (afterBindFailure(settings) != null) {
            _events.tryEmit(SessionEvent.CaptureFailed(app.getString(R.string.mode_unavailable)))
            settingsRepository.update { afterBindFailure(it) ?: it }
            return
        }
        fail(app.getString(R.string.camera_error_bind, error?.message ?: ""))
    }

    /** High-speed recording has its own session shape: one preview and one video stream at a fixed rate. */
    private suspend fun bindSlowMotion(
        key: BindingKey,
        target: PreviewTarget,
        provider: ProcessCameraProvider,
        selector: CameraSelector,
        cameraInfo: CameraInfo,
        capabilities: CameraCapabilities,
    ) {
        val fps = key.slowMotionFps.takeIf { it in capabilities.slowMotionFps } ?: capabilities.slowMotionFps.max()
        val quality = CapabilityProbe.slowMotionQuality(cameraInfo, fps) ?: run {
            fallBackOrFail(null)
            return
        }
        val preview = Preview.Builder().build().also {
            it.surfaceProvider = target.surfaceProvider
            routedProvider = target.surfaceProvider
        }
        val videoCapture = VideoCapture.withOutput(
            Recorder.Builder().setQualitySelector(QualitySelector.from(quality)).build(),
        )
        val session = HighSpeedVideoSessionConfig(videoCapture, preview, android.util.Range(fps, fps), true)
        val bound = try {
            provider.unbindAll()
            provider.bindToLifecycle(this, selector, session)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Binding slow motion failed", e)
            fallBackOrFail(e)
            return
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Binding slow motion failed", e)
            fallBackOrFail(e)
            return
        }
        imageAnalysis = null
        customPortraitBound = false
        preRollBound = false
        onBound(key, bound, preview, null, videoCapture, capabilities, cameraInfo.lensFacing, livePhotoAvailable = true)
    }

    private fun onBound(
        key: BindingKey,
        bound: Camera,
        preview: Preview,
        imageCapture: ImageCapture?,
        videoCapture: VideoCapture<Recorder>?,
        capabilities: CameraCapabilities,
        lensFacing: Int,
        livePhotoAvailable: Boolean,
    ) {
        val sameCamera = boundKey?.let { it.lensFacing == key.lensFacing && it.backCameraId == key.backCameraId } == true
        val wasZoomedMacro = boundKey?.macro is MacroSetup.Zoomed
        zoomJob?.cancel()
        this.camera = bound
        this.preview = preview
        this.imageCapture = imageCapture
        this.videoCapture = videoCapture
        this.boundKey = key
        requestedZoomRatio = bound.cameraInfo.zoomState.value?.zoomRatio ?: 1f
        val macro = key.macro
        bound.cameraInfo.zoomState.value?.let { zoom ->
            when {
                macro is MacroSetup.Zoomed -> applyZoom(bound, macro.zoomRatio.coerceIn(zoom.minZoomRatio, zoom.maxZoomRatio))
                wasZoomedMacro -> applyZoom(bound, 1f.coerceIn(zoom.minZoomRatio, zoom.maxZoomRatio))
            }
        }
        applyTargetRotation()
        applyCameraControls()
        observeCamera(bound)
        _state.update {
            it.copy(
                status = SessionStatus.READY,
                capabilities = capabilities,
                activeCameraId = cameraId(bound),
                lensFacing = lensFacing,
                exposureIndex = 0,
                // Known only when a live photo binding was tried; a plain-photo binding keeps what this camera showed.
                livePhotoAvailable = when {
                    key.mode == CaptureMode.PHOTO && key.livePhoto -> livePhotoAvailable
                    sameCamera -> it.livePhotoAvailable
                    else -> true
                },
            )
        }
        updatePortraitPreview()
        updatePreRoll()
        updatePanoramaLevel()
    }

    /** Runs the panorama level's sensor while Panorama mode is bound and the app is on screen; stops it otherwise. */
    private fun updatePanoramaLevel() {
        if (uiVisible && boundKey?.mode == CaptureMode.PANORAMA) {
            if (levelSensorRunning) return
            levelSensorRunning = true
            levelSensor.start { _, _, rotation -> _panoramaAttitude.value = attitudeOf(rotation) }
        } else {
            levelSensor.stop()
            levelSensorRunning = false
            _panoramaAttitude.value = null
        }
    }

    /** Runs the live photo's pre-roll encoder while it is bound and the app is on screen; stops it otherwise. */
    private fun updatePreRoll() {
        val analysis = imageAnalysis
        if (!preRollBound || !uiVisible || analysis == null || camera == null) {
            stopPreRoll()
            return
        }
        if (preRollAnalysis === analysis) return
        stopPreRoll()
        val mirrored = settings.mirrorFrontCamera && _state.value.lensFacing == LENS_FACING_FRONT
        val recorder = PreRollRecorder(withAudio = videoRecorder.hasAudioPermission(), mirrored = mirrored)
        analysis.setAnalyzer(preRollExecutor, recorder.analyzer)
        recorder.start()
        preRoll = recorder
        preRollAnalysis = analysis
        _state.update { it.copy(livePhotoPreRoll = true) }
        preRollWatch = scope.launch {
            recorder.failed.first { it }
            // Live photos keep working, recorded after the press as before.
            Log.w(TAG, "Pre-roll encoder failed; live photos record after the press")
            preRollBroken = true
            boundKey = null
            requestBind()
        }
    }

    private fun stopPreRoll() {
        preRollWatch?.cancel()
        preRollWatch = null
        preRollAnalysis?.clearAnalyzer()
        preRollAnalysis = null
        _state.update { it.copy(livePhotoPreRoll = false) }
        val recorder = preRoll ?: return
        preRoll = null
        // Joining the microphone thread must not hold up the main thread.
        scope.launch(Dispatchers.Default) { recorder.stop() }
    }

    /** Runs the live portrait while a custom portrait is bound and the app is on screen; stops it otherwise. */
    private fun updatePortraitPreview() {
        val analysis = imageAnalysis
        if (!customPortraitBound || !uiVisible || analysis == null || camera == null) {
            stopPortraitPreview()
            return
        }
        if (portraitAnalysis === analysis) return
        val preview = portraitPreview.value ?: PortraitPreview(subjectMasker, scope) { _portraitBlur.value }.also { portraitPreview.value = it }
        // The preview shows a front camera mirrored, whatever the saved photos do.
        preview.start(analysis, mirrored = _state.value.lensFacing == LENS_FACING_FRONT)
        portraitAnalysis = analysis
    }

    private fun stopPortraitPreview() {
        portraitAnalysis = null
        portraitPreview.value?.stop()
    }

    private suspend fun customPortraitAvailable(): Boolean =
        customPortraitAvailable ?: withContext(Dispatchers.Default) {
            portraitAvailable({ Log.w(TAG, "Custom portrait unavailable", it) }) { subjectMasker.available }
        }
            .also { customPortraitAvailable = it }

    /** Fire and forget: without the segmentation model a portrait is saved as an ordinary photo until it arrives. */
    private fun startPortraitModelDownload() {
        scope.launch {
            try {
                subjectMasker.ensureModel()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Requesting the portrait model failed", e)
            } catch (e: LinkageError) {
                Log.w(TAG, "Requesting the portrait model failed", e)
            }
        }
    }

    /** Loaded once; a phone without maker Extensions (or a failure loading them) yields null. */
    private suspend fun extensions(provider: ProcessCameraProvider): ExtensionsManager? {
        if (!extensionsLoaded) {
            extensionsManager = try {
                ExtensionsManager.getInstance(app, provider)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Camera extensions unavailable", e)
                null
            }
            extensionsLoaded = true
        }
        return extensionsManager
    }

    private fun observeCamera(camera: Camera) {
        cameraObservers?.cancel()
        cameraObservers = scope.launch {
            launch {
                camera.cameraInfo.zoomState.asFlow().collect { zoom ->
                    _state.update { it.copy(zoomRatio = zoom.zoomRatio) }
                }
            }
            launch {
                camera.cameraInfo.cameraState.asFlow().collect { cameraState ->
                    val error = cameraState.error
                    if (error != null) {
                        _state.update { it.copy(status = SessionStatus.ERROR, errorMessage = describe(error.code)) }
                        // A stale blurred frame must not stay over the preview; it starts again once the camera opens.
                        stopPortraitPreview()
                        stopPreRoll()
                        // The Recorder ends a video on its own; a time-lapse keeps what it has.
                        if (_state.value.recording?.kind == RecordingKind.TIMELAPSE) stopTimelapse()
                    } else if (cameraState.type == CameraState.Type.OPEN) {
                        _state.update { it.copy(status = SessionStatus.READY, errorMessage = null) }
                        // Back from an error without a new bind: the live portrait and the pre-roll start again.
                        updatePortraitPreview()
                        updatePreRoll()
                    }
                }
            }
        }
    }

    private fun applyCameraControls() {
        imageCapture?.flashMode = when (settings.flash) {
            FlashSetting.OFF -> ImageCapture.FLASH_MODE_OFF
            FlashSetting.AUTO -> ImageCapture.FLASH_MODE_AUTO
            FlashSetting.ON -> ImageCapture.FLASH_MODE_ON
        }
        val camera = camera ?: return
        if (camera.cameraInfo.hasFlashUnit()) {
            camera.cameraControl.enableTorch(settings.mode.isVideo() && settings.torch)
        }
    }

    private fun applyTargetRotation() {
        imageCapture?.targetRotation = targetRotation
        // The live portrait is drawn over the preview, so its frames turn with the display, not the device.
        imageAnalysis?.targetRotation = boundKey?.displayRotation?.takeIf { customPortraitBound } ?: targetRotation
        videoCapture?.targetRotation = targetRotation
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun selectorFor(provider: ProcessCameraProvider, key: BindingKey): CameraSelector? {
        val macro = key.macro
        if (macro is MacroSetup.Lens) {
            val byId = CameraSelector.Builder()
                .addCameraFilter { infos -> infos.filter { Camera2CameraInfo.from(it).cameraId == macro.cameraId } }
                .build()
            if (provider.hasCameraQuietly(byId)) return byId
            macroLensBroken = true
        }
        val backId = key.backCameraId
        if (backId != null) {
            val byId = CameraSelector.Builder()
                .addCameraFilter { infos -> infos.filter { Camera2CameraInfo.from(it).cameraId == backId } }
                .build()
            if (provider.hasCameraQuietly(byId)) return byId
        }
        val preferred = selectorFor(key.lensFacing)
        if (provider.hasCameraQuietly(preferred)) return preferred
        val other = selectorFor(if (key.lensFacing == LENS_FACING_BACK) LENS_FACING_FRONT else LENS_FACING_BACK)
        return other.takeIf { provider.hasCameraQuietly(it) }
    }

    /** The phone's cameras that CameraX can open (and the physical ones inside them); picks the Macro setup. */
    @OptIn(ExperimentalCamera2Interop::class)
    private fun findLenses(provider: ProcessCameraProvider): List<LensInfo> {
        val openable = provider.availableCameraInfos.map { Camera2CameraInfo.from(it).cameraId }.toSet()
        // Hidden cameras stay in the list for the settings; chooseMacro skips them.
        val lenses = readLenses(app).filter { it.hidden || it.cameraId in openable }
        val defaultBack = provider.availableCameraInfos.firstOrNull { it.lensFacing == CameraSelector.LENS_FACING_BACK }
        if (defaultBack != null) {
            macroSetup = chooseMacro(Camera2CameraInfo.from(defaultBack).cameraId, lenses)
            Log.i(TAG, "Macro: $macroSetup")
        }
        return lenses
    }

    private fun selectorFor(lensFacing: Int) = CameraSelector.Builder().requireLensFacing(lensFacing).build()

    private fun ProcessCameraProvider.hasCameraQuietly(selector: CameraSelector): Boolean =
        try {
            hasCamera(selector)
        } catch (_: androidx.camera.core.CameraInfoUnavailableException) {
            false
        }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun cameraId(camera: Camera): String = Camera2CameraInfo.from(camera.cameraInfo).cameraId

    private fun fail(message: String) {
        stopPortraitPreview()
        stopPreRoll()
        camera = null
        boundKey = null
        _state.update { it.copy(status = SessionStatus.ERROR, errorMessage = message) }
    }

    private fun describe(errorCode: Int): String = app.getString(
        when (errorCode) {
            CameraState.ERROR_CAMERA_IN_USE, CameraState.ERROR_MAX_CAMERAS_IN_USE -> R.string.camera_error_in_use
            CameraState.ERROR_CAMERA_DISABLED -> R.string.camera_error_disabled
            CameraState.ERROR_DO_NOT_DISTURB_MODE_ENABLED -> R.string.camera_error_dnd
            else -> R.string.camera_error_generic
        },
    )

    private fun viewPort(key: BindingKey): ViewPort {
        val (width, height) = when {
            key.mode == CaptureMode.VIDEO || key.mode == CaptureMode.TIMELAPSE -> 9 to 16
            key.mode == CaptureMode.PORTRAIT || key.mode == CaptureMode.NIGHT || key.mode == CaptureMode.PANORAMA -> 3 to 4
            key.aspect == PhotoAspect.RATIO_16_9 -> 9 to 16
            key.aspect == PhotoAspect.RATIO_1_1 -> 1 to 1
            else -> 3 to 4
        }
        val aspect = if (key.portrait) Rational(width, height) else Rational(height, width)
        return ViewPort.Builder(aspect, key.displayRotation).setScaleType(ViewPort.FILL_CENTER).build()
    }

    /** The pre-roll's analysis size: 1080 lines, as wide as the photo's aspect asks. */
    private fun preRollSize(aspect: Int) =
        if (aspect == AspectRatio.RATIO_16_9) android.util.Size(1920, 1080) else android.util.Size(1440, 1080)

    private fun buildVideoCapture(
        quality: VideoQualitySetting,
        aspect: Int,
        fps60: Boolean,
        stabilization: Boolean,
        mirror: Boolean,
        physicalId: String? = null,
    ): VideoCapture<Recorder> {
        val recorder = Recorder.Builder()
            .setQualitySelector(
                QualitySelector.from(
                    CapabilityProbe.toQuality(quality),
                    FallbackStrategy.lowerQualityOrHigherThan(CapabilityProbe.toQuality(quality)),
                ),
            )
            .setAspectRatio(aspect)
            .build()
        return VideoCapture.Builder(recorder)
            .setMirrorMode(if (mirror) MirrorMode.MIRROR_MODE_ON_FRONT_ONLY else MirrorMode.MIRROR_MODE_OFF)
            .setVideoStabilizationEnabled(stabilization)
            .apply { if (fps60) setTargetFrameRate(android.util.Range(FPS_60, FPS_60)) }
            .apply { usePhysicalCamera(physicalId) }
            .build()
    }

    /** Streams from [physicalId], a physical camera inside the bound logical one, when set. */
    @OptIn(ExperimentalCamera2Interop::class)
    private fun <T> ExtendableBuilder<T>.usePhysicalCamera(physicalId: String?) {
        if (physicalId != null) Camera2Interop.Extender(this).setPhysicalCameraId(physicalId)
    }

    private companion object {
        const val TAG = "CameraSession"
        const val FOCUS_AUTO_CANCEL_SECONDS = 3L
        const val FPS_60 = 60
        const val COUNTDOWN_STEP_MS = 1_000L
        const val TIMELAPSE_CLOCK_MS = 500L
        const val EVENT_BUFFER = 64
        const val ZOOM_ANIMATION_MS = 300L
        const val ZOOM_FRAME_MS = 16L

        // 1:1 is cropped from the 4:3 stream by the view port.
        fun PhotoAspect.streamAspect(): Int =
            if (this == PhotoAspect.RATIO_16_9) AspectRatio.RATIO_16_9 else AspectRatio.RATIO_4_3

        // Neighbours overlap by a third: enough for the seam, and fewer frames to hold and join.
        const val PANORAMA_STEPS_PER_FOV = 1.5f
        const val PANORAMA_RETRY_SCALE = 0.6
        const val PANORAMA_JPEG_QUALITY = 92

        /**
         * Analysis frames near 1080p for a time-lapse: enough for video, cheap to convert every interval.
         * Near 1280×960 for a panorama: frames are kept at most 960 px tall anyway.
         * Near 960×720 for the live portrait: it is blurred at 480 px tall.
         */
        fun analysisSize(mode: CaptureMode): android.util.Size = when (mode) {
            CaptureMode.PANORAMA -> android.util.Size(1920, 1440)
            CaptureMode.PORTRAIT -> android.util.Size(960, 720)
            else -> android.util.Size(1920, 1080)
        }

        fun analysisResolution(aspect: Int, size: android.util.Size): ResolutionSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy(aspect, AspectRatioStrategy.FALLBACK_RULE_AUTO))
            .setResolutionStrategy(ResolutionStrategy(size, ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
            .build()

        fun resolutionSelector(aspect: Int): ResolutionSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy(aspect, AspectRatioStrategy.FALLBACK_RULE_AUTO))
            .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
            .build()
    }
}
