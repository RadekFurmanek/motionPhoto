package cz.furmanek.motionphoto.camera

import cz.furmanek.motionphoto.settings.CaptureMode

enum class UseCaseKind { PREVIEW, IMAGE, VIDEO }

/** One binding to try; [videoExtras] allows 60 fps and stabilization on the video stream. */
data class BindingAttempt(val kinds: Set<UseCaseKind>, val videoExtras: Boolean = false)

/**
 * Bindings to try, best first. Some cameras cannot stream preview, still and video together, or cannot add
 * 60 fps or stabilization to a quality; there the camera falls back instead of becoming unusable.
 * [videoExtras] says whether the settings ask for 60 fps or stabilization in video mode.
 */
fun bindingAttempts(mode: CaptureMode, livePhoto: Boolean, videoExtras: Boolean): List<BindingAttempt> {
    val video = setOf(UseCaseKind.PREVIEW, UseCaseKind.VIDEO)
    val photo = setOf(UseCaseKind.PREVIEW, UseCaseKind.IMAGE)
    return when {
        mode == CaptureMode.VIDEO && videoExtras ->
            listOf(BindingAttempt(video, videoExtras = true), BindingAttempt(video, videoExtras = false))
        mode == CaptureMode.VIDEO -> listOf(BindingAttempt(video, videoExtras = true))
        livePhoto -> listOf(BindingAttempt(photo + UseCaseKind.VIDEO), BindingAttempt(photo))
        else -> listOf(BindingAttempt(photo))
    }
}
