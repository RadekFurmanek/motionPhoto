package cz.furmanek.motionphoto.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.SurfaceTexture
import android.net.Uri
import android.view.OrientationEventListener
import android.view.Surface
import android.view.TextureView
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cz.furmanek.motionphoto.R
import cz.furmanek.motionphoto.camera.PhotoCapture
import cz.furmanek.motionphoto.camera.RecordingKind
import cz.furmanek.motionphoto.camera.SessionEvent
import cz.furmanek.motionphoto.camera.SessionStatus
import cz.furmanek.motionphoto.camera.availableModes
import cz.furmanek.motionphoto.camera.isVideo
import cz.furmanek.motionphoto.camera.usesCustomPortrait
import cz.furmanek.motionphoto.camera.zoomPresets
import cz.furmanek.motionphoto.livephoto.PreRollRecorder
import cz.furmanek.motionphoto.media.MediaKind
import cz.furmanek.motionphoto.settings.CaptureMode
import cz.furmanek.motionphoto.settings.LENS_FACING_BACK
import cz.furmanek.motionphoto.timelapse.timelapseOutputDurationMs
import cz.furmanek.motionphoto.settings.effectiveLivePhoto
import cz.furmanek.motionphoto.ui.components.BlurSlider
import cz.furmanek.motionphoto.ui.components.ExposureSlider
import cz.furmanek.motionphoto.ui.components.GridOverlay
import cz.furmanek.motionphoto.ui.components.rememberPhoneSteady
import cz.furmanek.motionphoto.ui.components.LastCaptureThumbnail
import cz.furmanek.motionphoto.ui.components.LensChips
import cz.furmanek.motionphoto.ui.components.MacroChip
import cz.furmanek.motionphoto.ui.components.ModeBar
import cz.furmanek.motionphoto.ui.components.rememberModeBarState
import cz.furmanek.motionphoto.ui.components.PanoramaGuide
import cz.furmanek.motionphoto.ui.components.PanoramaLevel
import cz.furmanek.motionphoto.ui.components.PanoramaStitching
import cz.furmanek.motionphoto.ui.components.QuickSettingsBar
import cz.furmanek.motionphoto.ui.components.RecordingIndicator
import cz.furmanek.motionphoto.ui.components.ShutterButton
import cz.furmanek.motionphoto.ui.components.ZoomChips
import cz.furmanek.motionphoto.ui.components.outputAspect
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

private const val FOCUS_INDICATOR_VISIBLE_MS = 1_200L
private const val EXPOSURE_SLIDER_VISIBLE_MS = 3_000L
private val DEFAULT_MODES = listOf(CaptureMode.VIDEO, CaptureMode.PHOTO)
private val MODE_SWIPE_THRESHOLD = 48.dp

