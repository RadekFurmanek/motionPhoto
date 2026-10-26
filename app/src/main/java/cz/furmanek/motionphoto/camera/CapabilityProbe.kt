package cz.furmanek.motionphoto.camera

import android.util.Log
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraInfoUnavailableException
import androidx.camera.core.CameraSelector
import androidx.camera.core.DynamicRange
import androidx.camera.core.Preview
import androidx.camera.core.SessionConfig
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.HighSpeedVideoSessionConfig
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import cz.furmanek.motionphoto.settings.VideoQualitySetting

/** Reads [CameraCapabilities] for one camera from CameraX. */
object CapabilityProbe {

    private const val FPS_60 = 60
    private const val TAG = "CapabilityProbe"

    private val SLOW_MOTION_RATES = listOf(120, 240)
    private val EXTENSION_MODES = listOf(ExtensionMode.BOKEH, ExtensionMode.NIGHT, ExtensionMode.HDR)

    /** [extensions] is null when the maker offers no Extensions or they failed to load. */
    fun probe(
        provider: ProcessCameraProvider,
        cameraInfo: CameraInfo,
        selector: CameraSelector,
        extensions: ExtensionsManager?,
    ): CameraCapabilities {
        val videoCapabilities = Recorder.getVideoCapabilities(cameraInfo)
        val qualities = videoCapabilities.getSupportedQualities(DynamicRange.SDR)
            .mapNotNull(::toSetting)
            .sorted()
        val zoom = cameraInfo.zoomState.value
        val exposure = cameraInfo.exposureState
        return CameraCapabilities(
            hasFlash = cameraInfo.hasFlashUnit(),
            minZoom = zoom?.minZoomRatio ?: 1f,
            maxZoom = zoom?.maxZoomRatio ?: 1f,
            exposureRange = if (exposure.isExposureCompensationSupported) {
                exposure.exposureCompensationRange.lower..exposure.exposureCompensationRange.upper
            } else {
                0..0
            },
            exposureStep = exposure.exposureCompensationStep.toFloat(),
            videoQualities = qualities,
            fps60Qualities = qualities.filterTo(mutableSetOf()) { supports60Fps(cameraInfo, it) },
            videoStabilization = videoCapabilities.isStabilizationSupported,
            backLenses = backLenses(provider),
            hasFrontCamera = provider.hasCameraSafely(CameraSelector.DEFAULT_FRONT_CAMERA),
            hasBackCamera = provider.hasCameraSafely(CameraSelector.DEFAULT_BACK_CAMERA),
            slowMotionFps = optional("slow motion") {
                SLOW_MOTION_RATES.filterTo(mutableSetOf()) { slowMotionQuality(cameraInfo, it) != null }
            },
            extensionModes = optional("extensions") {
                EXTENSION_MODES.filterTo(mutableSetOf()) { extensions?.isExtensionAvailable(selector, it) == true }
            },
        )
    }

    /**
     * The highest high-speed quality that records at [fps], or null. Phones often reach 240 fps only
     * at a lower resolution than 120 fps.
     */
    fun slowMotionQuality(cameraInfo: CameraInfo, fps: Int): Quality? {
        val qualities = try {
            Recorder.getHighSpeedVideoCapabilities(cameraInfo)?.getSupportedQualities(DynamicRange.SDR).orEmpty()
        } catch (_: IllegalArgumentException) {
            emptyList()
        }
        // CameraX lists qualities from the largest to the smallest.
        return qualities.firstOrNull { quality ->
            val video = VideoCapture.withOutput(Recorder.Builder().setQualitySelector(QualitySelector.from(quality)).build())
            try {
                val ranges = cameraInfo.getSupportedFrameRateRanges(HighSpeedVideoSessionConfig(video, Preview.Builder().build()))
                supportsFixedRate(ranges.map { it.lower to it.upper }, fps)
            } catch (_: IllegalArgumentException) {
                false
            } catch (_: IllegalStateException) {
                false
            }
        }
    }

    /** Optional features must never break plain Photo and Video: any failure while probing them means "none". */
    private inline fun optional(feature: String, probe: () -> Set<Int>): Set<Int> =
        try {
            probe()
        } catch (e: RuntimeException) {
            Log.w(TAG, "Probing $feature failed", e)
            emptySet()
        }

    fun toQuality(setting: VideoQualitySetting): Quality = when (setting) {
        VideoQualitySetting.SD -> Quality.SD
        VideoQualitySetting.HD -> Quality.HD
        VideoQualitySetting.FHD -> Quality.FHD
        VideoQualitySetting.UHD -> Quality.UHD
    }

    private fun toSetting(quality: Quality): VideoQualitySetting? = when (quality) {
        Quality.SD -> VideoQualitySetting.SD
        Quality.HD -> VideoQualitySetting.HD
        Quality.FHD -> VideoQualitySetting.FHD
        Quality.UHD -> VideoQualitySetting.UHD
        else -> null
    }

    /** Asks for the frame rates of the exact Preview + VideoCapture session the Video mode would bind. */
    private fun supports60Fps(cameraInfo: CameraInfo, quality: VideoQualitySetting): Boolean {
        val videoCapture = VideoCapture.withOutput(
            Recorder.Builder().setQualitySelector(QualitySelector.from(toQuality(quality))).build(),
        )
        val session = SessionConfig(listOf(Preview.Builder().build(), videoCapture))
        return try {
            cameraInfo.getSupportedFrameRateRanges(session).any { it.upper >= FPS_60 }
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun backLenses(provider: ProcessCameraProvider): List<LensOption> {
        val back = provider.availableCameraInfos.filter { it.lensFacing == CameraSelector.LENS_FACING_BACK }
        if (back.size < 2) return emptyList()
        return back
            .map { LensOption(Camera2CameraInfo.from(it).cameraId, it.lensFacing, it.intrinsicZoomRatio) }
            .sortedBy { it.intrinsicZoomRatio }
    }

    private fun ProcessCameraProvider.hasCameraSafely(selector: CameraSelector): Boolean =
        try {
            hasCamera(selector)
        } catch (_: CameraInfoUnavailableException) {
            false
        }
}
