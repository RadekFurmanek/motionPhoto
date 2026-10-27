package cz.furmanek.motionphoto.camera

import androidx.camera.extensions.ExtensionMode
import cz.furmanek.motionphoto.settings.CameraSettings
import cz.furmanek.motionphoto.settings.CaptureMode
import cz.furmanek.motionphoto.settings.LENS_FACING_BACK

/**
 * Modes the bar offers for this camera, in bar order. Only modes the app implements and the camera supports.
 * Panorama needs the back camera, a rotation vector sensor and the lens optics to project the frames.
 */
fun availableModes(capabilities: CameraCapabilities, lensFacing: Int): List<CaptureMode> = CaptureMode.entries.filter { mode ->
    when (mode) {
        CaptureMode.VIDEO -> capabilities.videoQualities.isNotEmpty()
        CaptureMode.PHOTO -> true
        CaptureMode.SLOW_MOTION -> capabilities.slowMotionFps.isNotEmpty()
        CaptureMode.PORTRAIT -> ExtensionMode.BOKEH in capabilities.extensionModes || capabilities.customPortrait
        CaptureMode.NIGHT -> ExtensionMode.NIGHT in capabilities.extensionModes
        CaptureMode.TIMELAPSE -> true
        CaptureMode.PANORAMA -> lensFacing == LENS_FACING_BACK && capabilities.hasRotationVector && capabilities.hasOptics
    }
}

/** Portrait made by the app rather than the maker: the maker's bokeh wins wherever the camera has it. */
fun usesCustomPortrait(mode: CaptureMode, capabilities: CameraCapabilities): Boolean =
    mode == CaptureMode.PORTRAIT && ExtensionMode.BOKEH !in capabilities.extensionModes && capabilities.customPortrait

/** A stored mode this camera cannot do (other phone, other lens) falls back to Photo. */
fun fallbackMode(requested: CaptureMode, available: List<CaptureMode>): CaptureMode =
    if (requested in available) requested else CaptureMode.PHOTO

enum class ShutterAction { PHOTO, START_RECORDING, STOP_RECORDING, START_PANORAMA, FINISH_PANORAMA, NONE }

/**
 * What the shutter does. A running panorama goes first: it must stay stoppable whatever the settings say,
 * and nothing happens while one is being stitched.
 */
fun shutterAction(mode: CaptureMode, recording: Boolean, panorama: PanoramaUiState?): ShutterAction = when {
    panorama?.capturing == true -> ShutterAction.FINISH_PANORAMA
    panorama != null -> ShutterAction.NONE
    mode == CaptureMode.PANORAMA -> ShutterAction.START_PANORAMA
    !mode.isVideo() -> ShutterAction.PHOTO
    recording -> ShutterAction.STOP_RECORDING
    else -> ShutterAction.START_RECORDING
}

/** A panorama cannot carry on in another mode or on another camera. */
fun endsPanorama(old: CameraSettings, new: CameraSettings): Boolean =
    new.mode != CaptureMode.PANORAMA || new.lensFacing != old.lensFacing

/** Modes whose shutter starts and stops a recording. */
fun CaptureMode.isVideo(): Boolean =
    this == CaptureMode.VIDEO || this == CaptureMode.SLOW_MOTION || this == CaptureMode.TIMELAPSE

/**
 * High-speed sessions run at fixed rates; [ranges] (lower to upper) must contain exactly [fps]..[fps].
 * "Upper bound at least fps" would wrongly accept 120 on a camera that only offers 240.
 */
fun supportsFixedRate(ranges: Collection<Pair<Int, Int>>, fps: Int): Boolean = (fps to fps) in ranges

/**
 * Settings to fall back to when binding the current mode failed on this camera: maker modes and slow motion
 * fall back to plain Photo, HDR is switched off. Null when there is no simpler setup to try.
 */
fun afterBindFailure(settings: CameraSettings): CameraSettings? = when {
    settings.mode in setOf(CaptureMode.SLOW_MOTION, CaptureMode.PORTRAIT, CaptureMode.NIGHT) ->
        settings.copy(mode = CaptureMode.PHOTO)
    settings.mode == CaptureMode.PHOTO && settings.hdr -> settings.copy(hdr = false)
    else -> null
}