@Composable
fun CameraScreen(viewModel: CameraViewModel = viewModel()) {
    val state by viewModel.sessionState.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = LocalResources.current
    val displayRotation = LocalView.current.display?.rotation ?: Surface.ROTATION_0
    val isPortrait = LocalConfiguration.current.orientation != Configuration.ORIENTATION_LANDSCAPE
    val snackbarHostState = remember { SnackbarHostState() }
    var showSettings by rememberSaveable { mutableStateOf(false) }

    val capabilities = state.capabilities
    val recording = state.recording
    val locked = state.controlsLocked
    val ready = state.status == SessionStatus.READY

    // FIT_CENTER shows exactly the frame that is saved.
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FIT_CENTER } }

    DisposableEffect(viewModel, displayRotation, isPortrait) {
        val surfaceProvider = previewView.surfaceProvider
        viewModel.attachPreview(surfaceProvider, displayRotation, isPortrait, previewView.meteringPointFactory)
        onDispose { viewModel.detachPreview(surfaceProvider) }
    }
    LifecycleStartEffect(viewModel) {
        viewModel.onUiVisible(true)
        onStopOrDispose { viewModel.onUiVisible(false) }
    }
    DeviceRotationEffect(onRotationChanged = viewModel::setTargetRotation)

    // Volume keys shoot only while the viewfinder is what the user looks at.
    DisposableEffect(viewModel, showSettings) {
        viewModel.setShutterKeysActive(!showSettings)
        onDispose { viewModel.setShutterKeysActive(false) }
    }

    LaunchedEffect(viewModel) {
        // collectLatest + Short: a new result replaces the one on screen instead of queueing behind it.
        viewModel.events.collectLatest { event ->
            snackbarHostState.currentSnackbarData?.dismiss()
            when (event) {
                is SessionEvent.Saved -> {
                    val message = when (event.media.kind) {
                        MediaKind.MOTION_PHOTO -> R.string.saved
                        MediaKind.PHOTO -> R.string.saved_photo
                        MediaKind.VIDEO -> R.string.saved_video
                        MediaKind.PANORAMA -> R.string.saved_panorama
                    }
                    val result = snackbarHostState.showSnackbar(
                        // A notice (portrait saved as an ordinary photo) says more than "saved".
                        message = event.notice ?: resources.getString(message),
                        actionLabel = resources.getString(R.string.view),
                        withDismissAction = true,
                        duration = SnackbarDuration.Short,
                    )
                    if (result == SnackbarResult.ActionPerformed) context.openMedia(event.media.uri)
                }
                is SessionEvent.RecordingInterrupted ->
                    snackbarHostState.showSnackbar(resources.getString(R.string.recording_interrupted))
                is SessionEvent.CaptureFailed ->
                    snackbarHostState.showSnackbar(resources.getString(R.string.error_capture, event.message))
            }
        }
    }

    var focusPoint by remember { mutableStateOf<Offset?>(null) }
    var focusTapCount by remember { mutableIntStateOf(0) }
    LaunchedEffect(focusTapCount) {
        delay(FOCUS_INDICATOR_VISIBLE_MS)
        focusPoint = null
    }
    var exposureInteraction by remember { mutableIntStateOf(0) }
    var exposureVisible by remember { mutableStateOf(false) }
    LaunchedEffect(exposureInteraction) {
        delay(EXPOSURE_SLIDER_VISIBLE_MS)
        exposureVisible = false
    }
    // Held in PORTRÉT with our own portrait: the maker's bokeh has no strength to set.
    var blurInteraction by remember { mutableIntStateOf(0) }
    var blurVisible by remember { mutableStateOf(false) }
    LaunchedEffect(blurInteraction) {
        delay(EXPOSURE_SLIDER_VISIBLE_MS)
        blurVisible = false
    }
    // Coming back to PORTRÉT quickly must not find the slider still open.
    LaunchedEffect(settings.mode) { blurVisible = false }
    val customPortrait = capabilities != null && usesCustomPortrait(settings.mode, capabilities)
    val canAdjustBlur = customPortrait && !state.busy && state.panorama == null
    val exposureRange = capabilities?.exposureRange
    val canAdjustExposure = exposureRange != null && exposureRange.first < exposureRange.last

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        if (state.direct != null) {
            DirectPreview(viewModel::attachDirectSurface, viewModel::setDirectBounds, Modifier.fillMaxSize())
        }
        if (settings.mode == CaptureMode.NIGHT) {
            // A moving phone gets shorter night frames.
            val steady by rememberPhoneSteady()
            LaunchedEffect(steady) { viewModel.setPhoneSteady(steady) }
        }
        if (state.nightBurstMs != null) {
            Text(
                stringResource(R.string.night_hold_still),
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(50))
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            )
        }

        if (customPortrait) PortraitOverlay(viewModel.portraitFrame, Modifier.fillMaxSize())

        // Taps go to the PreviewView's metering factory, which uses the same coordinates as this overlay.
        // The transform detector comes after the tap detector, so it sees pinches first and consumes them.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(viewModel, canAdjustBlur) {
                    detectTapGestures(
                        onLongPress = if (canAdjustBlur) {
                            {
                                exposureVisible = false
                                blurVisible = true
                                blurInteraction++
                            }
                        } else {
                            null
                        },
                    ) { offset ->
                        // Re-metering during a panorama would show in the stitched result.
                        if (state.panorama != null) return@detectTapGestures
                        focusPoint = offset
                        focusTapCount++
                        blurVisible = false
                        exposureVisible = true
                        exposureInteraction++
                        viewModel.focusAt(offset.x, offset.y)
                    }
                }
                .pointerInput(viewModel) {
                    detectTransformGestures { _, _, zoom, _ ->
                        if (zoom != 1f) viewModel.zoomBy(zoom)
                    }
                },
        ) {
            if (settings.grid) GridOverlay(outputAspect(settings.mode, settings.aspect), Modifier.fillMaxSize())
            focusPoint?.let { FocusIndicator(position = it, key = focusTapCount) }
        }

        if (settings.mode == CaptureMode.PANORAMA && state.panorama?.stitchingProgress == null) {
            PanoramaLevel(viewModel.panoramaAttitude, state.panorama, Modifier.fillMaxSize())
        }
        state.panorama?.let { panorama ->
            val stitching = panorama.stitchingProgress
            if (panorama.capturing) {
                PanoramaGuide(panorama)
            } else if (stitching != null) {
                PanoramaStitching(stitching, Modifier.align(Alignment.Center))
            }
        }

        state.countdown?.let {
            Text(
                text = it.toString(),
                color = Color.White,
                fontSize = 96.sp,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            QuickSettingsBar(
                settings = settings,
                capabilities = capabilities,
                locked = locked,
                livePhotoAvailable = state.livePhotoAvailable,
                onUpdate = viewModel::updateSettings,
                onOpenSettings = { showSettings = true },
                direct = state.direct != null,
                directFlash = state.directFlash,
                macro = state.macro,
                macroTorch = state.macroTorch,
                onMacroTorch = viewModel::setMacroTorch,
            )
            recording?.let {
                RecordingIndicator(
                    it.durationNanos,
                    it.paused,
                    Modifier.padding(top = 8.dp),
                    outputMillis = if (it.kind == RecordingKind.TIMELAPSE) timelapseOutputDurationMs(it.frames) else null,
                )
            }
            SnackbarHost(snackbarHostState)
        }

        BottomControls(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(bottom = 16.dp),
            viewModel = viewModel,
            exposureSlider = {
                if (blurVisible && canAdjustBlur) {
                    val blur by viewModel.portraitBlur.collectAsStateWithLifecycle()
                    BlurSlider(
                        fraction = blur,
                        onChange = viewModel::setPortraitBlur,
                        onChangeFinished = viewModel::persistPortraitBlur,
                        onInteraction = { blurInteraction++ },
                    )
                } else if (exposureVisible && canAdjustExposure && state.panorama == null) {
                    ExposureSlider(
                        index = state.exposureIndex,
                        range = exposureRange,
                        onChange = viewModel::setExposureIndex,
                        onInteraction = { exposureInteraction++ },
                    )
                }
            },
        )

        if (!ready && state.errorMessage != null) {
            Text(
                text = state.errorMessage.orEmpty(),
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(32.dp),
            )
        }

        if (showSettings) {
            SettingsScreen(
                settings = settings,
                capabilities = capabilities,
                shutterSoundMandatory = viewModel.shutterSoundMandatory,
                onUpdate = viewModel::updateSettings,
                hiddenProbe = viewModel.hiddenProbe.collectAsStateWithLifecycle().value,
                probeStages = viewModel.probeStages.collectAsStateWithLifecycle().value,
                probing = viewModel.probing.collectAsStateWithLifecycle().value,
                onProbeHidden = viewModel::probeHiddenCameras,
                onBack = { showSettings = false },
            )
        }
    }
}

