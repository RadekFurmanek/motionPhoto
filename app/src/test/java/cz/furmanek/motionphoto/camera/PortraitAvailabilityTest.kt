package cz.furmanek.motionphoto.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PortraitAvailabilityTest {

    @Test
    fun `a passing check is reported as it is`() {
        assertTrue(portraitAvailable({ error("unexpected") }) { true })
        assertFalse(portraitAvailable({ error("unexpected") }) { false })
    }

    @Test
    fun `a throwing check means unavailable and is reported`() {
        val failure = NullPointerException("ML Kit")
        val reported = mutableListOf<Throwable>()

        val available = portraitAvailable({ reported += it }) { throw failure }

        assertFalse(available)
        assertEquals(listOf<Throwable>(failure), reported)
    }

    @Test
    fun `a missing class means unavailable`() {
        val reported = mutableListOf<Throwable>()

        val available = portraitAvailable({ reported += it }) { throw NoClassDefFoundError("com/google/mlkit") }

        assertFalse(available)
        assertEquals(1, reported.size)
    }

    @Test(expected = OutOfMemoryError::class)
    fun `an out of memory error is not swallowed`() {
        portraitAvailable({}) { throw OutOfMemoryError() }
    }
}
