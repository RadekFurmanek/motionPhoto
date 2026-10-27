package cz.furmanek.motionphoto.camera

import cz.furmanek.motionphoto.settings.VideoQualitySetting

/** A back camera the phone exposes on its own (e.g. ultra-wide), with its zoom relative to the default camera. */
data class LensOption(val cameraId: String, val lensFacing: Int, val intrinsicZoomRatio: Float)

/** What one camera can do; the UI offers only what is listed here. */
data class CameraCapabilities(
    val hasFlash: Boolean,
    val minZoom: Float,
    val maxZoom: Float,
    val exposureRange: IntRange,
    val exposureStep: Float,
    /** Ordered from lowest to highest. */
    val videoQualities: List<VideoQualitySetting>,
    val fps60Qualities: Set<VideoQualitySetting>,
    val videoStabilization: Boolean,
    /** Separately exposed back cameras; empty when there is only one. */
    val backLenses: List<LensOption>,
    val hasFrontCamera: Boolean,
    val hasBackCamera: Boolean,
    /** High-speed rates usable for slow motion, out of 120 and 240. */
    val slowMotionFps: Set<Int> = emptySet(),
    /** Maker extensions this camera offers (ExtensionMode.BOKEH, NIGHT, HDR). */
    val extensionModes: Set<Int> = emptySet(),
)

private val ZOOM_STEPS = listOf(1f, 2f, 5f, 10f)
private const val ULTRA_WIDE_PRESET = 0.5f
private const val ULTRA_WIDE_THRESHOLD = 0.6f

/** 0.5× when the camera zooms out that far, then 1×, 2×, 5×, 10× within range; 1× always. */
fun zoomPresets(minZoom: Float, maxZoom: Float): List<Float> {
    val ultraWide = if (minZoom <= ULTRA_WIDE_THRESHOLD) listOf(ULTRA_WIDE_PRESET) else emptyList()
    return ultraWide + ZOOM_STEPS.filter { it == 1f || it in minZoom..maxZoom }
}

/** The requested quality, else the nearest lower one, else the lowest available; null when video is impossible. */
fun resolveVideoQuality(requested: VideoQualitySetting, available: List<VideoQualitySetting>): VideoQualitySetting? =
    available.filter { it <= requested }.maxOrNull() ?: available.minOrNull()
