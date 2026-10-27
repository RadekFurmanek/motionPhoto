package cz.furmanek.motionphoto.portrait

import android.content.Context
import android.graphics.Bitmap
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import kotlinx.coroutines.tasks.await
import java.io.Closeable

/** Foreground confidence (0..1) per pixel, row by row. */
class Mask(val width: Int, val height: Int, val confidence: FloatArray)

interface SubjectMasker {
    /** Google Play services are present, so the model can be used at all. */
    val available: Boolean

    /** Whether the model is installed; if not, starts its download and returns false without waiting. */
    suspend fun ensureModel(): Boolean

    /** The subject mask at the size of [bitmap], or null when the model is not installed. */
    suspend fun mask(bitmap: Bitmap): Mask?
}

/** ML Kit subject segmentation; its model is an optional Play services module downloaded on first use. */
class MlKitSubjectMasker(context: Context) : SubjectMasker, Closeable {
    private val appContext = context.applicationContext
    private val segmenter = SubjectSegmentation.getClient(
        SubjectSegmenterOptions.Builder().enableForegroundConfidenceMask().build(),
    )
    private val moduleInstall = ModuleInstall.getClient(appContext)

    override val available: Boolean
        get() = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(appContext) == ConnectionResult.SUCCESS

    /** Once the model is installed it stays, so later calls skip the Play services checks. */
    @Volatile
    private var modelReady = false

    /** When the model download was last requested. */
    @Volatile
    private var installRequestedAt = 0L

    override suspend fun ensureModel(): Boolean {
        if (modelReady) return true
        if (!available) return false
        if (moduleInstall.areModulesAvailable(segmenter).await().areModulesAvailable()) {
            modelReady = true
            return true
        }
        // Fire and forget, at most once a minute, so a request lost offline is asked again: the photo being
        // taken now stays ordinary, later ones get the portrait.
        val now = System.currentTimeMillis()
        if (now - installRequestedAt >= INSTALL_RETRY_MS) {
            installRequestedAt = now
            moduleInstall.installModules(ModuleInstallRequest.newBuilder().addApi(segmenter).build())
        }
        return false
    }

    override suspend fun mask(bitmap: Bitmap): Mask? {
        if (!ensureModel()) return null
        val result = segmenter.process(InputImage.fromBitmap(bitmap, 0)).await()
        val buffer = result.foregroundConfidenceMask ?: return null
        buffer.rewind()
        val confidence = FloatArray(bitmap.width * bitmap.height)
        buffer.get(confidence)
        return Mask(bitmap.width, bitmap.height, confidence)
    }

    override fun close() = segmenter.close()

    private companion object {
        const val INSTALL_RETRY_MS = 60_000L
    }
}
