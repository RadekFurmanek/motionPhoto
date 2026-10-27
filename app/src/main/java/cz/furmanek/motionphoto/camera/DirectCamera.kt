package cz.furmanek.motionphoto.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import java.io.File
import java.util.concurrent.Executor
import kotlin.math.roundToInt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** [busy]: the phone said another camera is still open, so trying again shortly may work. */
class DirectCameraException(message: String, cause: Throwable? = null, val busy: Boolean = false) : Exception(message, cause)

/**
 * A back camera opened through Camera2 directly, for lenses the phone hides from its list (CameraX only binds
 * listed cameras): a preview into a surface the UI owns and full-size 4:3 JPEG stills. Call from the main thread;
 * Camera2 callbacks run on the camera's own thread. [close] once done; the object is not reusable.
 */
@SuppressLint("MissingPermission") // Only opened from the camera screen, which has the permission.
class DirectCamera(context: Context, val cameraId: String) {

    private val manager = context.getSystemService(CameraManager::class.java)
        ?: throw DirectCameraException("no CameraManager")
    private val characteristics = try {
        manager.getCameraCharacteristics(cameraId)
    } catch (e: IllegalArgumentException) {
        throw DirectCameraException("camera $cameraId unknown", e)
    } catch (e: CameraAccessException) {
        throw DirectCameraException("camera $cameraId not readable", e)
    }

