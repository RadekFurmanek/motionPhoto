package cz.furmanek.motionphoto.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Persists [CameraSettings]. Enums are stored by name; an unknown name reads as the default. */
class SettingsRepository(private val dataStore: DataStore<Preferences>) {

    val settings: Flow<CameraSettings> = dataStore.data.map(::read).distinctUntilChanged()

    suspend fun update(transform: (CameraSettings) -> CameraSettings) {
        dataStore.edit { prefs -> write(prefs, transform(read(prefs))) }
    }

    private fun read(prefs: Preferences): CameraSettings {
        val defaults = CameraSettings()
        return CameraSettings(
            mode = prefs.enum(Keys.MODE, defaults.mode),
            lensFacing = prefs[Keys.LENS_FACING] ?: defaults.lensFacing,
            livePhoto = prefs[Keys.LIVE_PHOTO] ?: defaults.livePhoto,
            flash = prefs.enum(Keys.FLASH, defaults.flash),
            timer = prefs.enum(Keys.TIMER, defaults.timer),
            aspect = prefs.enum(Keys.ASPECT, defaults.aspect),
            videoQuality = prefs.enum(Keys.VIDEO_QUALITY, defaults.videoQuality),
            videoFps60 = prefs[Keys.VIDEO_FPS_60] ?: defaults.videoFps60,
            videoStabilization = prefs[Keys.VIDEO_STABILIZATION] ?: defaults.videoStabilization,
            torch = prefs[Keys.TORCH] ?: defaults.torch,
            grid = prefs[Keys.GRID] ?: defaults.grid,
            saveLocation = prefs[Keys.SAVE_LOCATION] ?: defaults.saveLocation,
            shutterSound = prefs[Keys.SHUTTER_SOUND] ?: defaults.shutterSound,
            volumeKeyShutter = prefs[Keys.VOLUME_KEY_SHUTTER] ?: defaults.volumeKeyShutter,
            mirrorFrontCamera = prefs[Keys.MIRROR_FRONT] ?: defaults.mirrorFrontCamera,
            slowMotionFps = prefs[Keys.SLOW_MOTION_FPS] ?: defaults.slowMotionFps,
            hdr = prefs[Keys.HDR] ?: defaults.hdr,
            timelapseIntervalMs = prefs[Keys.TIMELAPSE_INTERVAL] ?: defaults.timelapseIntervalMs,
            portraitResolution = prefs.enum(Keys.PORTRAIT_RESOLUTION, defaults.portraitResolution),
        )
    }

    private fun write(prefs: MutablePreferences, settings: CameraSettings) {
        prefs[Keys.MODE] = settings.mode.name
        prefs[Keys.LENS_FACING] = settings.lensFacing
        prefs[Keys.LIVE_PHOTO] = settings.livePhoto
        prefs[Keys.FLASH] = settings.flash.name
        prefs[Keys.TIMER] = settings.timer.name
        prefs[Keys.ASPECT] = settings.aspect.name
        prefs[Keys.VIDEO_QUALITY] = settings.videoQuality.name
        prefs[Keys.VIDEO_FPS_60] = settings.videoFps60
        prefs[Keys.VIDEO_STABILIZATION] = settings.videoStabilization
        prefs[Keys.TORCH] = settings.torch
        prefs[Keys.GRID] = settings.grid
        prefs[Keys.SAVE_LOCATION] = settings.saveLocation
        prefs[Keys.SHUTTER_SOUND] = settings.shutterSound
        prefs[Keys.VOLUME_KEY_SHUTTER] = settings.volumeKeyShutter
        prefs[Keys.MIRROR_FRONT] = settings.mirrorFrontCamera
        prefs[Keys.SLOW_MOTION_FPS] = settings.slowMotionFps
        prefs[Keys.HDR] = settings.hdr
        prefs[Keys.TIMELAPSE_INTERVAL] = settings.timelapseIntervalMs
        prefs[Keys.PORTRAIT_RESOLUTION] = settings.portraitResolution.name
    }

    private inline fun <reified E : Enum<E>> Preferences.enum(key: Preferences.Key<String>, default: E): E =
        this[key]?.let { name -> enumValues<E>().firstOrNull { it.name == name } } ?: default

    private object Keys {
        val MODE = stringPreferencesKey("mode")
        val LENS_FACING = intPreferencesKey("lens_facing")
        val LIVE_PHOTO = booleanPreferencesKey("live_photo")
        val FLASH = stringPreferencesKey("flash")
        val TIMER = stringPreferencesKey("timer")
        val ASPECT = stringPreferencesKey("aspect")
        val VIDEO_QUALITY = stringPreferencesKey("video_quality")
        val VIDEO_FPS_60 = booleanPreferencesKey("video_fps_60")
        val VIDEO_STABILIZATION = booleanPreferencesKey("video_stabilization")
        val TORCH = booleanPreferencesKey("torch")
        val GRID = booleanPreferencesKey("grid")
        val SAVE_LOCATION = booleanPreferencesKey("save_location")
        val SHUTTER_SOUND = booleanPreferencesKey("shutter_sound")
        val VOLUME_KEY_SHUTTER = booleanPreferencesKey("volume_key_shutter")
        val MIRROR_FRONT = booleanPreferencesKey("mirror_front")
        val SLOW_MOTION_FPS = intPreferencesKey("slow_motion_fps")
        val HDR = booleanPreferencesKey("hdr")
        val TIMELAPSE_INTERVAL = longPreferencesKey("timelapse_interval_ms")
        val PORTRAIT_RESOLUTION = stringPreferencesKey("portrait_resolution")
    }
}