@Composable
private fun BottomControls(
    viewModel: CameraViewModel,
    exposureSlider: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.sessionState.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val capabilities = state.capabilities
    val recording = state.recording
    val panorama = state.panorama
    val locked = state.controlsLocked
    val ready = state.status == SessionStatus.READY
    val isVideo = settings.mode.isVideo()
    val modes = capabilities?.let { availableModes(it, state.lensFacing) } ?: DEFAULT_MODES
    val swipeThresholdPx = with(LocalDensity.current) { MODE_SWIPE_THRESHOLD.toPx() }
    val modeBar = rememberModeBarState(modes.indexOf(settings.mode).coerceAtLeast(0))
    val scope = rememberCoroutineScope()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .pointerInput(locked, modes) {
                if (locked) return@pointerInput
                // The mode labels slide with the finger and settle on the nearest one when it lets go.
                val release = {
                    val next = modeBar.release(swipeThresholdPx).coerceIn(modes.indices)
                    scope.launch(start = CoroutineStart.UNDISPATCHED) { modeBar.settle(next) }
                    if (modes[next] != viewModel.settings.value.mode) viewModel.setMode(modes[next])
                }
                detectHorizontalDragGestures(
                    onDragStart = { modeBar.startDrag() },
                    onDragEnd = release,
                    onDragCancel = release,
                ) { _, dragAmount -> modeBar.dragBy(dragAmount) }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        exposureSlider()

        // A panorama runs at 1× on the lens it started with.
        if (ready && capabilities != null && panorama == null) {
            val lenses = capabilities.backLenses
            val macroOffered = capabilities.macro != null && settings.mode == CaptureMode.PHOTO && state.lensFacing == LENS_FACING_BACK
            if (lenses.isNotEmpty() && state.lensFacing == LENS_FACING_BACK && !state.macro) {
                LensChips(lenses, state.activeCameraId, onSelect = { if (!locked) viewModel.selectBackCamera(it) })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (macroOffered) MacroChip(active = state.macro, onClick = { if (!locked) viewModel.setMacro(!state.macro) })
                // Macro picks its own lens and zoom, so no zoom chip is lit; picking one ends Macro.
                val ultraWideZoom = capabilities.ultraWideZoom.takeIf { settings.mode == CaptureMode.PHOTO && state.lensFacing == LENS_FACING_BACK }
                ZoomChips(
                    zoomPresets(capabilities.minZoom, capabilities.maxZoom, ultraWideZoom),
                    state.zoomRatio,
                    onSelect = { if (!locked) viewModel.setZoom(it) },
                    highlight = !state.macro,
                )
            }
        }

        ModeBar(modes, settings.mode, enabled = !locked, onSelect = viewModel::setMode, state = modeBar)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (panorama != null || recording?.kind == RecordingKind.TIMELAPSE) {
                // A time-lapse or panorama has nothing to pause, and opening the gallery would end a panorama.
                Spacer(Modifier.size(56.dp))
            } else if (recording != null) {
                RoundButton(
                    onClick = if (recording.paused) viewModel::resumeRecording else viewModel::pauseRecording,
                ) {
                    Icon(
                        if (recording.paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                        contentDescription = stringResource(if (recording.paused) R.string.resume else R.string.pause),
                        tint = Color.White,
                    )
                }
            } else {
                LastCaptureThumbnail(state.lastCapture, onClick = { context.openMedia(it) })
            }

            ShutterButton(
                isVideo = isVideo,
                isRecording = recording != null || panorama?.capturing == true,
                showClipProgress = state.nightBurstMs != null || state.busy && state.countdown == null &&
                    settings.mode == CaptureMode.PHOTO && settings.effectiveLivePhoto() && state.livePhotoAvailable,
                // With the pre-roll most of the clip is already recorded at the press; a night burst fills it in its time.
                clipDurationMs = state.nightBurstMs
                    ?: if (state.livePhotoPreRoll) PreRollRecorder.POST_ROLL_MS else PhotoCapture.CLIP_DURATION_MS,
                // Portrait (the maker's or ours), night and HDR processing can take seconds.
                processing = state.busy && state.countdown == null &&
                    (settings.mode == CaptureMode.PORTRAIT || settings.mode == CaptureMode.NIGHT || settings.hdr),
                // During the self-timer the shutter cancels the countdown; a panorama being stitched cannot be stopped.
                enabled = ready && (!state.busy || state.countdown != null) && panorama?.stitchingProgress == null,
                onClick = viewModel::onShutter,
                isPanorama = settings.mode == CaptureMode.PANORAMA,
            )

            val canSwitch = capabilities?.let { it.hasFrontCamera && it.hasBackCamera } == true && !locked
            RoundButton(onClick = viewModel::switchCamera, enabled = canSwitch) {
                Icon(
                    imageVector = Icons.Filled.Cameraswitch,
                    contentDescription = stringResource(R.string.switch_camera),
                    tint = if (canSwitch) Color.White else Color.Gray,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun RoundButton(onClick: () -> Unit, enabled: Boolean = true, content: @Composable () -> Unit) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.4f)),
    ) { content() }
}

@Composable
private fun FocusIndicator(position: Offset, key: Int) {
    val scale = remember(key) { Animatable(1.4f) }
    LaunchedEffect(key) { scale.animateTo(1f, tween(durationMillis = 250)) }
    Canvas(modifier = Modifier.fillMaxSize()) {
        drawCircle(
            color = Color.White,
            radius = 36.dp.toPx() * scale.value,
            center = position,
            style = Stroke(width = 2.dp.toPx()),
        )
    }
}

/**
 * Reports the physical device rotation as a [Surface] rotation constant. The activity is locked to portrait,
 * so this, not the display rotation, decides whether a photo is saved as landscape.
 */
@Composable
private fun DeviceRotationEffect(onRotationChanged: (Int) -> Unit) {
    val context = LocalContext.current
    DisposableEffect(context, onRotationChanged) {
        val listener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                onRotationChanged(
                    when (orientation) {
                        in 45 until 135 -> Surface.ROTATION_270
                        in 135 until 225 -> Surface.ROTATION_180
                        in 225 until 315 -> Surface.ROTATION_90
                        else -> Surface.ROTATION_0
                    },
                )
            }
        }
        listener.enable()
        onDispose { listener.disable() }
    }
}

