package cz.furmanek.motionphoto.ui

import android.app.Application
import androidx.camera.core.MeteringPointFactory
import androidx.camera.core.Preview
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cz.furmanek.motionphoto.MotionPhotoApp
import cz.furmanek.motionphoto.camera.SessionEvent
import cz.furmanek.motionphoto.camera.SessionState
import cz.furmanek.motionphoto.panorama.Attitude
import cz.furmanek.motionphoto.settings.CameraSettings
import cz.furmanek.motionphoto.settings.CaptureMode
import cz.furmanek.motionphoto.settings.LENS_FACING_BACK
import cz.furmanek.motionphoto.settings.LENS_FACING_FRONT
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Translates UI actions to the app-wide [cz.furmanek.motionphoto.camera.CameraSession] and settings. */
class CameraViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as MotionPhotoApp
    private val session = app.cameraSession
    private val settingsRepository = app.settingsRepository

    val sessionState: StateFlow<SessionState> = session.state
    val settings: StateFlow<CameraSettings> =
        settingsRepository.settings.stateIn(viewModelScope, SharingStarted.Eagerly, CameraSettings())
    val events: Flow<SessionEvent> = session.events

    /** The live portrait's blurred background over the preview; null shows the preview alone. */
    val portraitFrame: Flow<ImageBitmap?> = session.portraitFrame
    val panoramaAttitude: StateFlow<Attitude?> = session.panoramaAttitude

    /** The custom portrait's background blur, a fraction of the longer side. */
    val portraitBlur: StateFlow<Float> = session.portraitBlur

    fun setPortraitBlur(fraction: Float) = session.setPortraitBlur(fraction)

    fun persistPortraitBlur() = session.persistPortraitBlur()

    fun attachPreview(
        surfaceProvider: Preview.SurfaceProvider,
        displayRotation: Int,
        portrait: Boolean,
        meteringFactory: MeteringPointFactory,
    ) = session.attachPreview(surfaceProvider, displayRotation, portrait, meteringFactory)

    fun detachPreview(surfaceProvider: Preview.SurfaceProvider) = session.detachPreview(surfaceProvider)

    fun onUiVisible(visible: Boolean) = session.onUiVisible(visible)

    val shutterSoundMandatory: Boolean = session.shutterSoundMandatory

    fun onShutter() = session.onShutter()

    fun setShutterKeysActive(active: Boolean) {
        session.shutterKeysActive = active
    }

    fun pauseRecording() = session.pauseRecording()

    fun resumeRecording() = session.resumeRecording()

    fun setMode(mode: CaptureMode) {
        if (isLocked()) return
        updateSettings { it.copy(mode = mode) }
    }

    fun switchCamera() {
        if (isLocked()) return
        session.selectBackCamera(null)
        updateSettings {
            it.copy(lensFacing = if (it.lensFacing == LENS_FACING_BACK) LENS_FACING_FRONT else LENS_FACING_BACK)
        }
    }

    fun selectBackCamera(cameraId: String?) = session.selectBackCamera(cameraId)

    fun setMacro(on: Boolean) = session.setMacro(on)

    fun focusAt(x: Float, y: Float) = session.focusAt(x, y)

    fun zoomBy(scaleFactor: Float) = session.zoomBy(scaleFactor)

    fun setZoom(ratio: Float) = session.animateZoomTo(ratio)

    fun setExposureIndex(index: Int) = session.setExposureIndex(index)

    fun setTargetRotation(rotation: Int) = session.setTargetRotation(rotation)

    fun updateSettings(transform: (CameraSettings) -> CameraSettings) {
        viewModelScope.launch { settingsRepository.update(transform) }
    }

    private fun isLocked(): Boolean = sessionState.value.controlsLocked
}
