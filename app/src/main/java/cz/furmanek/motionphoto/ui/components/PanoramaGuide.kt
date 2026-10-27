package cz.furmanek.motionphoto.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cz.furmanek.motionphoto.R
import cz.furmanek.motionphoto.camera.PanoramaUiState
import kotlin.math.roundToInt

private val GuideRed = Color(0xFFE53935)
private val TargetYellow = Color(0xFFFFD54F)

/** How far the target sits from the middle per degree still to turn; it reaches the middle as a frame is due. */
private val TARGET_DP_PER_DEGREE = 6.dp

/**
 * The panorama guide in the middle of the preview: a level line, a fixed aiming ring in the middle and the target
 * ring, [PanoramaUiState.yawToNextDeg] away in the direction of the sweep. Aiming the middle ring into the target
 * takes the next frame. Below it the frames taken and the degrees swept; the line, the middle ring and a hint turn
 * red while the phone is tilted.
 */
@Composable
fun PanoramaGuide(panorama: PanoramaUiState, modifier: Modifier = Modifier) {
    val lineColor = if (panorama.level) Color.White else GuideRed
    Box(modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            val centerY = size.height / 2
            val ringRadius = 22.dp.toPx()
            drawLine(
                color = lineColor.copy(alpha = 0.8f),
                start = Offset(0f, centerY),
                end = Offset(size.width, centerY),
                strokeWidth = 2.dp.toPx(),
            )
            drawCircle(color = lineColor, radius = ringRadius, center = center, style = Stroke(width = 2.dp.toPx()))
            // The sweep is drawn left to right; turning the other way the target still closes in on the middle.
            val maxOffset = size.width / 2 - ringRadius * 2
            val offset = (panorama.yawToNextDeg.coerceAtLeast(0f) * TARGET_DP_PER_DEGREE.toPx()).coerceAtMost(maxOffset)
            drawCircle(
                color = TargetYellow,
                radius = ringRadius * 0.6f,
                center = Offset(center.x + offset, centerY),
            )
        }
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(top = 140.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.panorama_progress, panorama.count, panorama.sweptDeg.roundToInt()),
                color = Color.White,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.5f))
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            )
            // Always laid out, so the strip above does not jump when the hint comes and goes.
            Text(
                text = stringResource(R.string.panorama_hold_level),
                color = Color.White,
                modifier = Modifier
                    .alpha(if (panorama.level) 0f else 1f)
                    .clip(CircleShape)
                    .background(GuideRed.copy(alpha = 0.85f))
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
    }
}

/** Shown while a finished panorama is stitched: [progress] is 0..1. */
@Composable
fun PanoramaStitching(progress: Float, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black.copy(alpha = 0.6f))
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.panorama_stitching), color = Color.White)
        LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = Modifier.width(200.dp).height(4.dp),
            color = TargetYellow,
            trackColor = Color.White.copy(alpha = 0.3f),
        )
    }
}
