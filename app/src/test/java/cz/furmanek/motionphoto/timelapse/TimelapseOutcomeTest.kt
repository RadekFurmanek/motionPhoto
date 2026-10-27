package cz.furmanek.motionphoto.timelapse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelapseOutcomeTest {

    @Test
    fun `finalised file is saved`() {
        assertEquals(TimelapseOutcome.SAVED, classifyTimelapse(playable = true, frames = 10, encoderFailed = false))
    }

    @Test
    fun `encoder failure keeps what was written`() {
        assertEquals(
            TimelapseOutcome.SAVED_INTERRUPTED,
            classifyTimelapse(playable = true, frames = 10, encoderFailed = true),
        )
    }

    @Test
    fun `stop before the first frame is empty`() {
        assertEquals(TimelapseOutcome.EMPTY, classifyTimelapse(playable = false, frames = 0, encoderFailed = false))
    }

    @Test
    fun `encoder that never worked is a failure, not an empty run`() {
        assertEquals(TimelapseOutcome.FAILED, classifyTimelapse(playable = false, frames = 0, encoderFailed = true))
    }

    @Test
    fun `frames the muxer could not finalise are a failure`() {
        assertEquals(TimelapseOutcome.FAILED, classifyTimelapse(playable = false, frames = 5, encoderFailed = false))
    }

    @Test
    fun `single failures are tolerated`() {
        val failures = FrameFailures(limit = 3)
        repeat(5) {
            assertFalse(failures.onFailure())
            assertFalse(failures.onFailure())
            failures.onSuccess()
        }
    }

    @Test
    fun `failures in a row stop the recording`() {
        val failures = FrameFailures(limit = 3)
        assertFalse(failures.onFailure())
        assertFalse(failures.onFailure())
        assertTrue(failures.onFailure())
    }
}
