package cz.furmanek.motionphoto.ui.components

import androidx.annotation.StringRes
import cz.furmanek.motionphoto.R
import cz.furmanek.motionphoto.settings.CaptureMode
import cz.furmanek.motionphoto.settings.PhotoAspect
import cz.furmanek.motionphoto.settings.VideoQualitySetting

@StringRes
fun VideoQualitySetting.labelRes(): Int = when (this) {
    VideoQualitySetting.SD -> R.string.quality_sd
    VideoQualitySetting.HD -> R.string.quality_hd
    VideoQualitySetting.FHD -> R.string.quality_fhd
    VideoQualitySetting.UHD -> R.string.quality_uhd
}

@StringRes
fun PhotoAspect.labelRes(): Int = when (this) {
    PhotoAspect.RATIO_4_3 -> R.string.aspect_4_3
    PhotoAspect.RATIO_16_9 -> R.string.aspect_16_9
    PhotoAspect.RATIO_1_1 -> R.string.aspect_1_1
}

/** Width / height of the saved frame as shown in portrait. */
fun outputAspect(mode: CaptureMode, aspect: PhotoAspect): Float = when {
    mode == CaptureMode.VIDEO || mode == CaptureMode.SLOW_MOTION -> 9f / 16f
    mode == CaptureMode.PORTRAIT || mode == CaptureMode.NIGHT -> 3f / 4f
    aspect == PhotoAspect.RATIO_16_9 -> 9f / 16f
    aspect == PhotoAspect.RATIO_1_1 -> 1f
    else -> 3f / 4f
}

/** Next value in a cycle of options, wrapping around. */
fun <T> List<T>.next(current: T): T = this[(indexOf(current) + 1).mod(size)]
