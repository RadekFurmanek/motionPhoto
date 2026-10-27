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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cz.furmanek.motionphoto.R
import cz.furmanek.motionphoto.camera.PanoramaUiState
import cz.furmanek.motionphoto.panorama.Attitude
import cz.furmanek.motionphoto.panorama.levelErrorDeg
import kotlinx.coroutines.flow.Flow
import kotlin.math.abs
import kotlin.math.roundToInt

private val GuideRed = Color(0xFFE53935)
private val TargetYellow = Color(0xFFFFD54F)

/** The level line's colour while the phone is held straight. */
internal val LevelYellow = TargetYellow

/** Sideways roll still counted as straight, in degrees. */
private const val LEVEL_TOLERANCE_DEG = 2f

/** How far the target sits from the middle per degree still to turn (or tilted); it reaches the middle as a frame is due. */
private val TARGET_DP_PER_DEGREE = 6.dp

/**
 * The panorama's level and aim, drawn while Panorama mode is on screen: a horizon line turned against the phone's
 * sideways roll, yellow while the phone is straight. While [panorama] captures, also a fixed aiming ring in the
 * middle (red while the phone tilts too far) and the target dot: [PanoramaUiState.yawToNextDeg] away in the
 * direction of the sweep, and as far below or above the middle as the phone has tilted up or down since the start.
 * Aiming the ring onto the dot takes the next frame. Collected here, so the sensor (about 60 readings a second)
 * recomposes only this layer.
 */
@Composable
fun PanoramaLevel(attitudes: Flow<Attitude?>, panorama: PanoramaUiState?, modifier: Modifier = Modifier) {
    val attitude by attitudes.collectAsStateWithLifecycle(initialValue = null)
    Canvas(modifier.fillMaxSize()) {
        val current = attitude
        val ringRadius = 22.dp.toPx()
        val roll = current?.rollDeg ?: 0f
        val lineColor = if (current == null) Color.White else levelLineColor(roll)
        rotate(-roll, center) {
            // Longer than the screen, so the turned line still reaches both edges.
            drawLine(
                color = lineColor.copy(alpha = 0.8f),
                start = Offset(center.x - size.maxDimension, center.y),
                end = Offset(center.x + size.maxDimension, center.y),
                strokeWidth = 2.dp.toPx(),
            )
        }
        val capturing = panorama?.takeIf { it.capturing } ?: return@Canvas
        drawCircle(
            color = if (capturing.level) Color.White else GuideRed,
            radius = ringRadius,
            center = center,
            style = Stroke(width = 2.dp.toPx()),
        )
        val dx = targetOffsetPx(
            yawToNextDeg = capturing.yawToNextDeg,
            direction = capturing.direction,
            pxPerDegree = TARGET_DP_PER_DEGREE.toPx(),
            maxOffsetPx = size.width / 2 - ringRadius * 2,
        )
        val startPitch = capturing.startPitchDeg
        val dy = if (current != null && startPitch != null) {
            targetVerticalOffsetPx(
                pitchDeg = current.pitchDeg,
                startPitchDeg = startPitch,
                pxPerDegree = TARGET_DP_PER_DEGREE.toPx(),
                maxOffsetPx = size.height / 2 - ringRadius * 2,
            )
        } else {
            0f
        }
        drawCircle(color = TargetYellow, radius = ringRadius * 0.6f, center = Offset(center.x + dx, center.y + dy))
    }
}

/**
 * The panorama's progress below the middle: the frames taken and the degrees swept, and a hint while the phone
 * tilts too far.
 */
@Composable
fun PanoramaGuide(panorama: PanoramaUiState, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize()) {
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

/** Yellow while [rollDeg] is within [LEVEL_TOLERANCE_DEG] of an upright or sideways hold, white otherwise. */
internal fun levelLineColor(rollDeg: Float): Color =
    if (abs(levelErrorDeg(rollDeg)) <= LEVEL_TOLERANCE_DEG) LevelYellow else Color.White

/**
 * Vertical offset of the target dot from the middle, downward positive: the target stays at the height the
 * panorama started at, so tilting up by [pitchDeg] − [startPitchDeg] moves it down. Capped at [maxOffsetPx].
 */
internal fun targetVerticalOffsetPx(pitchDeg: Float, startPitchDeg: Float, pxPerDegree: Float, maxOffsetPx: Float): Float {
    val limit = maxOffsetPx.coerceAtLeast(0f)
    return ((pitchDeg - startPitchDeg) * pxPerDegree).coerceIn(-limit, limit)
}

/**
 * Horizontal offset of the target dot from the middle: [yawToNextDeg] scaled and capped at [maxOffsetPx], on the
 * side of the sweep [direction]. Before the first move (0) it sits on the right.
 */
internal fun targetOffsetPx(yawToNextDeg: Float, direction: Int, pxPerDegree: Float, maxOffsetPx: Float): Float {
    val offset = (yawToNextDeg.coerceAtLeast(0f) * pxPerDegree).coerceIn(0f, maxOffsetPx.coerceAtLeast(0f))
    return if (direction < 0) -offset else offset
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
