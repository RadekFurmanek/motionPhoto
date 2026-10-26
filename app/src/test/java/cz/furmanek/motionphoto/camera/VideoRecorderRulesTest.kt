package cz.furmanek.motionphoto.camera

import android.content.pm.ServiceInfo
import androidx.camera.video.VideoRecordEvent.Finalize
import org.junit.Assert.assertEquals
import org.junit.Test

class VideoRecorderRulesTest {

    @Test
    fun `clean finalize is saved`() {
        assertEquals(FinalizeOutcome.SAVED, classifyFinalize(Finalize.ERROR_NONE))
    }

    @Test
    fun `interrupted recordings keep what was recorded`() {
        listOf(
            Finalize.ERROR_INSUFFICIENT_STORAGE,
            Finalize.ERROR_SOURCE_INACTIVE,
            Finalize.ERROR_FILE_SIZE_LIMIT_REACHED,
            Finalize.ERROR_DURATION_LIMIT_REACHED,
        ).forEach { assertEquals(FinalizeOutcome.SAVED_INTERRUPTED, classifyFinalize(it)) }
    }

    @Test
    fun `broken recordings fail`() {
        listOf(Finalize.ERROR_ENCODING_FAILED, Finalize.ERROR_NO_VALID_DATA, Finalize.ERROR_UNKNOWN).forEach {
            assertEquals(FinalizeOutcome.FAILED, classifyFinalize(it))
        }
    }

    @Test
    fun `microphone service type only with audio`() {
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA, foregroundServiceTypes(withAudio = false, sdkInt = 34))
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            foregroundServiceTypes(withAudio = true, sdkInt = 34),
        )
    }

    @Test
    fun `no service types on Android 10 which does not know camera and microphone types`() {
        assertEquals(0, foregroundServiceTypes(withAudio = true, sdkInt = 29))
    }
}
