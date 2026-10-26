package cz.furmanek.motionphoto.camera

/**
 * Counts camera screens on display. A new screen starts before the one it covers stops,
 * so a single flag would end up "hidden" while the new screen is showing.
 */
class UiVisibility {
    private var visibleCount = 0

    val anyVisible: Boolean get() = visibleCount > 0

    fun shown() {
        visibleCount++
    }

    fun hidden() {
        visibleCount = (visibleCount - 1).coerceAtLeast(0)
    }
}
