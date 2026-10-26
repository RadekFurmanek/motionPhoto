package cz.furmanek.motionphoto.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.FlashlightOff
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.MotionPhotosOff
import androidx.compose.material.icons.filled.MotionPhotosOn
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timer10
import androidx.compose.material.icons.filled.Timer3
import androidx.compose.material.icons.filled.TimerOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cz.furmanek.motionphoto.R
import cz.furmanek.motionphoto.camera.CameraCapabilities
import cz.furmanek.motionphoto.camera.resolveVideoQuality
import cz.furmanek.motionphoto.settings.CameraSettings
import cz.furmanek.motionphoto.settings.CaptureMode
import cz.furmanek.motionphoto.settings.FlashSetting
import cz.furmanek.motionphoto.settings.PhotoAspect
import cz.furmanek.motionphoto.settings.TimerSetting
import cz.furmanek.motionphoto.settings.effectiveLivePhoto

/**
 * Settings for the current mode. Only what [capabilities] supports is shown; [locked] (capture or recording
 * running) disables everything that would rebind the camera.
 */
@Composable
fun QuickSettingsBar(
    settings: CameraSettings,
    capabilities: CameraCapabilities?,
    locked: Boolean,
    livePhotoAvailable: Boolean,
    onUpdate: ((CameraSettings) -> CameraSettings) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hasFlash = capabilities?.hasFlash == true
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (settings.mode) {
            CaptureMode.PHOTO -> {
                if (hasFlash) {
                    val (icon, label) = when (settings.flash) {
                        FlashSetting.OFF -> Icons.Filled.FlashOff to R.string.flash_off
                        FlashSetting.AUTO -> Icons.Filled.FlashAuto to R.string.flash_auto
                        FlashSetting.ON -> Icons.Filled.FlashOn to R.string.flash_on
                    }
                    BarIcon(icon, stringResource(label), enabled = !locked) {
                        onUpdate { it.copy(flash = FlashSetting.entries.next(it.flash)) }
                    }
                }
                val timerIcon = when (settings.timer) {
                    TimerSetting.OFF -> Icons.Filled.TimerOff
                    TimerSetting.THREE -> Icons.Filled.Timer3
                    TimerSetting.TEN -> Icons.Filled.Timer10
                }
                BarIcon(timerIcon, stringResource(R.string.timer), enabled = !locked) {
                    onUpdate { it.copy(timer = TimerSetting.entries.next(it.timer)) }
                }
                BarText(stringResource(settings.aspect.labelRes()), stringResource(R.string.aspect_ratio), enabled = !locked) {
                    onUpdate { it.copy(aspect = PhotoAspect.entries.next(it.aspect)) }
                }
                // Shown crossed out while the flash setting turns live photo off for the shot;
                // hidden on cameras that cannot record the clip next to the still.
                if (livePhotoAvailable) BarIcon(
                    if (settings.effectiveLivePhoto()) Icons.Filled.MotionPhotosOn else Icons.Filled.MotionPhotosOff,
                    stringResource(R.string.live_photo),
                    enabled = !locked,
                    highlighted = settings.effectiveLivePhoto(),
                ) {
                    onUpdate { it.copy(livePhoto = !it.livePhoto) }
                }
            }
            CaptureMode.VIDEO -> {
                if (hasFlash) {
                    BarIcon(
                        if (settings.torch) Icons.Filled.FlashlightOn else Icons.Filled.FlashlightOff,
                        stringResource(R.string.torch),
                        enabled = true,
                        highlighted = settings.torch,
                    ) {
                        onUpdate { it.copy(torch = !it.torch) }
                    }
                }
                val qualities = capabilities?.videoQualities.orEmpty()
                val quality = resolveVideoQuality(settings.videoQuality, qualities)
                if (quality != null && qualities.size > 1) {
                    BarText(stringResource(quality.labelRes()), stringResource(R.string.video_quality), enabled = !locked) {
                        onUpdate { it.copy(videoQuality = qualities.next(quality)) }
                    }
                }
                if (quality != null && quality in capabilities?.fps60Qualities.orEmpty()) {
                    BarText(
                        stringResource(R.string.fps60_short),
                        stringResource(R.string.settings_fps60),
                        enabled = !locked,
                        highlighted = settings.videoFps60,
                    ) {
                        onUpdate { it.copy(videoFps60 = !it.videoFps60) }
                    }
                }
            }
        }
        BarIcon(Icons.Filled.Settings, stringResource(R.string.settings), enabled = !locked, onClick = onOpenSettings)
    }
}

@Composable
private fun BarIcon(
    icon: ImageVector,
    description: String,
    enabled: Boolean,
    highlighted: Boolean = false,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(icon, contentDescription = description, tint = barColor(enabled, highlighted))
    }
}

@Composable
private fun BarText(
    text: String,
    description: String,
    enabled: Boolean,
    highlighted: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(48.dp)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = description, onClick = onClick),
    ) {
        Text(text, color = barColor(enabled, highlighted), fontWeight = FontWeight.Bold)
    }
}

private fun barColor(enabled: Boolean, highlighted: Boolean): Color = when {
    !enabled -> Color.Gray
    highlighted -> Color(0xFFFFD54F)
    else -> Color.White
}
