package cz.furmanek.motionphoto

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Recording
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.camera.view.video.AudioConfig
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.compose.LocalLifecycleOwner
import cz.furmanek.motionphoto.camera.FinalizeOutcome
import cz.furmanek.motionphoto.camera.classifyFinalize
import cz.furmanek.motionphoto.ui.CameraPermissionGate
import cz.furmanek.motionphoto.ui.components.RecordingIndicator
import cz.furmanek.motionphoto.ui.components.ShutterButton
import cz.furmanek.motionphoto.ui.components.formatDuration
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Handles ACTION_IMAGE_CAPTURE and ACTION_VIDEO_CAPTURE from other apps: one photo or video, a review,
 * and the result written to EXTRA_OUTPUT or returned. No live photo, gallery or settings here.
 */
class CaptureIntentActivity : ComponentActivity() {

    private val isVideo by lazy { intent.action == MediaStore.ACTION_VIDEO_CAPTURE }
    private val output: Uri? by lazy { IntentCompat.getParcelableExtra(intent, MediaStore.EXTRA_OUTPUT, Uri::class.java) }
    private val app get() = application as MotionPhotoApp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A background recording holds the camera; two owners would cut it short.
        if (app.cameraSession.state.value.recording != null) {
            Toast.makeText(this, R.string.intent_busy, Toast.LENGTH_LONG).show()
            setResult(RESULT_CANCELED)
            finish()
            return
        }
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                CameraPermissionGate {
                    CaptureScreen()
                }
            }
        }
    }

    @Composable
    private fun CaptureScreen() {
        val lifecycleOwner = LocalLifecycleOwner.current
        val scope = rememberCoroutineScope()
        val controller = remember {
            LifecycleCameraController(this).apply {
                setEnabledUseCases(if (isVideo) CameraController.VIDEO_CAPTURE else CameraController.IMAGE_CAPTURE)
                imageCaptureMode = ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
            }
        }
        LaunchedEffect(controller, lifecycleOwner) { controller.bindToLifecycle(lifecycleOwner) }
        // hasCamera() throws until CameraX has initialised.
        var initialized by remember { mutableStateOf(false) }
        LaunchedEffect(controller) {
            controller.initializationFuture.addListener({ initialized = true }, ContextCompat.getMainExecutor(this@CaptureIntentActivity))
        }

        var captured by remember { mutableStateOf<File?>(null) }
        var busy by remember { mutableStateOf(false) }
        var recording by remember { mutableStateOf<Recording?>(null) }
        var recordedNanos by remember { mutableLongStateOf(0L) }

        val reviewFile = captured
        if (reviewFile != null) {
            Review(
                file = reviewFile,
                durationNanos = recordedNanos,
                onRetake = {
                    reviewFile.delete()
                    captured = null
                },
                onUse = { scope.launch { deliver(reviewFile) } },
            )
            return
        }
        BackHandler(enabled = recording != null) { recording?.stop() }

        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(
                factory = { context ->
                    PreviewView(context).apply {
                        scaleType = PreviewView.ScaleType.FIT_CENTER
                        this.controller = controller
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            recording?.let {
                RecordingIndicator(
                    recordedNanos,
                    paused = false,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .padding(top = 16.dp),
                )
            }
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(bottom = 32.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.size(56.dp))
                ShutterButton(
                    isVideo = isVideo,
                    isRecording = recording != null,
                    showClipProgress = false,
                    enabled = !busy,
                    onClick = {
                        val active = recording
                        when {
                            active != null -> active.stop()
                            isVideo -> recording = startVideo(controller) { event ->
                                if (event is VideoRecordEvent.Finalize) {
                                    recording = null
                                    if (classifyFinalize(event.error) == FinalizeOutcome.FAILED) {
                                        Toast.makeText(this@CaptureIntentActivity, R.string.recording_failed, Toast.LENGTH_LONG).show()
                                    } else {
                                        captured = videoFile()
                                    }
                                } else {
                                    recordedNanos = event.recordingStats.recordedDurationNanos
                                }
                            }
                            else -> {
                                busy = true
                                takePhoto(controller) { file ->
                                    busy = false
                                    captured = file
                                }
                            }
                        }
                    },
                )
                val canSwitch = initialized && recording == null &&
                    controller.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) &&
                    controller.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)
                IconButton(
                    onClick = {
                        controller.cameraSelector =
                            if (controller.cameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) {
                                CameraSelector.DEFAULT_FRONT_CAMERA
                            } else {
                                CameraSelector.DEFAULT_BACK_CAMERA
                            }
                    },
                    enabled = canSwitch,
                    modifier = Modifier.size(56.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.4f)),
                ) {
                    Icon(
                        Icons.Filled.Cameraswitch,
                        contentDescription = stringResource(R.string.switch_camera),
                        tint = if (canSwitch) Color.White else Color.Gray,
                    )
                }
            }
        }
    }

    @Composable
    private fun Review(file: File, durationNanos: Long, onRetake: () -> Unit, onUse: () -> Unit) {
        BackHandler(onBack = onRetake)
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (isVideo) {
                Text(
                    stringResource(R.string.intent_video_recorded, formatDuration(durationNanos)),
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                val bitmap = remember(file) { loadUpright(file, REVIEW_MAX_PX) }
                bitmap?.let {
                    Image(
                        it.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(32.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                OutlinedButton(onClick = onRetake) { Text(stringResource(R.string.intent_retake)) }
                Button(onClick = onUse) { Text(stringResource(R.string.intent_use)) }
            }
        }
    }

    private fun takePhoto(controller: LifecycleCameraController, onSaved: (File?) -> Unit) {
        val file = photoFile()
        controller.takePicture(
            ImageCapture.OutputFileOptions.Builder(file).build(),
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) = onSaved(file)

                override fun onError(exception: ImageCaptureException) {
                    Toast.makeText(
                        this@CaptureIntentActivity,
                        getString(R.string.error_capture, exception.message ?: ""),
                        Toast.LENGTH_LONG,
                    ).show()
                    onSaved(null)
                }
            },
        )
    }

    // Audio only with RECORD_AUDIO; the caller's duration and size limits end the recording on their own.
    @SuppressLint("MissingPermission")
    private fun startVideo(controller: LifecycleCameraController, onEvent: (VideoRecordEvent) -> Unit): Recording {
        val options = FileOutputOptions.Builder(videoFile()).apply {
            val seconds = intent.getIntExtra(MediaStore.EXTRA_DURATION_LIMIT, 0)
            if (seconds > 0) setDurationLimitMillis(TimeUnit.SECONDS.toMillis(seconds.toLong()))
            val bytes = intent.getLongExtra(MediaStore.EXTRA_SIZE_LIMIT, 0L)
            if (bytes > 0) setFileSizeLimit(bytes)
        }.build()
        val audio = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        return controller.startRecording(options, AudioConfig.create(audio), ContextCompat.getMainExecutor(this), onEvent)
    }

    /** Writes [file] to EXTRA_OUTPUT, or returns it (photo: a thumbnail; video: a new gallery entry), then finishes. */
    private suspend fun deliver(file: File) {
        try {
            val result = withContext(Dispatchers.IO) {
                val target = output
                when {
                    target != null -> {
                        val stream = contentResolver.openOutputStream(target, "w")
                            ?: throw IOException("Cannot open $target for writing")
                        stream.use { out -> file.inputStream().use { it.copyTo(out) } }
                        Intent().setData(target)
                    }
                    isVideo -> {
                        val uri = app.mediaStoreWriter.saveVideoFile(file, System.currentTimeMillis())
                        Intent().setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    else -> Intent().putExtra("data", loadUpright(file, RESULT_THUMBNAIL_MAX_PX))
                }
            }
            setResult(RESULT_OK, result)
            finish()
        } catch (e: IOException) {
            Toast.makeText(this, getString(R.string.intent_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
        } catch (e: SecurityException) {
            Toast.makeText(this, getString(R.string.intent_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
        } finally {
            withContext(Dispatchers.IO) { file.delete() }
        }
    }

    private fun photoFile() = File(cacheDir, "intent_capture.jpg")

    private fun videoFile() = File(cacheDir, "intent_capture.mp4")

    private companion object {
        const val REVIEW_MAX_PX = 2048
        // The thumbnail travels through Binder (1 MB limit); 320 px ARGB is ~300 kB.
        const val RESULT_THUMBNAIL_MAX_PX = 320

        /** Decodes [file] downsampled to at most [maxPx] on its long side and turned upright per EXIF. */
        fun loadUpright(file: File, maxPx: Int): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx) sample *= 2
            val decoded = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: return null
            val scale = minOf(1f, maxPx.toFloat() / maxOf(decoded.width, decoded.height))
            val rotation = ExifInterface(file).rotationDegrees.toFloat()
            if (scale == 1f && rotation == 0f) return decoded
            val matrix = Matrix().apply {
                postScale(scale, scale)
                postRotate(rotation)
            }
            return Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        }
    }
}
