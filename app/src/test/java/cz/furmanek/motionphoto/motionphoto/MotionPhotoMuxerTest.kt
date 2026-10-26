package cz.furmanek.motionphoto.motionphoto

import com.adobe.internal.xmp.XMPMeta
import com.adobe.internal.xmp.XMPMetaFactory
import com.adobe.internal.xmp.XMPPathFactory
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MotionPhotoMuxerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `primary image carries Motion Photo XMP and still decodes`() {
        val jpeg = realJpeg()

        val primary = MotionPhotoMuxer.createPrimaryImage(jpeg, videoLength = 1234, presentationTimestampUs = 987_654)

        val meta = parseXmp(primary)
        assertEquals("1", meta.getPropertyString(MotionPhotoMuxer.GCAMERA_NS, "MotionPhoto"))
        assertEquals("1", meta.getPropertyString(MotionPhotoMuxer.GCAMERA_NS, "MotionPhotoVersion"))
        assertEquals("987654", meta.getPropertyString(MotionPhotoMuxer.GCAMERA_NS, "MotionPhotoPresentationTimestampUs"))
        assertEquals(2, meta.countArrayItems(MotionPhotoMuxer.CONTAINER_NS, "Directory"))
        assertEquals(mapOf("Mime" to "image/jpeg", "Semantic" to "Primary", "Length" to "0", "Padding" to "0"), directoryItem(meta, 1))
        assertEquals(mapOf("Mime" to "video/mp4", "Semantic" to "MotionPhoto", "Length" to "1234"), directoryItem(meta, 2))
        assertNotNull(ImageIO.read(ByteArrayInputStream(primary)))
    }

    @Test
    fun `xmp uses the namespaces and prefixes from the Motion Photo spec`() {
        val xmp = String(xmpPayload(MotionPhotoMuxer.createPrimaryImage(realJpeg(), 42, 0)), Charsets.UTF_8)

        assertTrue(xmp.contains("xmlns:GCamera=\"http://ns.google.com/photos/1.0/camera/\""))
        assertTrue(xmp.contains("xmlns:Container=\"http://ns.google.com/photos/1.0/container/\""))
        assertTrue(xmp.contains("xmlns:Item=\"http://ns.google.com/photos/1.0/container/item/\""))
        assertTrue(xmp.contains("Item:Mime=\"video/mp4\""))
        assertTrue(xmp.contains("Item:Semantic=\"MotionPhoto\""))
        assertTrue(xmp.contains("Item:Length=\"42\""))
    }

    @Test
    fun `xmp segment follows exif and replaces existing xmp`() {
        val exif = app1("Exif\u0000\u0000".toByteArray(Charsets.ISO_8859_1) + ByteArray(16) { it.toByte() })
        val oldXmp = app1(
            "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.ISO_8859_1) +
                """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
                  <rdf:Description rdf:about="" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:Container="http://ns.google.com/photos/1.0/container/">
                  <dc:format>image/jpeg</dc:format><Container:Directory><rdf:Seq><rdf:li>stale</rdf:li></rdf:Seq></Container:Directory>
                  </rdf:Description></rdf:RDF></x:xmpmeta>""".toByteArray(Charsets.UTF_8),
        )
        val plain = realJpeg()
        val afterJfif = 4 + (((plain[4].toInt() and 0xFF) shl 8) or (plain[5].toInt() and 0xFF))
        val jpeg = plain.copyOfRange(0, afterJfif) + exif + oldXmp + plain.copyOfRange(afterJfif, plain.size)

        val primary = MotionPhotoMuxer.createPrimaryImage(jpeg, 10, 5)

        val markers = segments(primary)
        assertEquals(0xE0, markers[0].first)
        assertArrayEquals(exif.copyOfRange(4, exif.size), markers[1].second)
        assertTrue(isXmp(markers[2]))
        assertEquals(1, countXmpSegments(primary))
        val meta = parseXmp(primary)
        assertEquals("image/jpeg", meta.getPropertyString("http://purl.org/dc/elements/1.1/", "format"))
        assertEquals(2, meta.countArrayItems(MotionPhotoMuxer.CONTAINER_NS, "Directory"))
        assertEquals("5", meta.getPropertyString(MotionPhotoMuxer.GCAMERA_NS, "MotionPhotoPresentationTimestampUs"))
    }

    @Test
    fun `mux appends the whole video so its offset is file length minus item length`() {
        val video = ByteArray(50_000) { (it * 31).toByte() }
        val videoFile = tmp.newFile("clip.mp4").apply { writeBytes(video) }
        val out = ByteArrayOutputStream()

        MotionPhotoMuxer.mux(realJpeg(), videoFile, 1_000_000, out)

        val result = out.toByteArray()
        val length = directoryItem(parseXmp(result), 2).getValue("Length").toLong()
        assertEquals(video.size.toLong(), length)
        assertArrayEquals(video, result.copyOfRange(result.size - length.toInt(), result.size))
        val primary = result.copyOfRange(0, result.size - video.size)
        assertEquals(0xFF, primary[primary.size - 2].toInt() and 0xFF)
        assertEquals(0xD9, primary[primary.size - 1].toInt() and 0xFF)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects data that is not a JPEG`() {
        MotionPhotoMuxer.createPrimaryImage(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47), 1, 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects an empty video`() {
        MotionPhotoMuxer.mux(realJpeg(), File(tmp.root, "missing.mp4"), 0, ByteArrayOutputStream())
    }

    private fun realJpeg(): ByteArray {
        val image = BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB)
        for (x in 0 until 64) for (y in 0 until 48) image.setRGB(x, y, (x * 4 shl 16) or (y * 5 shl 8))
        return ByteArrayOutputStream().also { ImageIO.write(image, "jpg", it) }.toByteArray()
    }

    private fun app1(payload: ByteArray): ByteArray {
        val length = payload.size + 2
        return byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (length shr 8).toByte(), length.toByte()) + payload
    }

    private fun segments(jpeg: ByteArray): List<Pair<Int, ByteArray>> {
        val result = mutableListOf<Pair<Int, ByteArray>>()
        var pos = 2
        while (true) {
            val marker = jpeg[pos + 1].toInt() and 0xFF
            if (marker == 0xDA) return result
            val length = ((jpeg[pos + 2].toInt() and 0xFF) shl 8) or (jpeg[pos + 3].toInt() and 0xFF)
            result += marker to jpeg.copyOfRange(pos + 4, pos + 2 + length)
            pos += 2 + length
        }
    }

    private val xmpHeader = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.ISO_8859_1)

    private fun isXmp(segment: Pair<Int, ByteArray>) =
        segment.first == 0xE1 && segment.second.size > xmpHeader.size &&
            segment.second.copyOfRange(0, xmpHeader.size).contentEquals(xmpHeader)

    private fun countXmpSegments(jpeg: ByteArray) = segments(jpeg).count(::isXmp)

    private fun xmpPayload(jpeg: ByteArray): ByteArray =
        segments(jpeg).first(::isXmp).second.let { it.copyOfRange(xmpHeader.size, it.size) }

    private fun parseXmp(jpeg: ByteArray): XMPMeta = XMPMetaFactory.parseFromBuffer(xmpPayload(jpeg))

    private fun directoryItem(meta: XMPMeta, index: Int): Map<String, String> {
        val itemPath = XMPPathFactory.composeArrayItemPath("Directory", index) +
            XMPPathFactory.composeStructFieldPath(MotionPhotoMuxer.CONTAINER_NS, "Item")
        return listOf("Mime", "Semantic", "Length", "Padding").mapNotNull { field ->
            meta.getStructField(MotionPhotoMuxer.CONTAINER_NS, itemPath, MotionPhotoMuxer.ITEM_NS, field)
                ?.let { field to it.value }
        }.toMap()
    }
}
