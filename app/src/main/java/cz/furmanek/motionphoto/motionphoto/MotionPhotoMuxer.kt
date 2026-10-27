package cz.furmanek.motionphoto.motionphoto

import com.adobe.internal.xmp.XMPMeta
import com.adobe.internal.xmp.XMPMetaFactory
import com.adobe.internal.xmp.XMPPathFactory
import com.adobe.internal.xmp.options.PropertyOptions
import java.io.File
import java.io.IOException
import java.io.OutputStream

/**
 * Builds Motion Photo files in the Google Camera format (Motion Photo v1):
 * a JPEG whose XMP describes a container, followed directly by the MP4 bytes.
 *
 * Readers locate the video from the end of the file (offset = file length − MotionPhoto item Length),
 * so the MP4 must be appended byte-for-byte with nothing after it.
 */
object MotionPhotoMuxer {

    const val GCAMERA_NS = "http://ns.google.com/photos/1.0/camera/"
    const val CONTAINER_NS = "http://ns.google.com/photos/1.0/container/"
    const val ITEM_NS = "http://ns.google.com/photos/1.0/container/item/"

    /** Spec value for "the still has no matching video frame". */
    const val PRESENTATION_TIMESTAMP_UNKNOWN = -1L

    // Properties of the legacy MicroVideo format and of a previous container; left in place they would
    // describe a layout that no longer matches the file.
    private val STALE_GCAMERA_PROPERTIES = listOf(
        "MicroVideo", "MicroVideoVersion", "MicroVideoOffset", "MicroVideoPresentationTimestampUs",
    )

    private data class DirectoryItem(val mime: String, val semantic: String, val length: Long, val padding: Long?)

    init {
        val registry = XMPMetaFactory.getSchemaRegistry()
        registry.registerNamespace(GCAMERA_NS, "GCamera")
        registry.registerNamespace(CONTAINER_NS, "Container")
        registry.registerNamespace(ITEM_NS, "Item")
    }

    /**
     * Writes the complete Motion Photo — [jpeg] with Motion Photo XMP, then the contents of [video] — to [output].
     *
     * @param presentationTimestampUs timestamp of the video frame matching the still, in microseconds from the
     * start of the video, or [PRESENTATION_TIMESTAMP_UNKNOWN].
     */
    fun mux(jpeg: ByteArray, video: File, presentationTimestampUs: Long, output: OutputStream) {
        val videoLength = video.length()
        require(videoLength > 0) { "Video file ${video.path} is missing or empty" }

        output.write(createPrimaryImage(jpeg, videoLength, presentationTimestampUs))
        val copied = video.inputStream().use { it.copyTo(output) }
        if (copied != videoLength) {
            throw IOException("Video file changed while muxing: expected $videoLength bytes, copied $copied")
        }
        output.flush()
    }

    /**
     * Returns [jpeg] with an XMP APP1 segment declaring a Motion Photo whose MP4 of [videoLength] bytes
     * will be appended right after the image. Any XMP already in the image is kept and extended.
     */
    fun createPrimaryImage(jpeg: ByteArray, videoLength: Long, presentationTimestampUs: Long): ByteArray {
        require(videoLength > 0) { "Video length must be positive, was $videoLength" }
        require(presentationTimestampUs >= PRESENTATION_TIMESTAMP_UNKNOWN) {
            "Presentation timestamp must be >= 0 or PRESENTATION_TIMESTAMP_UNKNOWN, was $presentationTimestampUs"
        }

        return JpegXmp.withXmp(jpeg) { meta -> describeVideo(meta, videoLength, presentationTimestampUs) }
    }

    private fun describeVideo(meta: XMPMeta, videoLength: Long, presentationTimestampUs: Long) {
        STALE_GCAMERA_PROPERTIES.forEach { meta.deleteProperty(GCAMERA_NS, it) }
        meta.deleteProperty(CONTAINER_NS, "Directory")

        meta.setProperty(GCAMERA_NS, "MotionPhoto", "1")
        meta.setProperty(GCAMERA_NS, "MotionPhotoVersion", "1")
        meta.setProperty(GCAMERA_NS, "MotionPhotoPresentationTimestampUs", presentationTimestampUs.toString())

        val items = listOf(
            DirectoryItem(mime = "image/jpeg", semantic = "Primary", length = 0, padding = 0),
            DirectoryItem(mime = "video/mp4", semantic = "MotionPhoto", length = videoLength, padding = null),
        )
        val arrayOptions = PropertyOptions().setArray(true).setArrayOrdered(true)
        items.forEachIndexed { index, item ->
            meta.appendArrayItem(CONTAINER_NS, "Directory", arrayOptions, null, PropertyOptions().setStruct(true))
            val listItemPath = XMPPathFactory.composeArrayItemPath("Directory", index + 1)
            meta.setStructField(CONTAINER_NS, listItemPath, CONTAINER_NS, "Item", null, PropertyOptions().setStruct(true))
            val itemPath = listItemPath + XMPPathFactory.composeStructFieldPath(CONTAINER_NS, "Item")
            meta.setStructField(CONTAINER_NS, itemPath, ITEM_NS, "Mime", item.mime)
            meta.setStructField(CONTAINER_NS, itemPath, ITEM_NS, "Semantic", item.semantic)
            meta.setStructField(CONTAINER_NS, itemPath, ITEM_NS, "Length", item.length.toString())
            item.padding?.let { meta.setStructField(CONTAINER_NS, itemPath, ITEM_NS, "Padding", it.toString()) }
        }
    }
}
