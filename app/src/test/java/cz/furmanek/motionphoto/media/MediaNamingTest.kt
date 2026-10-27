package cz.furmanek.motionphoto.media

import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaNamingTest {

    private val originalZone = TimeZone.getDefault()

    @After
    fun restoreZone() = TimeZone.setDefault(originalZone)

    @Test
    fun `motion photo uses the Pixel name with MP suffix`() {
        assertEquals("PXL_20251026_171338.MP.jpg", MediaNaming.fileName(MediaKind.MOTION_PHOTO, 1_761_498_818_000))
    }

    @Test
    fun `plain photo and video get their own suffixes`() {
        assertEquals("PXL_20251026_171338.jpg", MediaNaming.fileName(MediaKind.PHOTO, 1_761_498_818_000))
        assertEquals("PXL_20251026_171338.mp4", MediaNaming.fileName(MediaKind.VIDEO, 1_761_498_818_000))
    }

    @Test
    fun `panorama gets the PANO suffix`() {
        assertEquals("PXL_20251026_171338.PANO.jpg", MediaNaming.fileName(MediaKind.PANORAMA, 1_761_498_818_000))
    }

    @Test
    fun `name is in UTC regardless of the device time zone`() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))

        assertEquals("PXL_20251026_171338.jpg", MediaNaming.fileName(MediaKind.PHOTO, 1_761_498_818_000))
    }
}
