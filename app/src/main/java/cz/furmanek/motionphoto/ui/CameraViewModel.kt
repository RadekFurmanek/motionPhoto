package cz.furmanek.motionphoto.ui

import android.app.Application
import androidx.camera.core.MeteringPointFactory
import androidx.camera.core.Preview
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cz.furmanek.motionphoto.MotionPhotoApp
import cz.furmanek.motionphoto.camera.SessionEvent
import cz.furmanek.motionphoto.camera.SessionState
import cz.furmanek.motionphoto.settings.CameraSettings
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

    fun attachPreview(
        surfaceProvider: Preview.SurfaceProvider,
        displayRotation: Int,
        portrait: Boolean,
        meteringFactory: MeteringPointFactory,
    ) = session.attachPreview(surfaceProvider, displayRotation, portrait, meteringFactory)

    fun detachPreview(surfaceProvider: Preview.SurfaceProvider) = session.detachPreview(surfaceProvider)

    fun onUiVisible(visible: Boolean) = session.onUiVisible(visible)

    fun capture() = session.takePhoto()

    fun switchCamera() {
        if (isLocked()) return
        session.selectBackCamera(null)
        updateSettings {
            it.copy(lensFacing = if (it.lensFacing == LENS_FACING_BACK) LENS_FACING_FRONT else LENS_FACING_BACK)
        }
    }

    fun selectBackCamera(cameraId: String?) = session.selectBackCamera(cameraId)

    fun focusAt(x: Float, y: Float) = session.focusAt(x, y)

    fun zoomBy(scaleFactor: Float) = session.zoomBy(scaleFactor)

    fun setZoom(ratio: Float) = session.setZoom(ratio)

    fun setExposureIndex(index: Int) = session.setExposureIndex(index)

    fun setTargetRotation(rotation: Int) = session.setTargetRotation(rotation)

    fun updateSettings(transform: (CameraSettings) -> CameraSettings) {
        viewModelScope.launch { settingsRepository.update(transform) }
    }

    private fun isLocked(): Boolean = sessionState.value.busy || sessionState.value.recording != null
}
