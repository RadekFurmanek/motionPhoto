package cz.furmanek.motionphoto.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LocalFlorist
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cz.furmanek.motionphoto.R
import cz.furmanek.motionphoto.camera.LensOption
import kotlin.math.abs

private const val MATCH_TOLERANCE = 0.05f
private val ActiveYellow = Color(0xFFFFD54F)

fun formatZoom(ratio: Float): String =
    if (abs(ratio - ratio.toInt()) < MATCH_TOLERANCE) "${ratio.toInt()}×" else "%.1f×".format(ratio)

/** Zoom presets; the active chip shows the exact zoom when it sits between presets. Without [highlight] none is lit. */
@Composable
fun ZoomChips(presets: List<Float>, zoomRatio: Float, onSelect: (Float) -> Unit, modifier: Modifier = Modifier, highlight: Boolean = true) {
    val active = if (highlight) presets.lastOrNull { it <= zoomRatio + MATCH_TOLERANCE } ?: presets.first() else null
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        presets.forEach { preset ->
            val isActive = preset == active
            Chip(
                label = if (isActive) formatZoom(zoomRatio) else formatZoom(preset),
                active = isActive,
                onClick = { onSelect(preset) },
            )
        }
    }
}

/** Separately exposed back cameras, labelled by their zoom relative to the default camera. */
@Composable
fun LensChips(lenses: List<LensOption>, activeCameraId: String?, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        lenses.forEach { lens ->
            Chip(
                label = formatZoom(lens.intrinsicZoomRatio),
                active = lens.cameraId == activeCameraId,
                onClick = { onSelect(lens.cameraId) },
            )
        }
    }
}

/** Macro on or off: a flower, yellow while on. */
@Composable
fun MacroChip(active: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(if (active) 44.dp else 36.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable(onClickLabel = stringResource(R.string.macro), onClick = onClick),
    ) {
        Icon(
            Icons.Outlined.LocalFlorist,
            contentDescription = stringResource(R.string.macro),
            tint = if (active) ActiveYellow else Color.White,
            modifier = Modifier.size(if (active) 22.dp else 18.dp),
        )
    }
}

@Composable
private fun Chip(label: String, active: Boolean, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(if (active) 44.dp else 36.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable(onClick = onClick),
    ) {
        Text(label, color = if (active) ActiveYellow else Color.White, fontSize = if (active) 13.sp else 11.sp)
    }
}
