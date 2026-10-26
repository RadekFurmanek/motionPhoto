package cz.furmanek.motionphoto.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cz.furmanek.motionphoto.R
import cz.furmanek.motionphoto.settings.CaptureMode

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

private val ITEM_WIDTH_ESTIMATE = 96.dp

/** Mode tabs in [modes] order; the selected one scrolls to the middle. Disabled while a capture runs. */
@Composable
fun ModeBar(
    modes: List<CaptureMode>,
    selected: CaptureMode,
    enabled: Boolean,
    onSelect: (CaptureMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val selectedIndex = modes.indexOf(selected).coerceAtLeast(0)
    LaunchedEffect(selectedIndex, modes) { listState.animateScrollToItem(selectedIndex) }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        // Side padding lets the first and last tabs reach the middle.
        val sidePadding = (maxWidth - ITEM_WIDTH_ESTIMATE) / 2
        LazyRow(
            state = listState,
            contentPadding = PaddingValues(horizontal = sidePadding),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            userScrollEnabled = false,
        ) {
            itemsIndexed(modes) { _, mode ->
                val isSelected = mode == selected
                Text(
                    text = stringResource(mode.labelRes()),
                    color = when {
                        isSelected -> Color(0xFFFFD54F)
                        enabled -> Color.White
                        else -> Color.Gray
                    },
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier
                        .clickable(enabled = enabled && !isSelected, role = Role.Tab) { onSelect(mode) }
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                )
            }
        }
    }
}
