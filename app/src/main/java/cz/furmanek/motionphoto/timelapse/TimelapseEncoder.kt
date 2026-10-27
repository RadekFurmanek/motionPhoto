package cz.furmanek.motionphoto.timelapse

import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.Closeable
import java.io.FileDescriptor

/**
 * Encodes I420 frames of [width]×[height] (multiples of 16, see [alignedEncoderSize]) into an H.264 MP4
 * at [fps], frame n at n/fps seconds whatever the capture interval was. Not thread-safe: one worker thread.
 * [output] must be seekable and readable as well as writable; the caller owns and closes it after [finish].
 */
class TimelapseEncoder(
    output: FileDescriptor,
    private val width: Int,
    private val height: Int,
    rotationDegrees: Int,
    private val fps: Int = TIMELAPSE_FPS,
) : Closeable {

    private val codec = MediaCodec.createEncoderByType(MIME_TYPE)
    private val muxer = try {
        MediaMuxer(output, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    } catch (e: Exception) {
        codec.release()
        throw e
    }
    private val bufferInfo = MediaCodec.BufferInfo()
    private var track = -1
    private var muxerStarted = false
    private var finished = false
    private var result = false

    var frameCount = 0
        private set

    init {
        val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, (width * height * BITS_PER_PIXEL).coerceAtMost(MAX_BIT_RATE))
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        try {
            require(width % 16 == 0 && height % 16 == 0) { "Encoder size must be a multiple of 16, got ${width}x$height" }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            muxer.setOrientationHint(rotationDegrees)
        } catch (e: Exception) {
            codec.release()
            muxer.release()
            throw e
        }
    }

    /** Encodes one frame laid out as [rgbaToI420] produces it. Blocks until the codec takes it; throws when it stalls. */
    fun addFrame(i420: ByteArray) {
        check(!finished) { "Encoder already finished" }
        val index = dequeueInput()
        val image = checkNotNull(codec.getInputImage(index)) { "Codec gave no input image" }
        copyI420(i420, image)
        codec.queueInputBuffer(index, 0, width * height * 3 / 2, frameTimestampUs(frameCount, fps), 0)
        frameCount++
        drain(endOfStream = false)
    }

    /**
     * Completes the file with what was written. Never throws. Returns true only when the file holds at least
     * one frame and the muxer wrote its index; otherwise the output would not play and should be discarded.
     */
    fun finish(): Boolean {
        if (finished) return result
        finished = true
        if (frameCount > 0) {
            try {
                codec.queueInputBuffer(
                    dequeueInput(),
                    0,
                    0,
                    frameTimestampUs(frameCount, fps),
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                )
                drain(endOfStream = true)
            } catch (e: Exception) {
                // The frames muxed so far are still worth keeping.
                Log.w(TAG, "Ending the time-lapse stream failed", e)
            }
        }
        runCatching { codec.stop() }
        runCatching { codec.release() }
        // A muxer that failed to stop wrote no index, so the file would not play.
        val muxerStopped = muxerStarted && runCatching { muxer.stop() }
            .onFailure { Log.e(TAG, "Stopping the muxer failed", it) }
            .isSuccess
        runCatching { muxer.release() }
        result = frameCount > 0 && muxerStopped
        return result
    }

    override fun close() {
        finish()
    }

    private fun dequeueInput(): Int {
        val deadline = System.nanoTime() + STALL_TIMEOUT_NS
        while (true) {
            val index = codec.dequeueInputBuffer(TIMEOUT_US)
            if (index >= 0) return index
            check(System.nanoTime() < deadline) { "Codec took no input for $STALL_TIMEOUT_NS ns" }
            // The codec is full: emptying its output frees input buffers.
            drain(endOfStream = false)
        }
    }

    private fun drain(endOfStream: Boolean) {
        val deadline = System.nanoTime() + STALL_TIMEOUT_NS
        while (true) {
            val index = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) return
                    // A codec in an error state may never signal the end; the muxer then stops with what it has.
                    check(System.nanoTime() < deadline) { "Codec gave no end of stream for $STALL_TIMEOUT_NS ns" }
                }
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxerStarted = true
                }
                index >= 0 -> {
                    val buffer = checkNotNull(codec.getOutputBuffer(index))
                    val isConfig = bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (!isConfig && bufferInfo.size > 0 && muxerStarted) {
                        buffer.position(bufferInfo.offset)
                        buffer.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(track, buffer, bufferInfo)
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    /** Writes planar I420 into the codec's image, whatever its strides (planar or semi-planar). */
    private fun copyI420(i420: ByteArray, image: Image) {
        val chromaWidth = width / 2
        val chromaHeight = height / 2
        val ySize = width * height
        copyPlane(i420, 0, width, height, image.planes[0])
        copyPlane(i420, ySize, chromaWidth, chromaHeight, image.planes[1])
        copyPlane(i420, ySize + ySize / 4, chromaWidth, chromaHeight, image.planes[2])
    }

    private fun copyPlane(source: ByteArray, start: Int, planeWidth: Int, planeHeight: Int, plane: Image.Plane) {
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        for (row in 0 until planeHeight) {
            val sourceRow = start + row * planeWidth
            val targetRow = row * rowStride
            if (pixelStride == 1) {
                buffer.position(targetRow)
                buffer.put(source, sourceRow, planeWidth)
            } else {
                for (col in 0 until planeWidth) buffer.put(targetRow + col * pixelStride, source[sourceRow + col])
            }
        }
    }

    private companion object {
        const val TAG = "TimelapseEncoder"
        const val STALL_TIMEOUT_NS = 2_000_000_000L
        const val MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC
        const val TIMEOUT_US = 10_000L
        const val BITS_PER_PIXEL = 4
        const val MAX_BIT_RATE = 20_000_000
    }
}
