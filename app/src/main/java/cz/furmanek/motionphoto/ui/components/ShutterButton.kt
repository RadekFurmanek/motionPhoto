package cz.furmanek.motionphoto.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import cz.furmanek.motionphoto.R
import cz.furmanek.motionphoto.camera.PhotoCapture
import kotlinx.coroutines.launch

private val RecordRed = Color(0xFFE53935)
private val RingWidth = 4.dp

/**
 * Photo: white disc that dips when pressed. While a live photo's clip records, a red ring fills
 * the outer circle; once the clip is done the ring spins until the photo is saved.
 * Video: red disc to start, red square to stop.
 * Panorama: white disc with a start arrow, red square to finish.
 */
@Composable
fun ShutterButton(
    isVideo: Boolean,
    isRecording: Boolean,
    showClipProgress: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    processing: Boolean = false,
    isPanorama: Boolean = false,
) {
    val scope = rememberCoroutineScope()
    val press = remember { Animatable(1f) }
    val progress = remember { Animatable(0f) }
    var clipRecorded by remember { mutableStateOf(false) }
    LaunchedEffect(showClipProgress) {
        clipRecorded = false
        progress.snapTo(0f)
        if (showClipProgress) {
            progress.animateTo(1f, tween(PhotoCapture.CLIP_DURATION_MS.toInt(), easing = LinearEasing))
            clipRecorded = true
        }
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(80.dp)
            .clip(CircleShape)
            .clickable(
                enabled = enabled,
                role = Role.Button,
                onClickLabel = stringResource(
                    when {
                        !isPanorama -> R.string.shutter
                        isRecording -> R.string.panorama_stop
                        else -> R.string.panorama_start
                    },
                ),
                onClick = {
                    scope.launch {
                        press.animateTo(0.82f, tween(70))
                        press.animateTo(1f, tween(160))
                    }
                    onClick()
                },
            ),
    ) {
        val alpha = if (enabled) 1f else 0.5f
        val inner = Modifier.scale(press.value)
        when {
            isRecording -> Box(inner.size(32.dp).clip(RoundedCornerShape(6.dp)).background(RecordRed))
            isPanorama -> Box(
                inner.size(64.dp).clip(CircleShape).background(Color.White.copy(alpha = alpha)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = Color.Black.copy(alpha = alpha),
                    modifier = Modifier.size(36.dp),
                )
            }
            isVideo -> Box(inner.size(64.dp).clip(CircleShape).background(RecordRed.copy(alpha = alpha)))
            else -> Box(inner.size(64.dp).clip(CircleShape).background(Color.White.copy(alpha = alpha)))
        }
        when {
            showClipProgress && !clipRecorded -> OuterRing(Color.White, RecordRed, startDeg = -90f, sweepDeg = 360f * progress.value)
            showClipProgress || processing -> SpinningRing()
            else -> OuterRing(Color.White)
        }
    }
}

/** Indeterminate ring: the photo is still being processed or saved. */
@Composable
private fun SpinningRing() {
    val rotation by rememberInfiniteTransition(label = "shutter-spin").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart),
        label = "shutter-spin-rotation",
    )
    OuterRing(Color.White.copy(alpha = 0.3f), Color.White, startDeg = rotation - 90f, sweepDeg = 100f)
}

/**
 * The shutter's outer circle with an optional arc on top. Drawn on one Canvas because
 * Modifier.border paints over the content and hid the live photo progress.
 */
@Composable
private fun OuterRing(base: Color, arc: Color = Color.Transparent, startDeg: Float = 0f, sweepDeg: Float = 0f) {
    Canvas(Modifier.size(80.dp)) {
        val stroke = RingWidth.toPx()
        val topLeft = Offset(stroke / 2, stroke / 2)
        val arcSize = Size(size.width - stroke, size.height - stroke)
        drawArc(base, 0f, 360f, useCenter = false, topLeft = topLeft, size = arcSize, style = Stroke(stroke))
        if (sweepDeg > 0f) {
            drawArc(arc, startDeg, sweepDeg, useCenter = false, topLeft = topLeft, size = arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        }
    }
}
