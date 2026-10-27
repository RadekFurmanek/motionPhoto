package cz.furmanek.motionphoto.camera

import cz.furmanek.motionphoto.R
import cz.furmanek.motionphoto.portrait.PortraitOutcome

/** What to tell the user when a custom portrait was saved as an ordinary photo; null for a portrait. */
fun portraitNotice(outcome: PortraitOutcome): Int? = when (outcome) {
    PortraitOutcome.PORTRAIT -> null
    PortraitOutcome.NO_SUBJECT -> R.string.portrait_no_subject
    PortraitOutcome.MODEL_MISSING -> R.string.portrait_model_downloading
    PortraitOutcome.OUT_OF_MEMORY, PortraitOutcome.FAILED -> R.string.portrait_failed
}
