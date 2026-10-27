package cz.furmanek.motionphoto.portrait

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.core.graphics.scale
import androidx.exifinterface.media.ExifInterface
import cz.furmanek.motionphoto.panorama.Raster
import cz.furmanek.motionphoto.settings.PortraitResolution
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class PortraitOutcome { PORTRAIT, NO_SUBJECT, MODEL_MISSING, OUT_OF_MEMORY, FAILED }

/** Blurs the background of a photo behind the subject that [masker] finds. */
class PortraitProcessor(private val masker: SubjectMasker) {

    /** Rewrites [jpeg] in place as a portrait; for any outcome but [PortraitOutcome.PORTRAIT] it is left untouched. */
    suspend fun process(jpeg: File, resolution: PortraitResolution): PortraitOutcome = withContext(Dispatchers.Default) {
        var photo: Bitmap? = null
        var small: Bitmap? = null
        var temp: File? = null
        try {
            // Pixels stay as stored (not rotated) and the EXIF orientation is kept; only the segmenter sees them upright.
            val exif = ExifInterface(jpeg)
            val degrees = exif.rotationDegrees
            val flipped = exif.isFlipped
            // On API 26+ a decode that runs out of native memory returns null instead of throwing.
            photo = decode(jpeg, resolution) ?: return@withContext when (resolution) {
                PortraitResolution.FULL -> PortraitOutcome.OUT_OF_MEMORY
                PortraitResolution.STANDARD -> PortraitOutcome.FAILED
            }
            val (maskWidth, maskHeight) = fitInside(photo.width, photo.height, MASK_MAX_SIDE)
            small = photo.scale(maskWidth, maskHeight)
            val smallPixels = IntArray(maskWidth * maskHeight)
            small.getPixels(smallPixels, 0, maskWidth, 0, 0, maskWidth, maskHeight)
            if (small !== photo) small.recycle()
            small = null

            val confidence = segmentUpright(smallPixels, maskWidth, maskHeight, degrees, flipped)
                ?: return@withContext PortraitOutcome.MODEL_MISSING
            if (!hasSubject(confidence)) return@withContext PortraitOutcome.NO_SUBJECT
            val longest = max(maskWidth, maskHeight)
            val soft = blurMask(confidence, maskWidth, maskHeight, radius(longest, MASK_FEATHER))

            val background = boxBlur(Raster(maskWidth, maskHeight, smallPixels), radius(longest, BACKGROUND_BLUR)).pixels

            ensureActive()
            composite(photo, soft, background, maskWidth, maskHeight)

            temp = File.createTempFile(TEMP_PREFIX, ".jpg", jpeg.parentFile)
            temp.outputStream().use { out ->
                if (!photo.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) throw IOException("JPEG encoding failed")
            }
            copyExif(jpeg, temp)
            if (!temp.renameTo(jpeg)) {
                temp.copyTo(jpeg, overwrite = true)
            }
            PortraitOutcome.PORTRAIT
        } catch (e: CancellationException) {
            throw e
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "Out of memory making a portrait", e)
            PortraitOutcome.OUT_OF_MEMORY
        } catch (e: Exception) {
            Log.w(TAG, "Making a portrait failed", e)
            PortraitOutcome.FAILED
        } finally {
            if (small != null && small !== photo) small.recycle()
            photo?.recycle()
            temp?.delete()
        }
    }

    /**
     * Segments the small stored image turned upright, as the segmenter expects people standing, and returns
     * the confidence mask turned back to the stored [width] x [height]; null when the model is missing.
     */
    private suspend fun segmentUpright(pixels: IntArray, width: Int, height: Int, degrees: Int, flipped: Boolean): FloatArray? {
        val (uprightWidth, uprightHeight) = turnedSize(width, height, degrees)
        val upright = Bitmap.createBitmap(
            storedToUpright(pixels, width, height, degrees, flipped), uprightWidth, uprightHeight, Bitmap.Config.ARGB_8888,
        )
        try {
            val mask = masker.mask(upright) ?: return null
            return uprightToStored(mask.confidence, mask.width, mask.height, degrees, flipped)
        } finally {
            upright.recycle()
        }
    }

    private fun decode(jpeg: File, resolution: PortraitResolution): Bitmap? {
        val options = BitmapFactory.Options().apply {
            inMutable = true
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(jpeg.path, bounds)
        // An unreadable file is a failure; a null from the real decode below then means memory ran out.
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("Cannot read ${jpeg.name}")
        if (resolution == PortraitResolution.STANDARD) {
            options.inSampleSize = standardSampleSize(bounds.outWidth, bounds.outHeight)
        }
        return BitmapFactory.decodeFile(jpeg.path, options)
    }

    /**
     * Blends [photo] in place with the background, strip by strip so only one strip is copied out at a time.
     * The mask and the blurred background are both [maskWidth] x [maskHeight] and are sampled bilinearly.
     */
    private fun composite(photo: Bitmap, mask: FloatArray, background: IntArray, maskWidth: Int, maskHeight: Int) {
        val width = photo.width
        val height = photo.height
        val scaleX = maskWidth.toFloat() / width
        val scaleY = maskHeight.toFloat() / height
        val strip = IntArray(width * STRIP_ROWS)
        var top = 0
        while (top < height) {
            val rows = min(STRIP_ROWS, height - top)
            photo.getPixels(strip, 0, width, 0, top, width, rows)
            for (row in 0 until rows) {
                val my = toMaskCoordinate(top + row, scaleY)
                val offset = row * width
                for (x in 0 until width) {
                    val mx = toMaskCoordinate(x, scaleX)
                    val alpha = maskAt(mask, maskWidth, maskHeight, mx, my)
                    if (alpha >= 1f) continue // The subject itself stays as it is.
                    val blurred = argbAt(background, maskWidth, maskHeight, mx, my)
                    strip[offset + x] = blend(strip[offset + x], blurred, alpha)
                }
            }
            photo.setPixels(strip, 0, width, 0, top, width, rows)
            top += rows
        }
    }

    /** Carries orientation, capture time and location over from [original] to the re-encoded [target]. */
    private fun copyExif(original: File, target: File) {
        val source = ExifInterface(original)
        val dest = ExifInterface(target)
        for (tag in EXIF_TAGS) {
            source.getAttribute(tag)?.let { dest.setAttribute(tag, it) }
        }
        dest.saveAttributes()
    }

    private companion object {
        const val TAG = "PortraitProcessor"
        const val TEMP_PREFIX = "portrait_"
        const val MASK_MAX_SIDE = 1024
        const val STRIP_ROWS = 64
        const val JPEG_QUALITY = 95

        /** Mask feathering and background blur radii as fractions of the mask's longer side. */
        const val MASK_FEATHER = 0.01f
        const val BACKGROUND_BLUR = 0.025f

        fun radius(longestSide: Int, fraction: Float): Int = max(1, (longestSide * fraction).roundToInt())

        val EXIF_TAGS = listOf(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.TAG_DATETIME,
            ExifInterface.TAG_DATETIME_ORIGINAL,
            ExifInterface.TAG_DATETIME_DIGITIZED,
            ExifInterface.TAG_OFFSET_TIME,
            ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
            ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
            ExifInterface.TAG_SUBSEC_TIME,
            ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
            ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,
            ExifInterface.TAG_GPS_VERSION_ID,
            ExifInterface.TAG_GPS_LATITUDE,
            ExifInterface.TAG_GPS_LATITUDE_REF,
            ExifInterface.TAG_GPS_LONGITUDE,
            ExifInterface.TAG_GPS_LONGITUDE_REF,
            ExifInterface.TAG_GPS_ALTITUDE,
            ExifInterface.TAG_GPS_ALTITUDE_REF,
            ExifInterface.TAG_GPS_TIMESTAMP,
            ExifInterface.TAG_GPS_DATESTAMP,
            ExifInterface.TAG_GPS_PROCESSING_METHOD,
            ExifInterface.TAG_GPS_SPEED,
            ExifInterface.TAG_GPS_SPEED_REF,
            ExifInterface.TAG_GPS_IMG_DIRECTION,
            ExifInterface.TAG_GPS_IMG_DIRECTION_REF,
        )
    }
}
