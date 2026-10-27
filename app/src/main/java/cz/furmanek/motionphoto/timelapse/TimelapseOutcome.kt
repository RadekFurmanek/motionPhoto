package cz.furmanek.motionphoto.timelapse

/** How a stopped time-lapse ends up: published, published after an encoder failure, nothing to save, or broken. */
enum class TimelapseOutcome { SAVED, SAVED_INTERRUPTED, EMPTY, FAILED }

/**
 * [playable]: the muxer finalised a file with at least one frame. [frames]: frames handed to the codec.
 * Only a run that never got a frame and saw no encoder error counts as empty; everything else unplayable failed.
 */
fun classifyTimelapse(playable: Boolean, frames: Int, encoderFailed: Boolean): TimelapseOutcome = when {
    playable && encoderFailed -> TimelapseOutcome.SAVED_INTERRUPTED
    playable -> TimelapseOutcome.SAVED
    frames == 0 && !encoderFailed -> TimelapseOutcome.EMPTY
    else -> TimelapseOutcome.FAILED
}

/**
 * Tells a codec hiccup from a broken encoder: one bad frame must not end hours of recording,
 * but [limit] failures in a row will not recover and would only waste the rest of the run.
 */
class FrameFailures(private val limit: Int = CONSECUTIVE_FAILURE_LIMIT) {
    private var consecutive = 0

    fun onSuccess() {
        consecutive = 0
    }

    /** Returns true when the recording should stop. */
    fun onFailure(): Boolean = ++consecutive >= limit

    private companion object {
        const val CONSECUTIVE_FAILURE_LIMIT = 3
    }
}
