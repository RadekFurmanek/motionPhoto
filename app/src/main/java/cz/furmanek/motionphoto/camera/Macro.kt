package cz.furmanek.motionphoto.camera

import android.content.Context
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log
import kotlin.math.roundToInt

/** Default camera zoom for macro on phones without a close-focusing camera. */
const val MACRO_ZOOM = 2f

/** A lens must focus at least this close (10 cm) to count as macro. */
private const val MIN_MACRO_DIOPTERS = 10f

/** ...and this much closer than the default camera, which is otherwise the better camera. */
private const val CLOSER_THAN_DEFAULT = 1.5f

private const val TAG = "Macro"

/** Camera ids tried directly beyond the listed ones: some makers hide lenses from the list but still answer. */
private const val HIGHEST_PROBED_ID = 15

/**
 * A camera the phone lists: [cameraId] is what CameraX binds; [physicalId] a physical camera inside it (null for
 * the camera itself). [minFocusDiopters] is 1 / the closest focus in metres, 0 for fixed focus. A [hidden] camera is
 * missing from the phone's list but answered when asked by id; CameraX cannot open it.
 */
data class LensInfo(
    val cameraId: String,
    val physicalId: String?,
    val back: Boolean,
    val focalMm: Float,
    val minFocusDiopters: Float,
    val hidden: Boolean = false,
)

/** How Macro is done on this phone. */
sealed interface MacroSetup {
    /** A camera that focuses much closer than the default one. */
    data class Lens(val cameraId: String, val physicalId: String?) : MacroSetup

    /** No such camera: the default one, zoomed in so a small subject fills the frame from its closest focus. */
    data class Zoomed(val zoomRatio: Float) : MacroSetup
}

/** The back lens that focuses closest, if it beats the default camera [defaultCameraId] clearly; else a zoom. */
fun chooseMacro(defaultCameraId: String?, lenses: List<LensInfo>): MacroSetup {
    val defaultDiopters = lenses.firstOrNull { it.cameraId == defaultCameraId && it.physicalId == null }?.minFocusDiopters ?: 0f
    val best = lenses.filter { it.back && !it.hidden }.maxByOrNull { it.minFocusDiopters }
    return if (best != null && best.minFocusDiopters >= MIN_MACRO_DIOPTERS && best.minFocusDiopters >= defaultDiopters * CLOSER_THAN_DEFAULT) {
        MacroSetup.Lens(best.cameraId, best.physicalId)
    } else {
        MacroSetup.Zoomed(MACRO_ZOOM)
    }
}

/** The closest focus in whole centimetres, null for fixed focus. */
fun minFocusCm(diopters: Float): Int? = if (diopters > 0f) (100f / diopters).roundToInt() else null

/** Numeric ids up to [upTo] that are not in [listed]: where a hidden camera may still answer. */
fun hiddenCandidateIds(listed: List<String>, upTo: Int = HIGHEST_PROBED_ID): List<String> =
    (0..upTo).map(Int::toString).filter { it !in listed }

/**
 * Every camera the phone lists and the physical cameras inside each, read once from Camera2, then any camera
 * that answers by id though the list leaves it out (marked hidden).
 */
fun readLenses(context: Context): List<LensInfo> {
    val manager = context.getSystemService(CameraManager::class.java) ?: return emptyList()
    return try {
        val listedIds = manager.cameraIdList.toList()
        val listed = listedIds.flatMap { id ->
            val characteristics = manager.getCameraCharacteristics(id)
            val back = characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            val physical = characteristics.physicalCameraIds.mapNotNull { physicalId ->
                try {
                    lensInfo(id, physicalId, back, manager.getCameraCharacteristics(physicalId))
                } catch (e: IllegalArgumentException) {
                    Log.w(TAG, "Physical camera $physicalId of $id is not readable", e)
                    null
                }
            }
            listOf(lensInfo(id, null, back, characteristics)) + physical
        }
        val known = listed.map { it.physicalId ?: it.cameraId }.toSet()
        val hidden = hiddenCandidateIds(listedIds).filter { it !in known }.mapNotNull { id ->
            try {
                val characteristics = manager.getCameraCharacteristics(id)
                val back = characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
                lensInfo(id, null, back, characteristics).copy(hidden = true)
            } catch (_: IllegalArgumentException) {
                null
            } catch (_: CameraAccessException) {
                null
            }
        }
        listed + hidden
    } catch (e: CameraAccessException) {
        Log.w(TAG, "Listing the cameras failed", e)
        emptyList()
    }.also { lenses -> lenses.forEach { Log.i(TAG, "Lens $it") } }
}

private fun lensInfo(cameraId: String, physicalId: String?, back: Boolean, characteristics: CameraCharacteristics) = LensInfo(
    cameraId = cameraId,
    physicalId = physicalId,
    back = back,
    focalMm = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: 0f,
    minFocusDiopters = characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f,
)
