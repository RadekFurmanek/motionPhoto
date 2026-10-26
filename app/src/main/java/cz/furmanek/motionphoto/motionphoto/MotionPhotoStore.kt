package cz.furmanek.motionphoto.motionphoto

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Saves Motion Photos to the shared DCIM/Camera folder, where Google Photos picks them up. */
class MotionPhotoStore(private val contentResolver: ContentResolver) {

    /**
     * Muxes [jpeg] and [video] into one file named `PXL_yyyyMMdd_HHmmss.MP.jpg` and returns its MediaStore URI.
     * The entry stays pending (invisible to other apps) until the file is complete, and is removed on failure.
     */
    fun save(jpeg: File, video: File, presentationTimestampUs: Long, capturedAtMillis: Long): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName(capturedAtMillis))
            put(MediaStore.Images.Media.MIME_TYPE, MIME_TYPE)
            put(MediaStore.Images.Media.RELATIVE_PATH, RELATIVE_PATH)
            put(MediaStore.Images.Media.DATE_TAKEN, capturedAtMillis)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = contentResolver.insert(collection, values)
            ?: throw IOException("MediaStore refused to create an entry in $RELATIVE_PATH")
        try {
            val output = contentResolver.openOutputStream(uri, "w")
                ?: throw IOException("Cannot open $uri for writing")
            output.buffered().use { MotionPhotoMuxer.mux(jpeg.readBytes(), video, presentationTimestampUs, it) }
            contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            return uri
        } catch (e: Throwable) {
            try {
                contentResolver.delete(uri, null, null)
            } catch (cleanup: Exception) {
                e.addSuppressed(cleanup)
            }
            throw e
        }
    }

    private fun fileName(capturedAtMillis: Long): String =
        "PXL_" + FILE_NAME_FORMAT.format(Instant.ofEpochMilli(capturedAtMillis)) + ".MP.jpg"

    private companion object {
        const val MIME_TYPE = "image/jpeg"
        val RELATIVE_PATH = Environment.DIRECTORY_DCIM + "/Camera"
        // Pixel phones stamp PXL_ names in UTC; local time stays in DATE_TAKEN and EXIF.
        val FILE_NAME_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").withZone(ZoneOffset.UTC)
    }
}
