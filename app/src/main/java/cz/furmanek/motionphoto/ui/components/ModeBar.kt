package cz.furmanek.motionphoto.ui.components

import androidx.annotation.StringRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import cz.furmanek.motionphoto.R
import cz.furmanek.motionphoto.settings.CaptureMode
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

@StringRes
fun CaptureMode.labelRes(): Int = when (this) {
    CaptureMode.TIMELAPSE -> R.string.mode_timelapse
    CaptureMode.SLOW_MOTION -> R.string.mode_slow_motion
    CaptureMode.VIDEO -> R.string.mode_video
    CaptureMode.PHOTO -> R.string.mode_photo
    CaptureMode.PORTRAIT -> R.string.mode_portrait
    CaptureMode.NIGHT -> R.string.mode_night
    CaptureMode.PANORAMA -> R.string.mode_panorama
}

private val LABEL_GAP = 8.dp

/**
 * The x (px) under the bar's middle at [position], a mode index where 1.5 is half way between the second and
 * third label; [centers] are the label centres from the last layout.
 */
fun centerAt(centers: FloatArray, position: Float): Float {
    if (centers.isEmpty()) return 0f
    val p = position.coerceIn(0f, (centers.size - 1).toFloat())
    val i = floor(p).toInt().coerceAtMost(centers.size - 2).coerceAtLeast(0)
    if (centers.size == 1) return centers[0]
    return centers[i] + (centers[i + 1] - centers[i]) * (p - i)
}

/** The position after the finger moved [dx] px from [position]: the labels slide with the finger. */
fun positionAfterDrag(centers: FloatArray, position: Float, dx: Float): Float {
    if (centers.size < 2) return 0f
    val x = centerAt(centers, position) - dx
    if (x <= centers.first()) return 0f
    if (x >= centers.last()) return (centers.size - 1).toFloat()
    val i = (0 until centers.size - 1).first { x <= centers[it + 1] }
    return i + (x - centers[i]) / (centers[i + 1] - centers[i])
}

/**
 * The mode a drag that began on [start] and was let go at [shown] picks: the nearest label, but a swipe of at
 * least [thresholdPx] always moves on by one, as a quick flick should.
 */
fun modeAfterDrag(start: Int, shown: Float, draggedPx: Float, thresholdPx: Float, count: Int): Int {
    val nearest = shown.roundToInt().coerceIn(0, count - 1)
    if (nearest != start || abs(draggedPx) < thresholdPx) return nearest
    // Swiping left moves to the mode on the right, as in other camera apps.
    return (start + if (draggedPx < 0) 1 else -1).coerceIn(0, count - 1)
}

/** Where the mode bar is scrolled, shared with the swipe on the controls around it. */
@Stable
class ModeBarState(initial: Int) {
    private val position: Animatable<Float, AnimationVector1D> = Animatable(initial.toFloat())
    private var dragPx by mutableFloatStateOf(0f)
    private var start = initial

    /** Label centres from the last layout, px from the first label's left edge. */
    internal var centers = FloatArray(0)

    var dragging by mutableStateOf(false)
        private set

    /** The mode index under the bar's middle, fractional while it slides. */
    val shown: Float get() = if (dragging) positionAfterDrag(centers, position.value, dragPx) else position.value

    fun startDrag() {
        start = shown.roundToInt()
        dragPx = 0f
        dragging = true
    }

    fun dragBy(dx: Float) {
        dragPx += dx
    }

    /** Ends the drag: the mode it picks, see [modeAfterDrag]; then [settle] on it. */
    fun release(thresholdPx: Float): Int = modeAfterDrag(start, shown, dragPx, thresholdPx, centers.size.coerceAtLeast(1))

    /** Slides from where the finger left the bar to [index]. */
    suspend fun settle(index: Int) {
        if (dragging) {
            val from = shown
            position.snapTo(from)
            dragPx = 0f
            dragging = false
        }
        position.animateTo(index.toFloat())
    }
}

@Composable
fun rememberModeBarState(initial: Int): ModeBarState = remember { ModeBarState(initial) }

/**
 * Mode tabs in [modes] order; the one under [state] sits in the middle and is lit, so a drag shows where it
 * lands. Disabled while a capture runs.
 */
@Composable
fun ModeBar(
    modes: List<CaptureMode>,
    selected: CaptureMode,
    enabled: Boolean,
    onSelect: (CaptureMode) -> Unit,
    state: ModeBarState,
    modifier: Modifier = Modifier,
) {
    val selectedIndex = modes.indexOf(selected).coerceAtLeast(0)
    LaunchedEffect(selectedIndex, modes) { if (!state.dragging) state.settle(selectedIndex) }
    val lit = state.shown.roundToInt()
    Layout(
        modifier = modifier.fillMaxWidth().clipToBounds(),
        content = {
            modes.forEachIndexed { index, mode ->
                Text(
                    text = stringResource(mode.labelRes()),
                    color = when {
                        index == lit -> Color(0xFFFFD54F)
                        enabled -> Color.White
                        else -> Color.Gray
                    },
                    fontWeight = if (index == lit) FontWeight.Bold else FontWeight.Normal,
                    // Readable over bright scenes where the preview runs behind the bar (16:9 modes).
                    style = TextStyle(shadow = Shadow(Color.Black, Offset(0f, 1f), blurRadius = 6f)),
                    modifier = Modifier
                        .clickable(enabled = enabled && mode != selected, role = Role.Tab) { onSelect(mode) }
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                )
            }
        },
    ) { measurables, constraints ->
        // Bold is wider, so every label is measured at its own width and centred by that.
        val placeables = measurables.map { it.measure(Constraints()) }
        val gap = LABEL_GAP.roundToPx()
        val lefts = IntArray(placeables.size)
        var x = 0
        placeables.forEachIndexed { i, p ->
            lefts[i] = x
            x += p.width + gap
        }
        state.centers = FloatArray(placeables.size) { lefts[it] + placeables[it].width / 2f }
        val height = placeables.maxOfOrNull { it.height } ?: 0
        layout(constraints.maxWidth, height) {
            // Read here, so a slide only moves the labels and does not measure them again.
            val shift = (constraints.maxWidth / 2f - centerAt(state.centers, state.shown)).roundToInt()
            placeables.forEachIndexed { i, p -> p.place(lefts[i] + shift, (height - p.height) / 2) }
        }
    }
}
