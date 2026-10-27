package cz.furmanek.motionphoto.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class MacroTest {

    private fun lens(id: String, physical: String? = null, diopters: Float, back: Boolean = true) =
        LensInfo(cameraId = id, physicalId = physical, back = back, focalMm = 5f, minFocusDiopters = diopters)

    @Test
    fun closeFocusingPhysicalLensIsChosen() {
        val lenses = listOf(lens("0", diopters = 8f), lens("0", "2", diopters = 8f), lens("0", "3", diopters = 25f))
        assertEquals(MacroSetup.Lens("0", "3"), chooseMacro("0", lenses))
    }

    @Test
    fun closeFocusingStandaloneLensIsChosen() {
        val lenses = listOf(lens("0", diopters = 8f), lens("2", diopters = 20f))
        assertEquals(MacroSetup.Lens("2", null), chooseMacro("0", lenses))
    }

    @Test
    fun frontCameraIsNeverMacro() {
        val lenses = listOf(lens("0", diopters = 8f), lens("1", diopters = 30f, back = false))
        assertEquals(MacroSetup.Zoomed(MACRO_ZOOM), chooseMacro("0", lenses))
    }

    @Test
    fun lensNotMuchCloserThanDefaultFallsBackToZoom() {
        // 9 dioptres (11 cm) against 8: not worth leaving the better main camera.
        val lenses = listOf(lens("0", diopters = 8f), lens("0", "3", diopters = 9f))
        assertEquals(MacroSetup.Zoomed(MACRO_ZOOM), chooseMacro("0", lenses))
    }

    @Test
    fun fixedFocusLensIsNotMacro() {
        val lenses = listOf(lens("0", diopters = 0f), lens("0", "3", diopters = 0f))
        assertEquals(MacroSetup.Zoomed(MACRO_ZOOM), chooseMacro("0", lenses))
    }

    @Test
    fun withoutDefaultCameraAnyCloseLensCounts() {
        assertEquals(MacroSetup.Lens("2", null), chooseMacro(null, listOf(lens("2", diopters = 20f))))
    }

    @Test
    fun focusDistanceInCentimetres() {
        assertEquals(10, minFocusCm(10f))
        assertEquals(null, minFocusCm(0f))
    }

    @Test
    fun hiddenLensIsNeverMacro() {
        val lenses = listOf(lens("0", diopters = 10f), lens("2", diopters = 30f).copy(hidden = true))
        assertEquals(MacroSetup.Zoomed(MACRO_ZOOM), chooseMacro("0", lenses))
    }

    @Test
    fun probedIdsSkipListedOnes() {
        assertEquals(listOf("2", "3", "4"), hiddenCandidateIds(listOf("0", "1"), upTo = 4))
    }
}
