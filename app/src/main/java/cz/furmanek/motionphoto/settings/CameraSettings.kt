package cz.furmanek.motionphoto.settings

/** Same values as CameraSelector.LENS_FACING_*; kept here so settings stay free of CameraX. */
const val LENS_FACING_FRONT = 0
const val LENS_FACING_BACK = 1

/** Declaration order is the order of the mode bar. Stored by name, so reordering is safe. */
enum class CaptureMode { TIMELAPSE, SLOW_MOTION, VIDEO, PHOTO, PORTRAIT, NIGHT, PANORAMA }

enum class FlashSetting { OFF, AUTO, ON }

enum class TimerSetting(val seconds: Int) { OFF(0), THREE(3), TEN(10) }

enum class PhotoAspect { RATIO_4_3, RATIO_16_9, RATIO_1_1 }

/** How large a custom portrait is: the full sensor size, or decoded down to about 12 MP. */
enum class PortraitResolution { FULL, STANDARD }

/** Ordered from lowest to highest. */
enum class VideoQualitySetting { SD, HD, FHD, UHD }

data class CameraSettings(
    val mode: CaptureMode = CaptureMode.PHOTO,
    val lensFacing: Int = LENS_FACING_BACK,
    val livePhoto: Boolean = true,
    val flash: FlashSetting = FlashSetting.OFF,
    val timer: TimerSetting = TimerSetting.OFF,
    val aspect: PhotoAspect = PhotoAspect.RATIO_4_3,
    val videoQuality: VideoQualitySetting = VideoQualitySetting.FHD,
    val videoFps60: Boolean = false,
    val videoStabilization: Boolean = true,
    val torch: Boolean = false,
    val grid: Boolean = false,
    val saveLocation: Boolean = false,
    val shutterSound: Boolean = true,
    val volumeKeyShutter: Boolean = true,
    val mirrorFrontCamera: Boolean = false,
    val slowMotionFps: Int = 120,
    val hdr: Boolean = false,
    val timelapseIntervalMs: Long = 1000L,
    val portraitResolution: PortraitResolution = PortraitResolution.FULL,
)

/**
 * A flash would show in the clip and shift the still within it, so a flash shot is an ordinary photo.
 * HDR comes from the maker's Extensions, which cannot record the clip.
 */
fun CameraSettings.effectiveLivePhoto(): Boolean = livePhoto && flash == FlashSetting.OFF && !hdr

/** HDR and live photo exclude each other: turning one on turns the other off. */
fun CameraSettings.withHdr(on: Boolean): CameraSettings = copy(hdr = on, livePhoto = livePhoto && !on)

fun CameraSettings.withLivePhoto(on: Boolean): CameraSettings = copy(livePhoto = on, hdr = hdr && !on)

/** Some countries require the shutter sound; [mustPlay] comes from MediaActionSound.mustPlayShutterSound(). */
fun CameraSettings.shutterSoundRequired(mustPlay: Boolean): Boolean = shutterSound || mustPlay