    val sensorOrientation: Int = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: DEFAULT_ORIENTATION
    val maxZoom: Float = characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
    val exposureRange: IntRange =
        characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)?.let { it.lower..it.upper } ?: 0..0
    private val activeArray: Rect =
        characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: Rect(0, 0, 1, 1)
    private val afRegions = characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0
    private val aeRegions = characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0
    private val streams = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        ?: throw DirectCameraException("camera $cameraId lists no streams")

    /** The preview buffer, landscape as the sensor delivers it; the UI shows it turned upright. */
    val previewSize: Size = streams.getOutputSizes(SurfaceTexture::class.java).orEmpty()
        .map { it.width to it.height }
        .let { largest43(it, MAX_PREVIEW_LONG_SIDE) }
        ?.let { (w, h) -> Size(w, h) }
        ?: throw DirectCameraException("camera $cameraId has no 4:3 preview size")
    private val jpegSize: Size = streams.getOutputSizes(ImageFormat.JPEG).orEmpty()
        .map { it.width to it.height }
        .let { largest43(it) }
        ?.let { (w, h) -> Size(w, h) }
        ?: throw DirectCameraException("camera $cameraId has no 4:3 JPEG size")

    private val thread = HandlerThread("direct-camera").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }

    private val _failed = MutableStateFlow(false)

    /** Set when the camera errors or is taken away after opening; the owner should then close it. */
    val failed: StateFlow<Boolean> = _failed.asStateFlow()

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var preview: Surface? = null
    @Volatile private var closed = false

    private var zoom = 1f
    private var exposure = 0
    private var metering: MeteringRectangle? = null
    private val resumeContinuousFocus = Runnable { metering = null; repeatPreview() }

    /** Opens the camera and starts the preview into [preview]; throws [DirectCameraException] when it cannot. */
    suspend fun open(preview: Surface) {
        val camera = openDevice()
        if (closed) {
            camera.close()
            throw DirectCameraException("closed while opening")
        }
        device = camera
        val reader = ImageReader.newInstance(jpegSize.width, jpegSize.height, ImageFormat.JPEG, 2).also { reader = it }
        this.preview = preview
        session = configure(camera, listOf(preview, reader.surface))
        repeatPreview()
        Log.i(TAG, "Camera $cameraId open: preview $previewSize, JPEG $jpegSize, orientation $sensorOrientation")
    }

    private suspend fun openDevice(): CameraDevice {
        val opened = CompletableDeferred<CameraDevice>()
        try {
            manager.openCamera(cameraId, executor, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    // Too late (timed out or closed meanwhile): nobody will use it.
                    if (!opened.complete(camera)) camera.close()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    fail(opened, "camera $cameraId disconnected", busy = true)
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    val busy = error == ERROR_CAMERA_IN_USE || error == ERROR_MAX_CAMERAS_IN_USE
                    fail(opened, "camera $cameraId error $error", busy)
                }
            })
        } catch (e: CameraAccessException) {
            val busy = e.reason == CameraAccessException.CAMERA_IN_USE || e.reason == CameraAccessException.MAX_CAMERAS_IN_USE
            throw DirectCameraException("camera $cameraId not accessible: ${e.reason}", e, busy)
        } catch (e: SecurityException) {
            throw DirectCameraException("camera $cameraId refused", e)
        } catch (e: IllegalArgumentException) {
            throw DirectCameraException("camera $cameraId refused", e)
        }
        try {
            return withTimeoutOrNull(OPEN_TIMEOUT_MS) { opened.await() }
                ?: throw DirectCameraException("camera $cameraId did not open")
        } finally {
            // Timed out or cancelled (the user left): a late onOpened then finds this done and closes its camera.
            opened.cancel()
        }
    }

    /** Before opening, a failure fails the open; after, it marks the camera as failed. */
    private fun fail(opened: CompletableDeferred<CameraDevice>, message: String, busy: Boolean = false) {
        Log.w(TAG, message)
        if (!opened.completeExceptionally(DirectCameraException(message, busy = busy))) _failed.value = true
    }

    private suspend fun configure(camera: CameraDevice, surfaces: List<Surface>): CameraCaptureSession {
        val configured = CompletableDeferred<CameraCaptureSession?>()
        try {
            camera.createCaptureSession(
                SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    surfaces.map(::OutputConfiguration),
                    executor,
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            configured.complete(session)
                        }

                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            configured.complete(null)
                        }
                    },
                ),
            )
        } catch (e: CameraAccessException) {
            throw DirectCameraException("camera $cameraId session refused: ${e.reason}", e)
        }
        return withTimeoutOrNull(OPEN_TIMEOUT_MS) { configured.await() }
            ?: throw DirectCameraException("camera $cameraId session not configured")
    }

    /** Focuses and meters on a tap at ([u], [v]) of the upright preview (0..1); back to continuous after a while. */
    fun focusAt(u: Float, v: Float) {
        val (sx, sy) = tapToSensor(u, v, sensorOrientation)
        val crop = crop()
        val half = (crop.width() * METERING_FRACTION / 2).roundToInt().coerceAtLeast(1)
        val cx = crop.left + (sx * crop.width()).roundToInt()
        val cy = crop.top + (sy * crop.height()).roundToInt()
        val left = (cx - half).coerceIn(crop.left, crop.right - 1)
        val top = (cy - half).coerceIn(crop.top, crop.bottom - 1)
        metering = MeteringRectangle(
            left,
            top,
            (2 * half).coerceAtMost(crop.right - left),
            (2 * half).coerceAtMost(crop.bottom - top),
            MeteringRectangle.METERING_WEIGHT_MAX,
        )
        handler.removeCallbacks(resumeContinuousFocus)
        repeatPreview()
        val camera = device ?: return
        val session = session ?: return
        val preview = preview ?: return
        runCamera {
            val trigger = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(preview)
                applySettings()
                set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
            }
            session.capture(trigger.build(), null, handler)
        }
        handler.postDelayed(resumeContinuousFocus, FOCUS_HOLD_MS)
    }

    fun setExposure(index: Int) {
        exposure = index.coerceIn(exposureRange)
        repeatPreview()
    }

    fun setZoom(ratio: Float) {
        zoom = ratio.coerceIn(1f, maxZoom.coerceAtLeast(1f))
        repeatPreview()
    }

    val zoomRatio: Float get() = zoom

    /** Takes a still and writes the JPEG to [file], turned by [jpegOrientation] degrees. */
    suspend fun capture(file: File, jpegOrientation: Int) {
        val camera = device ?: throw DirectCameraException("camera $cameraId not open")
        val session = session ?: throw DirectCameraException("camera $cameraId not open")
        val reader = reader ?: throw DirectCameraException("camera $cameraId not open")
        val image = CompletableDeferred<ByteArray>()
        reader.setOnImageAvailableListener({ r ->
            r.acquireLatestImage()?.use { img ->
                val buffer = img.planes[0].buffer
                image.complete(ByteArray(buffer.remaining()).also(buffer::get))
            }
        }, handler)
        val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
            addTarget(reader.surface)
            applySettings()
            set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation)
            set(CaptureRequest.JPEG_QUALITY, JPEG_QUALITY)
        }
        try {
            session.capture(request.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                    image.completeExceptionally(DirectCameraException("capture failed: ${failure.reason}"))
                }
            }, handler)
        } catch (e: CameraAccessException) {
            throw DirectCameraException("capture refused: ${e.reason}", e)
        } catch (e: IllegalStateException) {
            throw DirectCameraException("capture refused", e)
        }
        val bytes = withTimeoutOrNull(CAPTURE_TIMEOUT_MS) { image.await() }
            ?: throw DirectCameraException("no image from camera $cameraId")
        withContext(Dispatchers.IO) { file.writeBytes(bytes) }
    }

    /** Closes the camera; safe to call more than once and while opening. */
    fun close() {
        if (closed) return
        closed = true
        handler.removeCallbacks(resumeContinuousFocus)
        runCatching { session?.close() }
        runCatching { device?.close() }
        runCatching { reader?.close() }
        session = null
        device = null
        reader = null
        // Camera2 still reports the session and device closing on this thread; let those land first.
        handler.postDelayed({ thread.quitSafely() }, THREAD_LINGER_MS)
    }

    private fun crop(): Rect {
        val (left, top, right, bottom) = cropRegion(activeArray.width(), activeArray.height(), zoom)
        return Rect(activeArray.left + left, activeArray.top + top, activeArray.left + right, activeArray.top + bottom)
    }

    private fun CaptureRequest.Builder.applySettings() {
        val region = metering
        set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        set(
            CaptureRequest.CONTROL_AF_MODE,
            if (region != null) CameraMetadata.CONTROL_AF_MODE_AUTO else CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
        )
        set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
        set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, exposure)
        set(CaptureRequest.SCALER_CROP_REGION, crop())
        if (region != null) {
            if (afRegions > 0) set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(region))
            if (aeRegions > 0) set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(region))
        }
    }

    private fun repeatPreview() {
        val camera = device ?: return
        val session = session ?: return
        val preview = preview ?: return
        runCamera {
            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(preview)
                applySettings()
            }
            session.setRepeatingRequest(request.build(), null, handler)
        }
    }

    /** A request on a camera that is closing or gone is dropped; [failed] reports a lost camera. */
    private inline fun runCamera(block: () -> Unit) {
        if (closed) return
        try {
            block()
        } catch (e: CameraAccessException) {
            Log.w(TAG, "Camera $cameraId request refused: ${e.reason}", e)
            _failed.value = true
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Camera $cameraId request on a closed session", e)
        }
    }

    private companion object {
        const val TAG = "DirectCamera"
        const val DEFAULT_ORIENTATION = 90
        const val MAX_PREVIEW_LONG_SIDE = 1920
        const val OPEN_TIMEOUT_MS = 5_000L
        const val CAPTURE_TIMEOUT_MS = 8_000L
        const val FOCUS_HOLD_MS = 5_000L
        const val METERING_FRACTION = 0.1f
        const val JPEG_QUALITY: Byte = 95
        const val THREAD_LINGER_MS = 2_000L
    }
}
