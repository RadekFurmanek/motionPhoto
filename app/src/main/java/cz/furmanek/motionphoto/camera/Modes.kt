package cz.furmanek.motionphoto.camera

import androidx.camera.extensions.ExtensionMode
import cz.furmanek.motionphoto.settings.CaptureMode

/** Modes the bar offers for this camera, in bar order. Only modes the app implements and the camera supports. */
fun availableModes(capabilities: CameraCapabilities): List<CaptureMode> = CaptureMode.entries.filter { mode ->
    when (mode) {
        CaptureMode.VIDEO -> capabilities.videoQualities.isNotEmpty()
        CaptureMode.PHOTO -> true
        CaptureMode.SLOW_MOTION -> capabilities.slowMotionFps.isNotEmpty()
        CaptureMode.PORTRAIT -> ExtensionMode.BOKEH in capabilities.extensionModes
        CaptureMode.NIGHT -> ExtensionMode.NIGHT in capabilities.extensionModes
        CaptureMode.TIMELAPSE, CaptureMode.PANORAMA -> false
    }
}

/** A stored mode this camera cannot do (other phone, other lens) falls back to Photo. */
fun fallbackMode(requested: CaptureMode, available: List<CaptureMode>): CaptureMode =
    if (requested in available) requested else CaptureMode.PHOTO

/** Modes whose shutter starts and stops a recording. */
fun CaptureMode.isVideo(): Boolean =
    this == CaptureMode.VIDEO || this == CaptureMode.SLOW_MOTION || this == CaptureMode.TIMELAPSE
