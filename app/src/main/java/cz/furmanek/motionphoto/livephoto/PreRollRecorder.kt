package cz.furmanek.motionphoto.livephoto

import android.Manifest
import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.abs

/**
 * Keeps encoding the camera's analysis frames (and the microphone, when [withAudio]) into a [ClipBuffer], so a
 * live photo's clip can start before the shutter press. Frames come in through [analyzer]; [cut] writes the
 * clip around a press. [mirrored] flips the frames horizontally (front camera with mirroring on).
 */
class PreRollRecorder(private val withAudio: Boolean, private val mirrored: Boolean) {

    private val buffer = ClipBuffer()
    private val lock = Any()
    private val _failed = MutableStateFlow(false)

    /** The encoder broke; the session falls back to recording after the press. */
    val failed: StateFlow<Boolean> = _failed.asStateFlow()

    private var video: MediaCodec? = null
    private var videoFormat: MediaFormat? = null
    private var width = 0
    private var height = 0
    private var rotationDegrees = 0
    private var clockOffsetNs: Long? = null
    private var running = false
    private var audioThread: Thread? = null

    @Volatile
    private var audioFormat: MediaFormat? = null
    private val info = MediaCodec.BufferInfo()
    private var srcRow = ByteArray(0)
    private var dstRow = ByteArray(0)

    val analyzer = ImageAnalysis.Analyzer { image -> image.use { encode(it) } }

    @SuppressLint("MissingPermission")
    fun start() {
        synchronized(lock) {
            if (running) return
            running = true
        }
        if (withAudio) audioThread = Thread(::recordAudio, "PreRollAudio").apply { start() }
    }

    /** Stops encoding and frees the codecs, the microphone and the buffered clip. */
    fun stop() {
        synchronized(lock) {
            running = false
            releaseVideo()
        }
        audioThread?.let {
            it.interrupt()
            it.join(AUDIO_JOIN_MS)
        }
        audioThread = null
        buffer.clear()
    }

    /**
     * Waits until [POST_ROLL_US] after [pressUs] is encoded, then writes the clip into [out]. Returns the clip
     * (for the still's position) or null when there is nothing usable.
     */
    suspend fun cut(pressUs: Long, out: File): Clip? {
        withTimeoutOrNull(CUT_TIMEOUT_MS) {
            while ((buffer.newestVideoUs ?: Long.MIN_VALUE) < pressUs + POST_ROLL_US) delay(POLL_MS)
        }
        val clip = buffer.clip(pressUs, PRE_ROLL_US, POST_ROLL_US) ?: return null
        if (clip.lastVideoUs < pressUs) return null
        val format = synchronized(lock) { videoFormat } ?: return null
        val rotation = synchronized(lock) { rotationDegrees }
        return withContext(Dispatchers.IO) {
            try {
                ClipWriter.write(clip, format, audioFormat, rotation, out)
                clip
            } catch (e: Exception) {
                Log.w(TAG, "Writing the pre-roll clip failed", e)
                null
            }
        }
    }

    private fun encode(image: ImageProxy) = synchronized(lock) {
        if (!running || _failed.value) return@synchronized
        try {
            val codec = video ?: createVideo(image)
            rotationDegrees = image.imageInfo.rotationDegrees
            val index = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
            if (index >= 0) {
                val input = codec.getInputImage(index) ?: error("Encoder gave no input image")
                copyFrame(image, input)
                codec.queueInputBuffer(index, 0, width * height * 3 / 2, toRealtimeUs(image.imageInfo.timestamp), 0)
            }
            drainVideo(codec)
        } catch (e: Exception) {
            Log.w(TAG, "Pre-roll video encoder failed", e)
            releaseVideo()
            _failed.value = true
        }
    }

