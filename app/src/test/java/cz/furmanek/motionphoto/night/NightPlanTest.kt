package cz.furmanek.motionphoto.night

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NightPlanTest {

    private val sensor = NightSensor(manual = true, exposureNs = 10_000L..1_000_000_000L, iso = 100..6400)

    @Test
    fun darkSceneUsesQuarterSecondFramesWithinTwoSeconds() {
        // The preview runs at 1/15 s, ISO 3200: dark.
        val plan = planNight(66_000_000L, 3200, sensor, steady = true)
        assertEquals(MAX_FRAME_STEADY_NS, plan.exposureNs)
        assertEquals(8, plan.frames)
        assertTrue(plan.frames * plan.exposureNs!! <= BURST_BUDGET_NS)
        // 66 ms × 3200 × 2 over 250 ms: ISO 1690.
        assertEquals(1690.0, plan.iso!!.toDouble(), 2.0)
        assertEquals(1f, plan.digitalGain, 0.01f)
    }

    @Test
    fun movingPhoneShortensTheFrames() {
        val plan = planNight(66_000_000L, 3200, sensor, steady = false)
        assertEquals(MAX_FRAME_MOVING_NS, plan.exposureNs)
        assertEquals(MAX_FRAMES, plan.frames)
    }

    @Test
    fun brighterSceneKeepsIsoLowAndShortensFrames() {
        // 1/30 s at ISO 100: target 2/30 s × ISO 100, already reachable at the lowest ISO.
        val plan = planNight(33_000_000L, 100, sensor, steady = true)
        assertEquals(66_000_000.0, plan.exposureNs!!.toDouble(), 1_000_000.0)
        assertEquals(100, plan.iso)
        assertEquals(MAX_FRAMES, plan.frames)
    }

    @Test
    fun isoCeilingTurnsIntoDigitalGain() {
        // 1/4 s at ISO 6400 already: twice that needs ISO 12800, the phone stops at 6400.
        val plan = planNight(250_000_000L, 6400, sensor, steady = true)
        assertEquals(6400, plan.iso)
        assertEquals(2f, plan.digitalGain, 0.01f)
    }

    @Test
    fun phoneLimitsTheFrameLength() {
        val short = sensor.copy(exposureNs = 10_000L..100_000_000L)
        val plan = planNight(66_000_000L, 3200, short, steady = true)
        assertEquals(100_000_000L, plan.exposureNs)
        assertEquals(MAX_FRAMES, plan.frames)
    }

    @Test
    fun withoutManualExposureTheBurstRunsOnAutoExposure() {
        val plan = planNight(66_000_000L, 3200, NightSensor(manual = false, exposureNs = null, iso = null), steady = true)
        assertNull(plan.exposureNs)
        assertNull(plan.iso)
        assertEquals(AUTO_FRAMES, plan.frames)
    }

    @Test
    fun burstLengthIsFramesTimesExposure() {
        val plan = planNight(66_000_000L, 3200, sensor, steady = true)
        assertEquals(2_000L, plan.durationMs)
    }
}
