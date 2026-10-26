package cz.furmanek.motionphoto.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cz.furmanek.motionphoto.R
import cz.furmanek.motionphoto.settings.CaptureMode

/** Mode tabs; the order is the order of [modes]. Disabled while a capture or recording runs. */
@Composable
fun ModeBar(
    modes: List<CaptureMode>,
    selected: CaptureMode,
    enabled: Boolean,
    onSelect: (CaptureMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
        modes.forEach { mode ->
            val isSelected = mode == selected
            Text(
                text = stringResource(if (mode == CaptureMode.PHOTO) R.string.mode_photo else R.string.mode_video),
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
