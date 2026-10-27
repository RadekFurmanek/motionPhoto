package cz.furmanek.motionphoto.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SettingsRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val scopes = mutableListOf<CoroutineScope>()

    @After
    fun tearDown() = scopes.forEach { it.cancel() }

    // DataStore allows one active instance per file, so each "app start" gets its own scope that is cancelled first.
    private fun dataStore(file: File): DataStore<Preferences> {
        scopes.forEach { it.cancel() }
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob()).also { scopes += it }
        return PreferenceDataStoreFactory.create(scope = scope) { file }
    }

    @Test
    fun `defaults when nothing was stored`() = runBlocking {
        val repository = SettingsRepository(dataStore(File(tmp.root, "settings.preferences_pb")))

        assertEquals(CameraSettings(), repository.settings.first())
    }

    @Test
    fun `updates survive a new repository over the same file`() = runBlocking {
        val file = File(tmp.root, "settings.preferences_pb")
        SettingsRepository(dataStore(file)).update {
            it.copy(
                flash = FlashSetting.AUTO,
                timer = TimerSetting.TEN,
                aspect = PhotoAspect.RATIO_1_1,
                mode = CaptureMode.VIDEO,
                livePhoto = false,
                lensFacing = LENS_FACING_FRONT,
                videoQuality = VideoQualitySetting.UHD,
                grid = true,
                slowMotionFps = 240,
                hdr = true,
                timelapseIntervalMs = 5000,
                portraitResolution = PortraitResolution.STANDARD,
                portraitBlur = 0.02f,
            )
        }

        val reloaded = SettingsRepository(dataStore(file)).settings.first()

        assertEquals(FlashSetting.AUTO, reloaded.flash)
        assertEquals(TimerSetting.TEN, reloaded.timer)
        assertEquals(PhotoAspect.RATIO_1_1, reloaded.aspect)
        assertEquals(CaptureMode.VIDEO, reloaded.mode)
        assertEquals(false, reloaded.livePhoto)
        assertEquals(LENS_FACING_FRONT, reloaded.lensFacing)
        assertEquals(VideoQualitySetting.UHD, reloaded.videoQuality)
        assertEquals(true, reloaded.grid)
        assertEquals(240, reloaded.slowMotionFps)
        assertEquals(true, reloaded.hdr)
        assertEquals(5000L, reloaded.timelapseIntervalMs)
        assertEquals(PortraitResolution.STANDARD, reloaded.portraitResolution)
        assertEquals(0.02f, reloaded.portraitBlur, 0f)
    }

    @Test
    fun `portrait blur defaults to the standard strength`() = assertEquals(0.012f, CameraSettings().portraitBlur, 0f)

    @Test
    fun `unknown stored enum value falls back to the default`() = runBlocking {
        val file = File(tmp.root, "settings.preferences_pb")
        dataStore(file).edit { it[stringPreferencesKey("flash")] = "BOGUS" }

        assertEquals(FlashSetting.OFF, SettingsRepository(dataStore(file)).settings.first().flash)
    }
}
