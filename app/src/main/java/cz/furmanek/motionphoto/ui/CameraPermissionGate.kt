package cz.furmanek.motionphoto.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import cz.furmanek.motionphoto.R

private val REQUESTED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)

/**
 * Shows [content] once CAMERA is granted. RECORD_AUDIO is requested together with it but is optional:
 * without it Motion Photos are saved with silent video.
 */
@Composable
fun CameraPermissionGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    var cameraGranted by remember { mutableStateOf(context.isGranted(Manifest.permission.CAMERA)) }
    var permanentlyDenied by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        cameraGranted = context.isGranted(Manifest.permission.CAMERA)
        // After a denial the system stops showing the dialog once the rationale flag is false.
        permanentlyDenied = !cameraGranted &&
            context.findActivity()?.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) == false
    }

    // The user may grant the permission in system settings and come back.
    LifecycleResumeEffect(Unit) {
        cameraGranted = context.isGranted(Manifest.permission.CAMERA)
        onPauseOrDispose { }
    }

    LaunchedEffect(Unit) {
        if (!cameraGranted) launcher.launch(REQUESTED_PERMISSIONS)
    }

    if (cameraGranted) {
        content()
    } else {
        PermissionRationale(
            permanentlyDenied = permanentlyDenied,
            onRequest = { launcher.launch(REQUESTED_PERMISSIONS) },
            onOpenSettings = {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                )
            },
        )
    }
}

@Composable
private fun PermissionRationale(permanentlyDenied: Boolean, onRequest: () -> Unit, onOpenSettings: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.permission_title), style = MaterialTheme.typography.headlineSmall)
            Text(
                text = stringResource(R.string.permission_rationale),
                modifier = Modifier.padding(vertical = 16.dp),
                textAlign = TextAlign.Center,
            )
            if (permanentlyDenied) {
                Button(onClick = onOpenSettings) { Text(stringResource(R.string.permission_open_settings)) }
            } else {
                Button(onClick = onRequest) { Text(stringResource(R.string.permission_grant)) }
            }
        }
    }
}

private fun Context.isGranted(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
