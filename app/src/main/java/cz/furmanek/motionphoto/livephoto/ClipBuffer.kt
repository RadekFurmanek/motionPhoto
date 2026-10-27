package cz.furmanek.motionphoto.livephoto

enum class Track { VIDEO, AUDIO }

/** One encoded video frame or audio chunk; [timeUs] is on the elapsed-realtime clock, in microseconds. */
class EncodedSample(val track: Track, val timeUs: Long, val keyFrame: Boolean, val data: ByteArray)

/** A cut of the buffer, ready to mux: video starts at a key frame, audio covers the same span. */
class Clip(val video: List<EncodedSample>, val audio: List<EncodedSample>, val firstVideoUs: Long) {
    val lastVideoUs: Long get() = video.last().timeUs
}

/** Where the still sits in [clip], in microseconds from its first frame. */
fun stillPositionUs(stillUs: Long, clip: Clip): Long =
    (stillUs - clip.firstVideoUs).coerceIn(0L, clip.lastVideoUs - clip.firstVideoUs)

/**
 * The last few seconds of encoded live photo video and audio, so a clip can start before the shutter press.
 * Holds at least [keepUs] behind the newest frame, always from a key frame. The encoder threads add, the
 * capture reads; all access is synchronized.
 */
class ClipBuffer(private val keepUs: Long = KEEP_US) {
    private val video = ArrayDeque<EncodedSample>()
    private val audio = ArrayDeque<EncodedSample>()

    val newestVideoUs: Long?
        @Synchronized get() = video.lastOrNull()?.timeUs

    @Synchronized
    fun add(sample: EncodedSample) {
        when (sample.track) {
            Track.VIDEO -> {
                video.addLast(sample)
                dropOld()
            }
            Track.AUDIO -> audio.addLast(sample)
        }
    }

    /** Video from the key frame at or before [pressUs] − [preUs] (or the first one held) to [pressUs] + [postUs]. */
    @Synchronized
    fun clip(pressUs: Long, preUs: Long, postUs: Long): Clip? {
        val startTarget = pressUs - preUs
        val start = video.lastOrNull { it.keyFrame && it.timeUs <= startTarget } ?: video.firstOrNull { it.keyFrame } ?: return null
        val end = pressUs + postUs
        val frames = video.filter { it.timeUs >= start.timeUs && it.timeUs <= end }
        val lastUs = frames.last().timeUs
        return Clip(frames, audio.filter { it.timeUs in start.timeUs..lastUs }, start.timeUs)
    }

    @Synchronized
    fun clear() {
        video.clear()
        audio.clear()
    }

    private fun dropOld() {
        val cutoff = video.last().timeUs - keepUs
        // Keep from the newest key frame at or before the cutoff, so a clip can always start cleanly.
        val firstKept = video.lastOrNull { it.keyFrame && it.timeUs <= cutoff } ?: return
        while (video.first() !== firstKept) video.removeFirst()
        while (audio.isNotEmpty() && audio.first().timeUs < firstKept.timeUs) audio.removeFirst()
    }

    companion object {
        /** At the cut the newest frame is press + 1.5 s, and the clip may reach back to press − 2.0 s. */
        const val KEEP_US = 4_000_000L
    }
}