private fun Context.openMedia(uri: Uri) {
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, contentResolver.getType(uri))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        // No gallery app installed; the capture is still saved in DCIM/Camera.
    }
}

/**
 * The hidden camera's preview: a 4:3 frame upright and centred like the CameraX preview, so the overlays line up.
 * Its texture goes to the session, which opens the camera once it has one; its place goes there too, for taps.
 */
@Composable
private fun DirectPreview(
    onTexture: (SurfaceTexture?) -> Unit,
    onBounds: (Float, Float, Float, Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { context ->
                TextureView(context).apply {
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) = onTexture(texture)

                        override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit

                        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                            // The session closes the camera before the texture is released.
                            onTexture(null)
                            return true
                        }

                        override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .onGloballyPositioned { coordinates ->
                    val position = coordinates.positionInParent()
                    onBounds(position.x, position.y, coordinates.size.width.toFloat(), coordinates.size.height.toFloat())
                },
        )
    }
}

/**
 * The live portrait's blurred background: the same 3:4 frame as the preview, fitted the same way, and
 * transparent over the subject, which stays the sharp preview. It takes no touches, so taps still focus.
 * Collected here, so a new frame (up to 20 a second) recomposes only this overlay, not the whole screen.
 */
@Composable
private fun PortraitOverlay(frames: Flow<ImageBitmap?>, modifier: Modifier = Modifier) {
    val frame by frames.collectAsStateWithLifecycle(initialValue = null)
    // The blurred layer lags behind the live preview, so it would slide while the phone moves: fade it out then.
    val steady by rememberPhoneSteady()
    val alpha by animateFloatAsState(if (steady) 1f else 0f, tween(if (steady) 250 else 120), label = "portrait-blur")
    frame?.let {
        Image(bitmap = it, contentDescription = null, contentScale = ContentScale.Fit, alpha = alpha, modifier = modifier)
    }
}
