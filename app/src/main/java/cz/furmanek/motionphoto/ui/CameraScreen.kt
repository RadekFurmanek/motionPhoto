package cz.furmanek.motionphoto.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.view.OrientationEventListener
import android.view.Surface
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cz.furmanek.motionphoto.R
import cz.furmanek.motionphoto.camera.PhotoCapture
import cz.furmanek.motionphoto.camera.SessionEvent
import cz.furmanek.motionphoto.camera.SessionStatus
import kotlinx.coroutines.delay

private const val FOCUS_INDICATOR_VISIBLE_MS = 1_200L

@Composable
fun CameraScreen(viewModel: CameraViewModel = viewModel()) {
    val state by viewModel.sessionState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = LocalResources.current
    val displayRotation = LocalView.current.display?.rotation ?: Surface.ROTATION_0
    val isPortrait = LocalConfiguration.current.orientation != Configuration.ORIENTATION_LANDSCAPE
    val snackbarHostState = remember { SnackbarHostState() }

    // FIT_CENTER shows the full 4:3 frame that ends up in the photo and the video.
    val previewView = remember {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FIT_CENTER }
    }

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

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is SessionEvent.Saved -> {
                    val result = snackbarHostState.showSnackbar(
                        message = resources.getString(R.string.saved),
                        actionLabel = resources.getString(R.string.view),
                        withDismissAction = true,
                    )
                    if (result == SnackbarResult.ActionPerformed) context.openImage(event.uri)
                }
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

        // Taps go to the PreviewView's metering factory, which uses the same coordinates as this overlay.
        // The transform detector comes after the tap detector, so it sees pinches first and consumes them.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(viewModel) {
                    detectTapGestures { offset ->
                        focusPoint = offset
                        focusTapCount++
                        viewModel.focusAt(offset.x, offset.y)
                    }
                }
                .pointerInput(viewModel) {
                    detectTransformGestures { _, _, zoom, _ ->
                        if (zoom != 1f) viewModel.zoomBy(zoom)
                    }
                },
        ) {
            focusPoint?.let { FocusIndicator(position = it, key = focusTapCount) }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing),
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            state.errorMessage?.let {
                Text(text = it, color = Color.White, modifier = Modifier.padding(bottom = 16.dp))
            }
            if (state.status == SessionStatus.READY) {
                Text(
                    text = stringResource(R.string.zoom_ratio, state.zoomRatio),
                    color = Color.White,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.4f))
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
            Spacer(Modifier.size(24.dp))
            val canSwitch = state.capabilities?.let { it.hasFrontCamera && it.hasBackCamera } == true && !state.busy
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.size(56.dp))
                ShutterButton(
                    enabled = state.status == SessionStatus.READY && !state.busy,
                    isCapturing = state.busy,
                    onClick = viewModel::capture,
                )
                IconButton(
                    onClick = viewModel::switchCamera,
                    enabled = canSwitch,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.4f)),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Cameraswitch,
                        contentDescription = stringResource(R.string.switch_camera),
                        tint = if (canSwitch) Color.White else Color.Gray,
                    )
                }
            }
        }
    }
}

@Composable
private fun ShutterButton(enabled: Boolean, isCapturing: Boolean, onClick: () -> Unit) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(isCapturing) {
        progress.snapTo(0f)
        if (isCapturing) {
            progress.animateTo(1f, tween(PhotoCapture.CLIP_DURATION_MS.toInt(), easing = LinearEasing))
        }
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(80.dp)
            .clip(CircleShape)
            .border(4.dp, Color.White, CircleShape)
            .clickable(
                enabled = enabled,
                role = Role.Button,
                onClickLabel = stringResource(R.string.shutter),
                onClick = onClick,
            ),
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(if (enabled) Color.White else Color.White.copy(alpha = 0.5f)),
        )
        if (isCapturing) {
            CircularProgressIndicator(
                progress = { progress.value },
                modifier = Modifier.size(80.dp),
                color = Color.Red,
                strokeWidth = 4.dp,
                trackColor = Color.Transparent,
            )
        }
    }
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

private fun android.content.Context.openImage(uri: Uri) {
    val intent = Intent(Intent.ACTION_VIEW, uri)
        .setDataAndType(uri, "image/jpeg")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        // No gallery app installed; the photo is still saved in DCIM/Camera.
    }
}
