package cz.furmanek.motionphoto.camera

import android.graphics.ImageFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import java.util.concurrent.Executor

/**
 * A preview target that takes frames and drops them. While a recording runs with the app in the background,
 * the real preview surface is gone; a camera stream with no consumer stalls every stream of the session,
 * so video and time-lapse frames would stop. This keeps the preview stream flowing.
 */
class DiscardingSurfaceProvider : Preview.SurfaceProvider {

    private val thread = HandlerThread("discarded-preview").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }

    override fun onSurfaceRequested(request: SurfaceRequest) {
        val size = request.resolution
        val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.PRIVATE, MAX_IMAGES)
        reader.setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, handler)
        request.provideSurface(reader.surface, executor) { reader.close() }
    }

    private companion object {
        const val MAX_IMAGES = 2
    }
}
