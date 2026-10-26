package cz.furmanek.motionphoto.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import java.util.concurrent.TimeUnit

fun formatDuration(durationNanos: Long): String {
    val totalSeconds = TimeUnit.NANOSECONDS.toSeconds(durationNanos)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%02d:%02d".format(minutes, seconds)
}

/** [outputMillis] (time-lapse) adds the length of the resulting video: "02:10 → 0:04". */
@Composable
fun RecordingIndicator(durationNanos: Long, paused: Boolean, modifier: Modifier = Modifier, outputMillis: Long? = null) {
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.5f))
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(if (paused) Color.Gray else Color(0xFFE53935)),
        )
        val elapsed = formatDuration(durationNanos)
        Text(
            if (outputMillis == null) elapsed else "$elapsed → ${formatDuration(TimeUnit.MILLISECONDS.toNanos(outputMillis))}",
            color = Color.White,
        )
    }
}
