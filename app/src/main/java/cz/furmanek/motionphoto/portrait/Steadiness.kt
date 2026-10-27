package cz.furmanek.motionphoto.portrait

/**
 * Whether the phone is held still, from the gyroscope's angular speed. The live blur hides while the phone
 * moves (the blurred layer lags behind the live preview and slides) and returns once it has been calm for
 * [settleMs]. Two thresholds keep hand tremor from toggling it.
 */
class Steadiness(
    private val moveRadPerS: Float = MOVE_RAD_PER_S,
    private val calmRadPerS: Float = CALM_RAD_PER_S,
    private val settleMs: Long = SETTLE_MS,
) {
    private var lastUnsettledMs = Long.MIN_VALUE
    var steady = true
        private set

    /** Feeds one reading; returns whether the phone counts as steady now. */
    fun update(angularSpeedRadPerS: Float, nowMs: Long): Boolean {
        if (angularSpeedRadPerS > moveRadPerS) steady = false
        if (angularSpeedRadPerS > calmRadPerS) lastUnsettledMs = nowMs
        if (!steady && nowMs - lastUnsettledMs >= settleMs) steady = true
        return steady
    }

    companion object {
        const val MOVE_RAD_PER_S = 0.5f
        const val CALM_RAD_PER_S = 0.25f
        const val SETTLE_MS = 300L
    }
}
