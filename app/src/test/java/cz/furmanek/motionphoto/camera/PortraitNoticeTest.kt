package cz.furmanek.motionphoto.camera

import cz.furmanek.motionphoto.R
import cz.furmanek.motionphoto.portrait.PortraitOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PortraitNoticeTest {

    @Test
    fun `a portrait needs no notice`() {
        assertNull(portraitNotice(PortraitOutcome.PORTRAIT))
    }

    @Test
    fun `no subject says nothing was found`() {
        assertEquals(R.string.portrait_no_subject, portraitNotice(PortraitOutcome.NO_SUBJECT))
    }

    @Test
    fun `missing model says it is downloading`() {
        assertEquals(R.string.portrait_model_downloading, portraitNotice(PortraitOutcome.MODEL_MISSING))
    }

    @Test
    fun `out of memory and failure say the portrait failed`() {
        assertEquals(R.string.portrait_failed, portraitNotice(PortraitOutcome.OUT_OF_MEMORY))
        assertEquals(R.string.portrait_failed, portraitNotice(PortraitOutcome.FAILED))
    }
}
