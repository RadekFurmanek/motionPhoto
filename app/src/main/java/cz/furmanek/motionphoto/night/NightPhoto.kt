package cz.furmanek.motionphoto.night

import android.content.Context
import android.graphics.Bitmap
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.media.Image
import androidx.exifinterface.media.ExifInterface
import java.io.File

private const val JPEG_QUALITY = 95

/** What [characteristics] allow by hand for a night burst; exposures are capped by the longest frame duration. */
fun nightSensor(characteristics: CameraCharacteristics): NightSensor {
    val manual = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
        ?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) == true
    val maxFrame = characteristics.get(CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION)
    val exposure = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        ?.let { it.lower..minOf(it.upper, maxFrame ?: it.upper) }
    val iso = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.let { it.lower..it.upper }
    return NightSensor(manual && exposure != null && iso != null, exposure, iso)
}

/** [nightSensor] of camera [id]; null when the camera cannot be read. */
fun readNightSensor(context: Context, id: String): NightSensor? = try {
    context.getSystemService(CameraManager::class.java)?.getCameraCharacteristics(id)?.let(::nightSensor)
} catch (_: CameraAccessException) {
    null
} catch (_: IllegalArgumentException) {
    null
}

/** Copies a YUV_420_888 [image] of the frame's size into [frame]'s packed planes. */
fun copyYuv(image: Image, frame: YuvFrame) {
    copyPlane(image.planes[0], frame.width, frame.height, frame.y)
    copyPlane(image.planes[1], frame.chromaWidth, frame.chromaHeight, frame.u)
    copyPlane(image.planes[2], frame.chromaWidth, frame.chromaHeight, frame.v)
}

private fun copyPlane(plane: Image.Plane, width: Int, height: Int, out: ByteArray) {
    val buffer = plane.buffer
    val rowStride = plane.rowStride
    val pixelStride = plane.pixelStride
    for (y in 0 until height) {
        if (pixelStride == 1) {
            buffer.position(y * rowStride)
            buffer.get(out, y * width, width)
        } else {
            val base = y * rowStride
            for (x in 0 until width) out[y * width + x] = buffer.get(base + x * pixelStride)
        }
    }
}

/** Renders the merged [frame] (see [renderNight]) into a JPEG at [file], tagged to show turned by [orientation]°. */
fun writeNightJpeg(frame: YuvFrame, digitalGain: Float, orientation: Int, file: File) {
    val bitmap = Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888)
    try {
        renderNight(frame, digitalGain) { y, pixels -> bitmap.setPixels(pixels, 0, frame.width, 0, y, frame.width, 1) }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
    } finally {
        bitmap.recycle()
    }
    val tag = when (orientation) {
        90 -> ExifInterface.ORIENTATION_ROTATE_90
        180 -> ExifInterface.ORIENTATION_ROTATE_180
        270 -> ExifInterface.ORIENTATION_ROTATE_270
        else -> ExifInterface.ORIENTATION_NORMAL
    }
    ExifInterface(file).apply {
        setAttribute(ExifInterface.TAG_ORIENTATION, tag.toString())
        saveAttributes()
    }
}
