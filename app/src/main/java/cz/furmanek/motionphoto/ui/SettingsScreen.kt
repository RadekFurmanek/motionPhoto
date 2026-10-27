package cz.furmanek.motionphoto.ui

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import cz.furmanek.motionphoto.R
import cz.furmanek.motionphoto.camera.CameraCapabilities
import cz.furmanek.motionphoto.camera.resolveVideoQuality
import cz.furmanek.motionphoto.settings.CameraSettings
import cz.furmanek.motionphoto.ui.components.labelRes

@Composable
fun SettingsScreen(
    settings: CameraSettings,
    capabilities: CameraCapabilities?,
    shutterSoundMandatory: Boolean,
    onUpdate: ((CameraSettings) -> CameraSettings) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    // Location is asked for only when the user turns geotagging on; a refusal leaves it off.
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.any { it }) onUpdate { it.copy(saveLocation = true) }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState()),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                }
                Text(stringResource(R.string.settings), style = MaterialTheme.typography.titleLarge)
            }

            Section(stringResource(R.string.settings_section_general))
            SwitchRow(stringResource(R.string.settings_grid), checked = settings.grid) { checked ->
                onUpdate { it.copy(grid = checked) }
            }
            SwitchRow(
                title = stringResource(R.string.settings_location),
                summary = stringResource(R.string.settings_location_summary),
                checked = settings.saveLocation,
            ) { checked ->
                if (checked) {
                    locationPermission.launch(
                        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                    )
                } else {
                    onUpdate { it.copy(saveLocation = false) }
                }
            }
            SwitchRow(
                title = stringResource(R.string.settings_shutter_sound),
                summary = if (shutterSoundMandatory) stringResource(R.string.settings_shutter_sound_mandatory) else null,
                checked = settings.shutterSound || shutterSoundMandatory,
                enabled = !shutterSoundMandatory,
            ) { checked ->
                onUpdate { it.copy(shutterSound = checked) }
            }
            SwitchRow(stringResource(R.string.settings_volume_key), checked = settings.volumeKeyShutter) { checked ->
                onUpdate { it.copy(volumeKeyShutter = checked) }
            }

            Section(stringResource(R.string.settings_section_photo))
            SwitchRow(
                title = stringResource(R.string.settings_mirror),
                summary = stringResource(R.string.settings_mirror_summary),
                checked = settings.mirrorFrontCamera,
            ) { checked ->
                onUpdate { it.copy(mirrorFrontCamera = checked) }
            }

            val qualities = capabilities?.videoQualities.orEmpty()
            if (qualities.isNotEmpty()) {
                Section(stringResource(R.string.settings_section_video))
                Text(
                    stringResource(R.string.settings_video_quality),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // The quality actually used: the stored one may be missing on this camera.
                    val effectiveQuality = resolveVideoQuality(settings.videoQuality, qualities)
                    qualities.forEach { quality ->
                        FilterChip(
                            selected = effectiveQuality == quality,
                            onClick = { onUpdate { it.copy(videoQuality = quality) } },
                            label = { Text(stringResource(quality.labelRes())) },
                        )
                    }
                }
                if (capabilities?.fps60Qualities.orEmpty().isNotEmpty()) {
                    SwitchRow(
                        title = stringResource(R.string.settings_fps60),
                        summary = stringResource(R.string.settings_fps60_summary),
                        checked = settings.videoFps60,
                    ) { checked ->
                        onUpdate { it.copy(videoFps60 = checked) }
                    }
                }
                if (capabilities?.videoStabilization == true) {
                    SwitchRow(stringResource(R.string.settings_stabilization), checked = settings.videoStabilization) { checked ->
                        onUpdate { it.copy(videoStabilization = checked) }
                    }
                }
            }

            val slowMotionRates = capabilities?.slowMotionFps.orEmpty().sorted()
            if (slowMotionRates.isNotEmpty()) {
                Section(stringResource(R.string.settings_section_slow_motion))
                val effectiveRate = settings.slowMotionFps.takeIf { it in slowMotionRates } ?: slowMotionRates.last()
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    slowMotionRates.forEach { rate ->
                        FilterChip(
                            selected = effectiveRate == rate,
                            onClick = { onUpdate { it.copy(slowMotionFps = rate) } },
                            label = { Text(stringResource(R.string.slow_motion_fps, rate)) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 4.dp),
    )
}

@Composable
private fun SwitchRow(
    title: String,
    checked: Boolean,
    summary: String? = null,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, role = Role.Switch) { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title)
            summary?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}
