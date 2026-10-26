package cz.furmanek.motionphoto.camera

import cz.furmanek.motionphoto.settings.CaptureMode

enum class UseCaseKind { PREVIEW, IMAGE, VIDEO }

/**
 * Use-case sets to try binding, best first. Some cameras cannot stream preview, still and video together;
 * there a live photo falls back to a plain photo rather than leaving the camera unusable.
 */
fun bindingAttempts(mode: CaptureMode, livePhoto: Boolean): List<Set<UseCaseKind>> = when {
    mode == CaptureMode.VIDEO -> listOf(setOf(UseCaseKind.PREVIEW, UseCaseKind.VIDEO))
    livePhoto -> listOf(
        setOf(UseCaseKind.PREVIEW, UseCaseKind.IMAGE, UseCaseKind.VIDEO),
        setOf(UseCaseKind.PREVIEW, UseCaseKind.IMAGE),
    )
    else -> listOf(setOf(UseCaseKind.PREVIEW, UseCaseKind.IMAGE))
}
