package cz.furmanek.motionphoto.media

import android.content.ContentResolver
import android.content.ContentValues
import android.location.Location
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.camera.video.MediaStoreOutputOptions
import androidx.exifinterface.media.ExifInterface
import cz.furmanek.motionphoto.motionphoto.MotionPhotoMuxer
import java.io.File
import java.io.IOException
import java.io.OutputStream

/** Saves captures to the shared DCIM/Camera folder, where Google Photos picks them up. */
class MediaStoreWriter(private val resolver: ContentResolver) {

    /** Muxes [jpeg] and [video] into one Motion Photo. [location] goes into the still's EXIF. */
    fun saveMotionPhoto(
        jpeg: File,
        video: File,
        presentationTimestampUs: Long,
        capturedAtMillis: Long,
        location: Location?,
    ): Uri {
        location?.let { writeExifLocation(jpeg, it) }
        return insertImage(MediaKind.MOTION_PHOTO, capturedAtMillis) { output ->
            MotionPhotoMuxer.mux(jpeg.readBytes(), video, presentationTimestampUs, output)
        }
    }

    fun savePhoto(jpeg: File, capturedAtMillis: Long, location: Location?): Uri {
        location?.let { writeExifLocation(jpeg, it) }
        return insertImage(MediaKind.PHOTO, capturedAtMillis) { output ->
            jpeg.inputStream().use { it.copyTo(output) }
        }
    }

    /** The Recorder writes the video into MediaStore itself and handles the pending state. */
    fun videoOutputOptions(capturedAtMillis: Long, location: Location?): MediaStoreOutputOptions {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, MediaNaming.fileName(MediaKind.VIDEO, capturedAtMillis))
            put(MediaStore.Video.Media.MIME_TYPE, MediaKind.VIDEO.mimeType)
            put(MediaStore.Video.Media.RELATIVE_PATH, RELATIVE_PATH)
            put(MediaStore.Video.Media.DATE_TAKEN, capturedAtMillis)
        }
        return MediaStoreOutputOptions.Builder(resolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(values)
            .setLocation(location)
            .build()
    }

    /**
     * Creates a pending image entry, lets [write] fill it and publishes it.
     * The entry stays invisible to other apps until complete and is removed on failure.
     */
    private fun insertImage(kind: MediaKind, capturedAtMillis: Long, write: (OutputStream) -> Unit): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, MediaNaming.fileName(kind, capturedAtMillis))
            put(MediaStore.Images.Media.MIME_TYPE, kind.mimeType)
            put(MediaStore.Images.Media.RELATIVE_PATH, RELATIVE_PATH)
            put(MediaStore.Images.Media.DATE_TAKEN, capturedAtMillis)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values)
            ?: throw IOException("MediaStore refused to create an entry in $RELATIVE_PATH")
        try {
            val output = resolver.openOutputStream(uri, "w") ?: throw IOException("Cannot open $uri for writing")
            output.buffered().use(write)
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            return uri
        } catch (e: Throwable) {
            try {
                resolver.delete(uri, null, null)
            } catch (cleanup: Exception) {
                e.addSuppressed(cleanup)
            }
            throw e
        }
    }

    private fun writeExifLocation(jpeg: File, location: Location) {
        ExifInterface(jpeg).apply {
            setGpsInfo(location)
            saveAttributes()
        }
    }

    private companion object {
        val RELATIVE_PATH = Environment.DIRECTORY_DCIM + "/Camera"
    }
}
