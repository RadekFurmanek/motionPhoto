package cz.furmanek.motionphoto.livephoto

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/** Writes a [Clip] cut from the [ClipBuffer] as an MP4, timed from its first video frame. */
object ClipWriter {

    fun write(clip: Clip, videoFormat: MediaFormat, audioFormat: MediaFormat?, rotationDegrees: Int, out: File) {
        val muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            val videoTrack = muxer.addTrack(videoFormat)
            val audioTrack = if (audioFormat != null && clip.audio.isNotEmpty()) muxer.addTrack(audioFormat) else -1
            muxer.setOrientationHint(rotationDegrees)
            muxer.start()
            val info = MediaCodec.BufferInfo()
            fun writeAll(track: Int, samples: List<EncodedSample>) {
                val times = relativeTimesUs(samples, clip.firstVideoUs)
                samples.forEachIndexed { i, sample ->
                    info.set(0, sample.data.size, times[i], if (sample.keyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                    muxer.writeSampleData(track, ByteBuffer.wrap(sample.data), info)
                }
            }
            writeAll(videoTrack, clip.video)
            if (audioTrack >= 0) writeAll(audioTrack, clip.audio)
            muxer.stop()
        } finally {
            muxer.release()
        }
    }

    /** Sample times from [originUs], never before it. */
    fun relativeTimesUs(samples: List<EncodedSample>, originUs: Long): LongArray =
        LongArray(samples.size) { (samples[it].timeUs - originUs).coerceAtLeast(0L) }
}
