package cz.furmanek.motionphoto.media

import com.adobe.internal.xmp.XMPMetaFactory
import cz.furmanek.motionphoto.motionphoto.JpegXmp

/**
 * Photo Sphere (GPano) XMP that makes Google Photos open a panorama in its viewer: the image is a [width]×[height]
 * crop at ([left], [top]) of a full 360°×180° equirectangular image of [fullWidth]×[fullHeight].
 */
object PanoramaXmp {

    const val GPANO_NS = "http://ns.google.com/photos/1.0/panorama/"

    init {
        XMPMetaFactory.getSchemaRegistry().registerNamespace(GPANO_NS, "GPano")
    }

    fun insert(jpeg: ByteArray, width: Int, height: Int, fullWidth: Int, fullHeight: Int, left: Int, top: Int): ByteArray =
        JpegXmp.withXmp(jpeg) { meta ->
            meta.setProperty(GPANO_NS, "UsePanoramaViewer", "True")
            meta.setProperty(GPANO_NS, "ProjectionType", "equirectangular")
            meta.setPropertyInteger(GPANO_NS, "CroppedAreaImageWidthPixels", width)
            meta.setPropertyInteger(GPANO_NS, "CroppedAreaImageHeightPixels", height)
            meta.setPropertyInteger(GPANO_NS, "FullPanoWidthPixels", fullWidth)
            meta.setPropertyInteger(GPANO_NS, "FullPanoHeightPixels", fullHeight)
            meta.setPropertyInteger(GPANO_NS, "CroppedAreaLeftPixels", left)
            meta.setPropertyInteger(GPANO_NS, "CroppedAreaTopPixels", top)
        }
}
