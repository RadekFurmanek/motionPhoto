package cz.furmanek.motionphoto.media

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.location.Location
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.camera.video.MediaStoreOutputOptions
import androidx.exifinterface.media.ExifInterface
import cz.furmanek.motionphoto.motionphoto.MotionPhotoMuxer
import cz.furmanek.motionphoto.panorama.Panorama
import java.io.File
import java.io.IOException
import java.io.OutputStream

/** Saves captures to the shared DCIM/Camera folder, where Google Photos picks them up. */
class MediaStoreWriter(private val resolver: ContentResolver, private val packageName: String) {

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

    fun savePhoto(jpeg: File, capturedAtMillis: Long, location: Location?): Uri =
        saveJpegFile(MediaKind.PHOTO, jpeg, capturedAtMillis, location)

    /**
     * Saves a stitched panorama as PXL_….PANO.jpg with GPano XMP from [panorama]. EXIF needs a file, so it goes
     * through a temp file; the XMP is added after the EXIF write so that cannot drop it.
     */
    fun savePanorama(jpeg: ByteArray, panorama: Panorama, capturedAtMillis: Long, location: Location?): Uri {
        val file = File.createTempFile(TEMP_PANORAMA_PREFIX, ".jpg")
        try {
            file.writeBytes(jpeg)
            location?.let { writeExifLocation(file, it) }
            val tagged = PanoramaXmp.insert(
                file.readBytes(),
                width = panorama.image.width,
                height = panorama.image.height,
                fullWidth = panorama.fullWidth,
                fullHeight = panorama.fullHeight,
                left = panorama.left,
                top = panorama.top,
            )
            return insertImage(MediaKind.PANORAMA, capturedAtMillis) { output -> output.write(tagged) }
        } finally {
            file.delete()
        }
    }

    private fun saveJpegFile(kind: MediaKind, jpeg: File, capturedAtMillis: Long, location: Location?): Uri {
        location?.let { writeExifLocation(jpeg, it) }
        return insertImage(kind, capturedAtMillis) { output ->
            jpeg.inputStream().use { it.copyTo(output) }
        }
    }

    /** Copies a finished video file into DCIM/Camera (used when another app asked for a video without a target). */
    fun saveVideoFile(video: File, capturedAtMillis: Long): Uri {
        val values = pendingVideoValues(capturedAtMillis)
        return insertPending(videoCollection(), values, MediaStore.Video.Media.IS_PENDING) { output ->
            video.inputStream().use { it.copyTo(output) }
        }
    }

    /** A video entry in DCIM/Camera that stays hidden until [publishVideo]; the caller writes through [descriptor]. */
    class PendingVideo(val uri: Uri, val descriptor: ParcelFileDescriptor)

    /**
     * Creates a pending video entry for a writer that cannot stream, such as MediaMuxer: it is opened for reading
     * and writing because the muxer seeks back to write the index. Finish with [publishVideo] or [discardVideo].
     */
    fun createPendingVideo(capturedAtMillis: Long): PendingVideo {
        val uri = resolver.insert(videoCollection(), pendingVideoValues(capturedAtMillis))
            ?: throw IOException("MediaStore refused to create an entry in $RELATIVE_PATH")
        try {
            val descriptor = resolver.openFileDescriptor(uri, "rw") ?: throw IOException("Cannot open $uri for writing")
            return PendingVideo(uri, descriptor)
        } catch (e: Throwable) {
            deleteAfterFailure(uri, e)
        }
    }

    fun publishVideo(uri: Uri) {
        resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
    }

    fun discardVideo(uri: Uri) {
        resolver.delete(uri, null, null)
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

    /** The newest photo or video this app saved, for the last-capture thumbnail after a restart. */
    fun latestOwnCapture(): Uri? {
        val collections = listOf(
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
        )
        return collections.mapNotNull { collection ->
            resolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATE_ADDED),
                "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?",
                arrayOf(packageName),
                "${MediaStore.MediaColumns.DATE_ADDED} DESC",
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                ContentUris.withAppendedId(collection, cursor.getLong(0)) to cursor.getLong(1)
            }
        }.maxByOrNull { it.second }?.first
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
        return insertPending(collection, values, MediaStore.Images.Media.IS_PENDING, write)
    }

    private fun insertPending(
        collection: Uri,
        values: ContentValues,
        pendingColumn: String,
        write: (OutputStream) -> Unit,
    ): Uri {
        val uri = resolver.insert(collection, values)
            ?: throw IOException("MediaStore refused to create an entry in $RELATIVE_PATH")
        try {
            val output = resolver.openOutputStream(uri, "w") ?: throw IOException("Cannot open $uri for writing")
            output.buffered().use(write)
            resolver.update(uri, ContentValues().apply { put(pendingColumn, 0) }, null, null)
            return uri
        } catch (e: Throwable) {
            deleteAfterFailure(uri, e)
        }
    }

    private fun deleteAfterFailure(uri: Uri, e: Throwable): Nothing {
        try {
            resolver.delete(uri, null, null)
        } catch (cleanup: Exception) {
            e.addSuppressed(cleanup)
        }
        throw e
    }

    private fun videoCollection(): Uri = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    private fun pendingVideoValues(capturedAtMillis: Long) = ContentValues().apply {
        put(MediaStore.Video.Media.DISPLAY_NAME, MediaNaming.fileName(MediaKind.VIDEO, capturedAtMillis))
        put(MediaStore.Video.Media.MIME_TYPE, MediaKind.VIDEO.mimeType)
        put(MediaStore.Video.Media.RELATIVE_PATH, RELATIVE_PATH)
        put(MediaStore.Video.Media.DATE_TAKEN, capturedAtMillis)
        put(MediaStore.Video.Media.IS_PENDING, 1)
    }

    private fun writeExifLocation(jpeg: File, location: Location) {
        ExifInterface(jpeg).apply {
            setGpsInfo(location)
            saveAttributes()
        }
    }

    private companion object {
        val RELATIVE_PATH = Environment.DIRECTORY_DCIM + "/Camera"
        const val TEMP_PANORAMA_PREFIX = "panorama_"
    }
}
