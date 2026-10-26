package cz.furmanek.motionphoto.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import cz.furmanek.motionphoto.R
import cz.furmanek.motionphoto.camera.PhotoCapture

private val RecordRed = Color(0xFFE53935)

/**
 * Photo: white disc, with a progress ring while a live photo's clip records.
 * Video: red disc to start, red square to stop.
 */
@Composable
fun ShutterButton(
    isVideo: Boolean,
    isRecording: Boolean,
    showClipProgress: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    processing: Boolean = false,
) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(showClipProgress) {
        progress.snapTo(0f)
        if (showClipProgress) progress.animateTo(1f, tween(PhotoCapture.CLIP_DURATION_MS.toInt(), easing = LinearEasing))
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(80.dp)
            .clip(CircleShape)
            .border(4.dp, Color.White, CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = stringResource(R.string.shutter), onClick = onClick),
    ) {
        val alpha = if (enabled) 1f else 0.5f
        when {
            isRecording -> Box(Modifier.size(32.dp).clip(RoundedCornerShape(6.dp)).background(RecordRed))
            isVideo -> Box(Modifier.size(64.dp).clip(CircleShape).background(RecordRed.copy(alpha = alpha)))
            else -> Box(Modifier.size(64.dp).clip(CircleShape).background(Color.White.copy(alpha = alpha)))
        }
        if (processing) {
            CircularProgressIndicator(
                modifier = Modifier.size(80.dp),
                color = Color.White,
                strokeWidth = 4.dp,
                trackColor = Color.Transparent,
            )
        }
        if (showClipProgress) {
            CircularProgressIndicator(
                progress = { progress.value },
                modifier = Modifier.size(80.dp),
                color = RecordRed,
                strokeWidth = 4.dp,
                trackColor = Color.Transparent,
            )
        }
    }
}
