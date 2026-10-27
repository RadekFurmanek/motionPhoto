package cz.furmanek.motionphoto.media

import com.adobe.internal.xmp.XMPMetaFactory
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PanoramaXmpTest {

    /** SOI, a JFIF APP0, then scan data and EOI. */
    private val jpeg = byteArrayOf(
        0xFF.toByte(), 0xD8.toByte(),
        0xFF.toByte(), 0xE0.toByte(), 0x00, 0x06, 'J'.code.toByte(), 'F'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(),
        0xFF.toByte(), 0xDA.toByte(), 0x00, 0x02, 0x11, 0x22, 0x33,
        0xFF.toByte(), 0xD9.toByte(),
    )

    @Test
    fun insertedXmpParsesWithGPanoValues() {
        val out = PanoramaXmp.insert(jpeg, width = 4000, height = 1200, fullWidth = 9000, fullHeight = 4500, left = 2500, top = 1700)
        assertEquals(0xFF, out[0].toInt() and 0xFF)
        assertEquals(0xD8, out[1].toInt() and 0xFF)
        val text = String(out, Charsets.ISO_8859_1)
        val packetStart = text.indexOf("<x:xmpmeta")
        val packetEnd = text.indexOf("</x:xmpmeta>") + "</x:xmpmeta>".length
        val meta = XMPMetaFactory.parseFromString(text.substring(packetStart, packetEnd))!!
        val ns = PanoramaXmp.GPANO_NS
        assertEquals("equirectangular", meta.getPropertyString(ns, "ProjectionType"))
        assertEquals("True", meta.getPropertyString(ns, "UsePanoramaViewer"))
        assertEquals(4000, meta.getPropertyInteger(ns, "CroppedAreaImageWidthPixels"))
        assertEquals(1200, meta.getPropertyInteger(ns, "CroppedAreaImageHeightPixels"))
        assertEquals(9000, meta.getPropertyInteger(ns, "FullPanoWidthPixels"))
        assertEquals(4500, meta.getPropertyInteger(ns, "FullPanoHeightPixels"))
        assertEquals(2500, meta.getPropertyInteger(ns, "CroppedAreaLeftPixels"))
        assertEquals(1700, meta.getPropertyInteger(ns, "CroppedAreaTopPixels"))
        // The image data after the header is untouched.
        val scan = jpeg.copyOfRange(10, jpeg.size)
        assertArrayEquals(scan, out.copyOfRange(out.size - scan.size, out.size))
    }
}
