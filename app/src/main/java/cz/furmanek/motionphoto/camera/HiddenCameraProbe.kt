package cz.furmanek.motionphoto.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.util.concurrent.Executor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/** What happened when the app tried a camera the phone hides from its list. */
enum class ProbeOutcome {
    /** It opened and sent a frame: the app can use it. */
    WORKS,

    /** The phone refused to open it for this app. */
    BLOCKED,

    /** Another app (or this one) held a camera. */
    BUSY,

    /** It opened but no frame came. */
    NO_FRAME,

    /** Anything else; the detail says what. */
    FAILED,
}

data class ProbeResult(val outcome: ProbeOutcome, val detail: String = "")

private const val TAG = "HiddenCameraProbe"
private const val OPEN_TIMEOUT_MS = 4_000L
private const val FRAME_TIMEOUT_MS = 4_000L
private const val PROBE_WIDTH = 640
private const val PROBE_HEIGHT = 480

/**
 * Opens camera [id] directly through Camera2, streams it into a small reader and waits for one frame; then closes
 * everything. The caller must have let go of its own camera first, or the phone may report it busy.
 */
@SuppressLint("MissingPermission") // Only reachable from the camera screen, which has the permission.
suspend fun probeCamera(context: Context, id: String): ProbeResult {
    val manager = context.getSystemService(CameraManager::class.java) ?: return ProbeResult(ProbeOutcome.FAILED, "no CameraManager")
    val thread = HandlerThread("hidden-camera-probe").apply { start() }
    val handler = Handler(thread.looper)
    val executor = Executor { handler.post(it) }
    val reader = ImageReader.newInstance(PROBE_WIDTH, PROBE_HEIGHT, ImageFormat.YUV_420_888, 2)
    var device: CameraDevice? = null
    try {
        val opened = CompletableDeferred<Result<CameraDevice>>()
        try {
            manager.openCamera(id, executor, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (!opened.complete(Result.success(camera))) camera.close()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    opened.complete(Result.failure(ProbeFailure(ProbeOutcome.BUSY, "disconnected")))
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    opened.complete(Result.failure(ProbeFailure(openErrorOutcome(error), "error $error")))
                }
            })
        } catch (e: SecurityException) {
            return ProbeResult(ProbeOutcome.BLOCKED, e.message.orEmpty())
        } catch (e: IllegalArgumentException) {
            return ProbeResult(ProbeOutcome.BLOCKED, e.message.orEmpty())
        } catch (e: CameraAccessException) {
            return ProbeResult(accessOutcome(e.reason), e.message.orEmpty())
        }
        val camera = withTimeoutOrNull(OPEN_TIMEOUT_MS) { opened.await() }
            ?.getOrElse { e -> return (e as? ProbeFailure)?.result ?: ProbeResult(ProbeOutcome.FAILED, e.toString()) }
            ?: run {
                // A late onOpened then finds the deferred done and closes its camera.
                opened.cancel()
                return ProbeResult(ProbeOutcome.FAILED, "open timed out")
            }
        device = camera

        val frame = CompletableDeferred<Unit>()
        reader.setOnImageAvailableListener({ r ->
            r.acquireLatestImage()?.close()
            frame.complete(Unit)
        }, handler)
        val configured = CompletableDeferred<CameraCaptureSession?>()
        camera.createCaptureSession(
            SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                listOf(OutputConfiguration(reader.surface)),
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
        val session = withTimeoutOrNull(OPEN_TIMEOUT_MS) { configured.await() }
            ?: return ProbeResult(ProbeOutcome.FAILED, "session not configured")
        val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(reader.surface) }.build()
        session.setRepeatingRequest(request, null, handler)
        return if (withTimeoutOrNull(FRAME_TIMEOUT_MS) { frame.await() } != null) {
            ProbeResult(ProbeOutcome.WORKS)
        } else {
            ProbeResult(ProbeOutcome.NO_FRAME)
        }
    } catch (e: CameraAccessException) {
        return ProbeResult(accessOutcome(e.reason), e.message.orEmpty())
    } catch (e: IllegalStateException) {
        return ProbeResult(ProbeOutcome.FAILED, e.message.orEmpty())
    } finally {
        device?.close()
        reader.close()
        thread.quitSafely()
    }
}

private class ProbeFailure(outcome: ProbeOutcome, detail: String) : Exception(detail) {
    val result = ProbeResult(outcome, detail)
}

private fun openErrorOutcome(error: Int): ProbeOutcome = when (error) {
    CameraDevice.StateCallback.ERROR_CAMERA_DISABLED -> ProbeOutcome.BLOCKED
    CameraDevice.StateCallback.ERROR_CAMERA_IN_USE, CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE -> ProbeOutcome.BUSY
    else -> ProbeOutcome.FAILED
}

private fun accessOutcome(reason: Int): ProbeOutcome = when (reason) {
    CameraAccessException.CAMERA_DISABLED -> ProbeOutcome.BLOCKED
    CameraAccessException.CAMERA_IN_USE, CameraAccessException.MAX_CAMERAS_IN_USE -> ProbeOutcome.BUSY
    else -> ProbeOutcome.FAILED
}.also { Log.w(TAG, "Camera access failed: reason $reason") }
