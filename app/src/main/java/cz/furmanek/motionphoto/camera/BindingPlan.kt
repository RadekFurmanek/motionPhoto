package cz.furmanek.motionphoto.camera

import cz.furmanek.motionphoto.settings.CaptureMode

enum class UseCaseKind { PREVIEW, IMAGE, VIDEO, ANALYSIS }

/** One binding to try; [videoExtras] allows 60 fps and stabilization on the video stream. */
data class BindingAttempt(val kinds: Set<UseCaseKind>, val videoExtras: Boolean = false)

/**
 * Bindings to try, best first. Some cameras cannot stream preview, still and video together, or cannot add
 * 60 fps or stabilization to a quality; there the camera falls back instead of becoming unusable.
 * [videoExtras] says whether the settings ask for 60 fps or stabilization in video mode. [customPortrait] says
 * the portrait is made by the app: its live preview needs frame analysis, but the photo works without it.
 */
fun bindingAttempts(
    mode: CaptureMode,
    livePhoto: Boolean,
    videoExtras: Boolean,
    customPortrait: Boolean = false,
): List<BindingAttempt> {
    val video = setOf(UseCaseKind.PREVIEW, UseCaseKind.VIDEO)
    val photo = setOf(UseCaseKind.PREVIEW, UseCaseKind.IMAGE)
    return when (mode) {
        CaptureMode.VIDEO -> if (videoExtras) {
            listOf(BindingAttempt(video, videoExtras = true), BindingAttempt(video, videoExtras = false))
        } else {
            listOf(BindingAttempt(video, videoExtras = true))
        }
        // A high-speed session has a fixed shape; there is nothing to fall back to.
        CaptureMode.SLOW_MOTION -> listOf(BindingAttempt(video))
        CaptureMode.PORTRAIT -> if (customPortrait) {
            listOf(BindingAttempt(photo + UseCaseKind.ANALYSIS), BindingAttempt(photo))
        } else {
            listOf(BindingAttempt(photo))
        }
        CaptureMode.NIGHT -> listOf(BindingAttempt(photo))
        CaptureMode.PHOTO -> if (livePhoto) {
            listOf(BindingAttempt(photo + UseCaseKind.VIDEO), BindingAttempt(photo))
        } else {
            listOf(BindingAttempt(photo))
        }
        CaptureMode.TIMELAPSE, CaptureMode.PANORAMA -> listOf(BindingAttempt(setOf(UseCaseKind.PREVIEW, UseCaseKind.ANALYSIS)))
    }
}
