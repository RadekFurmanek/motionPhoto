package cz.furmanek.motionphoto.motionphoto

import com.adobe.internal.xmp.XMPException
import com.adobe.internal.xmp.XMPMeta
import com.adobe.internal.xmp.XMPMetaFactory
import com.adobe.internal.xmp.options.SerializeOptions
import java.io.ByteArrayOutputStream

/** Rewrites the XMP packet of a JPEG in place of any it had, keeping every other segment and the image data. */
object JpegXmp {

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

    private class Segment(val marker: Int, val start: Int, val payloadStart: Int, val end: Int)

    /**
     * Returns [jpeg] with its XMP (or a new packet) changed by [edit], written right after the JFIF and Exif
     * segments. Extended XMP is dropped.
     */
    fun withXmp(jpeg: ByteArray, edit: (XMPMeta) -> Unit): ByteArray {
        val (segments, scanStart) = parseHeaderSegments(jpeg)
        val existingXmp = segments.firstOrNull { it.hasSignature(jpeg, XMP_SIGNATURE) }
            ?.let { jpeg.copyOfRange(it.payloadStart + XMP_SIGNATURE.size, it.end) }
        val meta = existingXmp?.let(::parseOrNull) ?: XMPMetaFactory.create()
        // Extended XMP segments are dropped from the file, so the pointer to them must go too.
        meta.deleteProperty(XMP_NOTE_NS, "HasExtendedXMP")
        edit(meta)
        // Compact form writes structs as attributes, the layout Google Camera emits.
        val xmpPayload = XMP_SIGNATURE + XMPMetaFactory.serializeToBuffer(meta, SerializeOptions().setUseCompactFormat(true))
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
