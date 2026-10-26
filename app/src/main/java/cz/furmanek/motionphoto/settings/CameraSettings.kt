package cz.furmanek.motionphoto.settings

/** Same values as CameraSelector.LENS_FACING_*; kept here so settings stay free of CameraX. */
const val LENS_FACING_FRONT = 0
const val LENS_FACING_BACK = 1

enum class CaptureMode { PHOTO, VIDEO }

enum class FlashSetting { OFF, AUTO, ON }

enum class TimerSetting(val seconds: Int) { OFF(0), THREE(3), TEN(10) }

enum class PhotoAspect { RATIO_4_3, RATIO_16_9, RATIO_1_1 }

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
)

/** A flash would show in the clip and shift the still within it, so a flash shot is an ordinary photo. */
fun CameraSettings.effectiveLivePhoto(): Boolean = livePhoto && flash == FlashSetting.OFF

/** Some countries require the shutter sound; [mustPlay] comes from MediaActionSound.mustPlayShutterSound(). */
fun CameraSettings.shutterSoundRequired(mustPlay: Boolean): Boolean = shutterSound || mustPlay
