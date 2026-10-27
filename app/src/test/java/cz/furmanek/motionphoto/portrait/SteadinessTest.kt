package cz.furmanek.motionphoto.portrait

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SteadinessTest {

    @Test
    fun startsSteady() {
        assertTrue(Steadiness().steady)
    }

    @Test
    fun handTremorKeepsItSteady() {
        val s = Steadiness()
        for (t in 0L..1_000L step 20) assertTrue(s.update(if (t % 40 == 0L) 0.4f else 0.1f, t))
    }

    @Test
    fun movingHidesItUntilCalmForSettleTime() {
        val s = Steadiness()
        assertFalse(s.update(1.2f, 0))
        assertFalse(s.update(0.1f, 100))
        assertFalse(s.update(0.1f, 299))
        assertTrue(s.update(0.1f, 300))
    }

    @Test
    fun slowDriftAfterMovingDelaysReturn() {
        val s = Steadiness()
        s.update(1.0f, 0)
        assertFalse(s.update(0.3f, 250))
        assertFalse(s.update(0.1f, 500))
        assertTrue(s.update(0.1f, 550))
    }
}
