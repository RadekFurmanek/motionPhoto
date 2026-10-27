package cz.furmanek.motionphoto.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cz.furmanek.motionphoto.R
import cz.furmanek.motionphoto.portrait.PORTRAIT_BLUR_MAX
import cz.furmanek.motionphoto.portrait.PORTRAIT_BLUR_MIN

/** The custom portrait's background blur, styled like [ExposureSlider]; [fraction] is of the longer side. */
@Composable
fun BlurSlider(
    fraction: Float,
    onChange: (Float) -> Unit,
    onInteraction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.5f))
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.BlurOn, contentDescription = stringResource(R.string.portrait_blur), tint = Color.White)
        Slider(
            value = fraction,
            onValueChange = {
                onInteraction()
                onChange(it)
            },
            valueRange = PORTRAIT_BLUR_MIN..PORTRAIT_BLUR_MAX,
            colors = SliderDefaults.colors(thumbColor = Color(0xFFFFD54F), activeTrackColor = Color(0xFFFFD54F)),
            modifier = Modifier.width(220.dp),
        )
    }
}
