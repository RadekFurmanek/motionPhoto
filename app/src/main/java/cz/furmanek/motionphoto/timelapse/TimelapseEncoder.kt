package cz.furmanek.motionphoto.timelapse

import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.Closeable
import java.io.File

/**
 * Encodes I420 frames of [width]×[height] (multiples of 16, see [alignedEncoderSize]) into an H.264 MP4
 * at [fps], frame n at n/fps seconds whatever the capture interval was. Not thread-safe: one worker thread.
 */
class TimelapseEncoder(
    private val output: File,
    private val width: Int,
    private val height: Int,
    rotationDegrees: Int,
    private val fps: Int = TIMELAPSE_FPS,
) : Closeable {

    private val codec = MediaCodec.createEncoderByType(MIME_TYPE)
    private val muxer = MediaMuxer(output.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private val bufferInfo = MediaCodec.BufferInfo()
    private var track = -1
    private var muxerStarted = false
    private var finished = false
    private var result = false

    var frameCount = 0
        private set

    init {
        require(width % 16 == 0 && height % 16 == 0) { "Encoder size must be a multiple of 16, got ${width}x$height" }
        val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, (width * height * BITS_PER_PIXEL).coerceAtMost(MAX_BIT_RATE))
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            muxer.setOrientationHint(rotationDegrees)
        } catch (e: RuntimeException) {
            codec.release()
            muxer.release()
            output.delete()
            throw e
        }
    }

    /** Encodes one frame laid out as [rgbaToI420] produces it. Blocks until the codec takes it. */
    fun addFrame(i420: ByteArray) {
        check(!finished) { "Encoder already finished" }
        val index = dequeueInput()
        val image = checkNotNull(codec.getInputImage(index)) { "Codec gave no input image" }
        copyI420(i420, image)
        codec.queueInputBuffer(index, 0, width * height * 3 / 2, frameTimestampUs(frameCount, fps), 0)
        frameCount++
        drain(endOfStream = false)
    }

    /** Completes the file. Returns false, and deletes the file, when no frame was written. */
    fun finish(): Boolean {
        if (finished) return result
        finished = true
        try {
            if (frameCount > 0) {
                codec.queueInputBuffer(
                    dequeueInput(),
                    0,
                    0,
                    frameTimestampUs(frameCount, fps),
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                )
                drain(endOfStream = true)
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
            if (muxerStarted) runCatching { muxer.stop() }
            muxer.release()
        }
        result = frameCount > 0 && muxerStarted
        if (!result) output.delete()
        return result
    }

    override fun close() {
        finish()
    }

    private fun dequeueInput(): Int {
        while (true) {
            val index = codec.dequeueInputBuffer(TIMEOUT_US)
            if (index >= 0) return index
            // The codec is full: emptying its output frees input buffers.
            drain(endOfStream = false)
        }
    }

    private fun drain(endOfStream: Boolean) {
        while (true) {
            val index = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!endOfStream) return
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
        const val MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC
        const val TIMEOUT_US = 10_000L
        const val BITS_PER_PIXEL = 4
        const val MAX_BIT_RATE = 20_000_000
    }
}
