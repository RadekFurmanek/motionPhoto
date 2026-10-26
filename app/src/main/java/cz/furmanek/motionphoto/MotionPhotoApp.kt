package cz.furmanek.motionphoto

import android.app.Application
import androidx.datastore.preferences.preferencesDataStore
import cz.furmanek.motionphoto.camera.CameraSession
import cz.furmanek.motionphoto.media.MediaStoreWriter
import cz.furmanek.motionphoto.settings.SettingsRepository

private val Application.settingsDataStore by preferencesDataStore(name = "camera_settings")

class MotionPhotoApp : Application() {
    val settingsRepository by lazy { SettingsRepository(settingsDataStore) }
    val mediaStoreWriter by lazy { MediaStoreWriter(contentResolver, packageName) }
    val cameraSession by lazy { CameraSession(this, settingsRepository, mediaStoreWriter) }
}
