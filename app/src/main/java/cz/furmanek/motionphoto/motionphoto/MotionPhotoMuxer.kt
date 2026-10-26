package cz.furmanek.motionphoto.motionphoto

import com.adobe.internal.xmp.XMPException
import com.adobe.internal.xmp.XMPMeta
import com.adobe.internal.xmp.XMPMetaFactory
import com.adobe.internal.xmp.XMPPathFactory
import com.adobe.internal.xmp.options.PropertyOptions
import com.adobe.internal.xmp.options.SerializeOptions
import java.io.ByteArrayOutputStream
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

    private const val XMP_NOTE_NS = "http://ns.adobe.com/xmp/note/"

    private const val MARKER_SOI = 0xD8
    private const val MARKER_EOI = 0xD9
    private const val MARKER_SOS = 0xDA
    private const val MARKER_TEM = 0x01
    private const val MARKER_APP0 = 0xE0
    private const val MARKER_APP1 = 0xE1

    /** A segment length field counts itself (2 bytes) but not the marker. */
    private const val MAX_SEGMENT_PAYLOAD = 0xFFFF - 2

    private val XMP_SIGNATURE = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.ISO_8859_1)
    private val EXTENDED_XMP_SIGNATURE = "http://ns.adobe.com/xmp/extension/\u0000".toByteArray(Charsets.ISO_8859_1)
    private val EXIF_SIGNATURE = "Exif\u0000\u0000".toByteArray(Charsets.ISO_8859_1)

    // Properties of the legacy MicroVideo format and of a previous container; left in place they would
    // describe a layout that no longer matches the file.
    private val STALE_GCAMERA_PROPERTIES = listOf(
        "MicroVideo", "MicroVideoVersion", "MicroVideoOffset", "MicroVideoPresentationTimestampUs",
    )

    private data class DirectoryItem(val mime: String, val semantic: String, val length: Long, val padding: Long?)

    private class Segment(val marker: Int, val start: Int, val payloadStart: Int, val end: Int)

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

        val (segments, scanStart) = parseHeaderSegments(jpeg)
        val existingXmp = segments.firstOrNull { it.hasSignature(jpeg, XMP_SIGNATURE) }
            ?.let { jpeg.copyOfRange(it.payloadStart + XMP_SIGNATURE.size, it.end) }
        val xmpPayload = XMP_SIGNATURE + buildXmp(existingXmp, videoLength, presentationTimestampUs)
        check(xmpPayload.size <= MAX_SEGMENT_PAYLOAD) { "XMP packet of ${xmpPayload.size} bytes does not fit in APP1" }

        val out = ByteArrayOutputStream(jpeg.size + xmpPayload.size + 4)
        out.write(0xFF)
        out.write(MARKER_SOI)
        var xmpWritten = false
        for (segment in segments) {
            if (segment.hasSignature(jpeg, XMP_SIGNATURE) || segment.hasSignature(jpeg, EXTENDED_XMP_SIGNATURE)) {
                continue
            }
            // JFIF APP0 and Exif APP1 must stay first; XMP goes right after them.
            val isLeadingSegment = segment.marker == MARKER_APP0 || segment.hasSignature(jpeg, EXIF_SIGNATURE)
            if (!xmpWritten && !isLeadingSegment) {
                out.writeApp1(xmpPayload)
                xmpWritten = true
            }
            out.write(jpeg, segment.start, segment.end - segment.start)
        }
        if (!xmpWritten) out.writeApp1(xmpPayload)
        out.write(jpeg, scanStart, jpeg.size - scanStart)
        return out.toByteArray()
    }

    private fun buildXmp(existingXmp: ByteArray?, videoLength: Long, presentationTimestampUs: Long): ByteArray {
        val meta = existingXmp?.let(::parseOrNull) ?: XMPMetaFactory.create()

        STALE_GCAMERA_PROPERTIES.forEach { meta.deleteProperty(GCAMERA_NS, it) }
        meta.deleteProperty(CONTAINER_NS, "Directory")
        // Extended XMP segments are dropped from the file, so the pointer to them must go too.
        meta.deleteProperty(XMP_NOTE_NS, "HasExtendedXMP")

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

        // Compact form writes the directory items as Container:Item attributes, the layout Google Camera emits.
        val options = SerializeOptions().setUseCompactFormat(true)
        return XMPMetaFactory.serializeToBuffer(meta, options)
    }

    /** An unreadable XMP packet from the camera is replaced rather than failing the whole capture. */
    private fun parseOrNull(xmp: ByteArray): XMPMeta? =
        try {
            XMPMetaFactory.parseFromBuffer(xmp)
        } catch (_: XMPException) {
            null
        }

    /**
     * Returns the marker segments between SOI and the first SOS (or EOI), and the offset where that marker starts.
     * Everything from there on is image data and is copied verbatim.
     */
    private fun parseHeaderSegments(jpeg: ByteArray): Pair<List<Segment>, Int> {
        require(jpeg.size >= 4 && jpeg.u8(0) == 0xFF && jpeg.u8(1) == MARKER_SOI) { "Data is not a JPEG image" }
        val segments = mutableListOf<Segment>()
        var pos = 2
        while (true) {
            require(pos + 1 < jpeg.size && jpeg.u8(pos) == 0xFF) { "Malformed JPEG: expected a marker at offset $pos" }
            var markerPos = pos
            while (markerPos + 1 < jpeg.size && jpeg.u8(markerPos + 1) == 0xFF) markerPos++
            require(markerPos + 1 < jpeg.size) { "Malformed JPEG: truncated at offset $pos" }
            val marker = jpeg.u8(markerPos + 1)
            when (marker) {
                MARKER_SOS, MARKER_EOI -> return segments to pos
                MARKER_TEM, in 0xD0..0xD7 -> {
                    segments += Segment(marker, pos, markerPos + 2, markerPos + 2)
                    pos = markerPos + 2
                }
                else -> {
                    require(markerPos + 3 < jpeg.size) { "Malformed JPEG: truncated segment at offset $pos" }
                    val length = (jpeg.u8(markerPos + 2) shl 8) or jpeg.u8(markerPos + 3)
                    val end = markerPos + 2 + length
                    require(length >= 2 && end <= jpeg.size) { "Malformed JPEG: bad segment length at offset $pos" }
                    segments += Segment(marker, pos, markerPos + 4, end)
                    pos = end
                }
            }
        }
    }

    private fun Segment.hasSignature(jpeg: ByteArray, signature: ByteArray): Boolean {
        if (marker != MARKER_APP1 || end - payloadStart < signature.size) return false
        return signature.indices.all { jpeg[payloadStart + it] == signature[it] }
    }

    private fun ByteArrayOutputStream.writeApp1(payload: ByteArray) {
        val length = payload.size + 2
        write(0xFF)
        write(MARKER_APP1)
        write(length shr 8)
        write(length and 0xFF)
        write(payload)
    }

    private fun ByteArray.u8(index: Int): Int = this[index].toInt() and 0xFF
}
