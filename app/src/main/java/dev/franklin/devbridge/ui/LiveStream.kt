package dev.franklin.devbridge.ui

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.view.Surface
import dev.franklin.devbridge.adb.AdbConnection
import dev.franklin.devbridge.adb.H264Framer
import java.io.ByteArrayOutputStream

/**
 * Live video of the target's screen, scrcpy style: `screenrecord` encodes H.264
 * on the phone, the stream crosses ADB, and the hardware decoder here draws it
 * straight onto a [Surface].
 *
 * `screenrecord` stops after its time limit, so the stream is restarted when it
 * ends; that causes a brief freeze every few minutes.
 */
class LiveStream(
    private val connection: AdbConnection,
    private val surface: Surface,
    private val width: Int,
    private val height: Int,
    private val physicalDisplayId: String,
    private val listener: Listener,
) {
    interface Listener {
        fun onLive(framesPerSecond: Int)
        fun onFailed(reason: String)
    }

    @Volatile private var stopped = false
    private var thread: Thread? = null

    fun start() {
        thread = Thread({ run() }, "live-stream").apply { isDaemon = true; start() }
    }

    fun stop() {
        stopped = true
        thread?.interrupt()
    }

    private fun command(): String {
        val display = if (physicalDisplayId.isBlank()) "" else " --display-id $physicalDisplayId"
        return "screenrecord --output-format=h264 --size ${width}x$height --bit-rate 8000000 --time-limit 170$display -"
    }

    private fun run() {
        var decoder: Decoder? = null
        try {
            decoder = Decoder(surface, width, height)
            var restarts = 0
            while (!stopped) {
                val stream = try {
                    connection.open("exec:${command()}", 8_000)
                } catch (e: Exception) {
                    if (!stopped) listener.onFailed(e.message ?: "Could not start screen recording")
                    return
                }
                val framer = H264Framer()
                val text = ByteArrayOutputStream()
                var gotVideo = false
                var windowStart = System.currentTimeMillis()
                var windowFrames = 0
                try {
                    while (!stopped) {
                        val chunk = stream.read(25)
                        if (chunk == null) {
                            // The phone went quiet, so the last unit is complete: release it.
                            framer.flush()?.let { decoder.queue(it); windowFrames++ }
                            continue
                        }
                        if (stream.eof) break
                        if (!gotVideo) {
                            if (!hasStartCode(chunk)) {
                                if (text.size() < 2000) text.write(chunk)
                                continue
                            }
                            gotVideo = true
                        }
                        for (unit in framer.feed(chunk)) {
                            decoder.queue(unit)
                            windowFrames++
                        }
                        val now = System.currentTimeMillis()
                        if (now - windowStart >= 1000) {
                            listener.onLive(windowFrames)
                            windowFrames = 0
                            windowStart = now
                        }
                    }
                } finally {
                    stream.close()
                }
                if (stopped) break
                if (!gotVideo) {
                    val reason = String(text.toByteArray(), Charsets.UTF_8).trim().lineSequence().lastOrNull().orEmpty()
                    listener.onFailed(reason.ifEmpty { "The phone did not produce any video (screen recording unsupported or blocked)" })
                    return
                }
                restarts++
            }
        } catch (e: Exception) {
            if (!stopped) listener.onFailed(e.message ?: e.javaClass.simpleName)
        } finally {
            decoder?.close()
        }
    }

    private fun hasStartCode(b: ByteArray): Boolean {
        for (i in 0 until b.size - 2) if (b[i] == 0.toByte() && b[i + 1] == 0.toByte() && b[i + 2] == 1.toByte()) return true
        return false
    }

    /** Thin wrapper over the platform H.264 decoder. */
    private class Decoder(surface: Surface, width: Int, height: Int) {
        private val codec: MediaCodec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        private val info = MediaCodec.BufferInfo()
        private var index = 0L

        init {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1 shl 20)
            if (Build.VERSION.SDK_INT >= 30) format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            codec.configure(format, surface, null, 0)
            codec.start()
        }

        fun queue(unit: H264Framer.Unit) {
            // Dropping a frame would smear the picture until the next key frame, so wait for room.
            var slot = codec.dequeueInputBuffer(100_000)
            if (slot < 0) {
                drain()
                slot = codec.dequeueInputBuffer(100_000)
                if (slot < 0) return
            }
            val buffer = codec.getInputBuffer(slot) ?: return
            buffer.clear()
            if (unit.data.size > buffer.capacity()) {
                codec.queueInputBuffer(slot, 0, 0, 0, 0)
                return
            }
            buffer.put(unit.data)
            val flags = if (unit.keyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
            codec.queueInputBuffer(slot, 0, unit.data.size, index++ * 16_667L, flags)
            drain()
        }

        private fun drain() {
            while (true) {
                val out = codec.dequeueOutputBuffer(info, 0)
                when {
                    out >= 0 -> codec.releaseOutputBuffer(out, true)
                    out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED || out == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> continue
                    else -> return
                }
            }
        }

        fun close() {
            try { codec.stop() } catch (e: Exception) { /* not started */ }
            try { codec.release() } catch (e: Exception) { /* already released */ }
        }
    }
}