    private fun createVideo(image: ImageProxy): MediaCodec {
        width = image.width / 16 * 16
        height = image.height / 16 * 16
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, (width * height * BITS_PER_PIXEL).coerceAtMost(MAX_BIT_RATE))
            setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
            setFloat(MediaFormat.KEY_I_FRAME_INTERVAL, KEY_FRAME_INTERVAL_S)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
        } catch (e: Exception) {
            codec.release()
            throw e
        }
        video = codec
        return codec
    }

    private fun drainVideo(codec: MediaCodec) {
        while (true) {
            val index = codec.dequeueOutputBuffer(info, 0)
            when {
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> videoFormat = codec.outputFormat
                index < 0 -> return
                else -> {
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                        buffer.add(EncodedSample(Track.VIDEO, info.presentationTimeUs, info.isKeyFrame(), codec.copyOut(index, info)))
                    }
                    codec.releaseOutputBuffer(index, false)
                }
            }
        }
    }

    private fun releaseVideo() {
        video?.let {
            try {
                it.stop()
            } catch (_: Exception) {
            }
            it.release()
        }
        video = null
        videoFormat = null
    }

    /** Camera frame times are usually on the elapsed-realtime clock; some devices use uptime instead. */
    private fun toRealtimeUs(timestampNs: Long): Long {
        val offset = clockOffsetNs ?: run {
            val realtime = SystemClock.elapsedRealtimeNanos()
            (if (abs(realtime - timestampNs) > UPTIME_GUESS_NS) realtime - SystemClock.uptimeNanos() else 0L)
                .also { clockOffsetNs = it }
        }
        return (timestampNs + offset) / 1_000
    }

    private fun copyFrame(src: ImageProxy, dst: Image) {
        for (plane in 0 until 3) {
            val w = if (plane == 0) width else width / 2
            val h = if (plane == 0) height else height / 2
            copyPlane(src.planes[plane].buffer, src.planes[plane].rowStride, src.planes[plane].pixelStride, dst.planes[plane], w, h)
        }
    }

    /** Row by row through arrays; mirrors horizontally when [mirrored]. */
    private fun copyPlane(src: ByteBuffer, srcRowStride: Int, srcPixelStride: Int, dst: Image.Plane, w: Int, h: Int) {
        val dstBuffer = dst.buffer
        val dstPixelStride = dst.pixelStride
        val srcRowLength = (w - 1) * srcPixelStride + 1
        val dstRowLength = (w - 1) * dstPixelStride + 1
        if (srcRow.size < srcRowLength) srcRow = ByteArray(srcRowLength)
        if (dstRow.size < dstRowLength) dstRow = ByteArray(dstRowLength)
        val direct = srcPixelStride == 1 && dstPixelStride == 1 && !mirrored
        for (y in 0 until h) {
            src.position(y * srcRowStride)
            src.get(srcRow, 0, srcRowLength)
            if (!direct) {
                dstBuffer.position(y * dst.rowStride)
                // Interleaved destinations (NV12) share bytes with the other chroma plane: keep what is there.
                dstBuffer.get(dstRow, 0, dstRowLength)
                for (x in 0 until w) {
                    val sx = if (mirrored) w - 1 - x else x
                    dstRow[x * dstPixelStride] = srcRow[sx * srcPixelStride]
                }
            }
            dstBuffer.position(y * dst.rowStride)
            dstBuffer.put(if (direct) srcRow else dstRow, 0, dstRowLength)
        }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun recordAudio() {
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = try {
            AudioRecord(MediaRecorder.AudioSource.CAMCORDER, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuffer * 2)
        } catch (e: Exception) {
            Log.w(TAG, "No microphone for the pre-roll; the clip stays silent", e)
            return
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val audioInfo = MediaCodec.BufferInfo()
        try {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BIT_RATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, minBuffer * 2)
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            record.startRecording()
            while (!Thread.currentThread().isInterrupted) {
                val index = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
                if (index >= 0) {
                    val input = codec.getInputBuffer(index) ?: continue
                    val read = record.read(input, minOf(input.remaining(), minBuffer))
                    if (read < 0) error("AudioRecord.read failed: $read")
                    // The chunk just read started its duration ago.
                    val startUs = SystemClock.elapsedRealtimeNanos() / 1_000 - read / 2 * 1_000_000L / SAMPLE_RATE
                    codec.queueInputBuffer(index, 0, read, startUs, 0)
                }
                while (true) {
                    val out = codec.dequeueOutputBuffer(audioInfo, 0)
                    if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        audioFormat = codec.outputFormat
                    } else if (out >= 0) {
                        if (audioInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && audioInfo.size > 0) {
                            buffer.add(EncodedSample(Track.AUDIO, audioInfo.presentationTimeUs, true, codec.copyOut(out, audioInfo)))
                        }
                        codec.releaseOutputBuffer(out, false)
                    } else {
                        break
                    }
                }
            }
        } catch (e: Exception) {
            // A broken microphone leaves the clip silent rather than losing it.
            Log.w(TAG, "Pre-roll audio stopped", e)
        } finally {
            try {
                record.stop()
            } catch (_: Exception) {
            }
            record.release()
            try {
                codec.stop()
            } catch (_: Exception) {
            }
            codec.release()
        }
    }

    private fun MediaCodec.copyOut(index: Int, info: MediaCodec.BufferInfo): ByteArray {
        val out = getOutputBuffer(index) ?: return ByteArray(0)
        out.position(info.offset)
        return ByteArray(info.size).also { out.get(it) }
    }

    private fun MediaCodec.BufferInfo.isKeyFrame() = flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0

    companion object {
        private const val TAG = "PreRollRecorder"
        const val PRE_ROLL_MS = 1_500L
        const val POST_ROLL_MS = 1_500L
        const val PRE_ROLL_US = PRE_ROLL_MS * 1_000
        const val POST_ROLL_US = POST_ROLL_MS * 1_000
        private const val CUT_TIMEOUT_MS = 3_000L
        private const val POLL_MS = 30L
        private const val INPUT_TIMEOUT_US = 10_000L
        private const val AUDIO_JOIN_MS = 500L
        private const val FRAME_RATE = 30
        private const val KEY_FRAME_INTERVAL_S = 0.5f
        private const val BITS_PER_PIXEL = 4
        private const val MAX_BIT_RATE = 20_000_000
        private const val UPTIME_GUESS_NS = 1_000_000_000L
        private const val SAMPLE_RATE = 44_100
        private const val AUDIO_BIT_RATE = 128_000
    }
}
