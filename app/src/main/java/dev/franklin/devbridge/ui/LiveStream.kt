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
 * `screenrecord` stops itself after about three minutes. A second recording is
 * started shortly before that and takes over at its first key frame, so the
 * picture carries on without a freeze.
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

    private companion object {
        /** `screenrecord` is told to stop at 170 s; the replacement starts 10 s earlier. */
        const val ROTATE_AFTER_MS = 160_000L
    }

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

    /** One running `screenrecord` and the parser reading it. */
    private class Source(val stream: AdbConnection.AdbStream) {
        val framer = H264Framer()
        val startedAt = System.currentTimeMillis()
        val text = ByteArrayOutputStream()
        var gotVideo = false
    }

    private fun open(): Source = Source(connection.open("exec:${command()}", 8_000))

    private fun run() {
        var decoder: Decoder? = null
        var current: Source? = null
        var next: Source? = null
        var nextReady: MutableList<H264Framer.Unit>? = null
        try {
            decoder = Decoder(surface, width, height)
            current = try {
                open()
            } catch (e: Exception) {
                if (!stopped) listener.onFailed(e.message ?: "Could not start screen recording")
                return
            }
            var windowStart = System.currentTimeMillis()
            var windowFrames = 0
            var lastOpenAttempt = 0L

            while (!stopped) {
                val source = current!!
                val chunk = source.stream.read(if (next == null) 25 else 5)
                if (chunk == null) {
                    // The phone went quiet, so the last unit is complete: release it.
                    source.framer.flush()?.let { decoder.queue(it); windowFrames++ }
                } else if (!source.stream.eof) {
                    if (!source.gotVideo && !hasStartCode(chunk)) {
                        if (source.text.size() < 2000) source.text.write(chunk)
                    } else {
                        source.gotVideo = true
                        for (unit in source.framer.feed(chunk)) {
                            decoder.queue(unit)
                            windowFrames++
                        }
                    }
                }

                val now = System.currentTimeMillis()

                // screenrecord ends after a few minutes. Start the next one early and switch over at its
                // first key frame, so the picture never freezes.
                if (next == null && now - source.startedAt > ROTATE_AFTER_MS && now - lastOpenAttempt > 5_000) {
                    lastOpenAttempt = now
                    next = try { open() } catch (e: Exception) { null }
                    nextReady = null
                }
                val upcoming = next
                if (upcoming != null) {
                    val data = upcoming.stream.read(0)
                    if (data != null && !upcoming.stream.eof && (upcoming.gotVideo || hasStartCode(data))) {
                        upcoming.gotVideo = true
                        val units = upcoming.framer.feed(data)
                        if (nextReady == null && units.isNotEmpty() && units.first().keyFrame) nextReady = units.toMutableList()
                        else nextReady?.addAll(units)
                    }
                    val ready = nextReady
                    if (ready != null) {
                        source.stream.close()
                        for (unit in ready) { decoder.queue(unit); windowFrames++ }
                        current = upcoming
                        next = null
                        nextReady = null
                    } else if (upcoming.stream.eof) {
                        upcoming.stream.close()
                        next = null
                    }
                }

                if (current!!.stream.eof) {
                    // Ended before a replacement was ready: reopen straight away.
                    current.stream.close()
                    if (!current.gotVideo) {
                        val reason = String(current.text.toByteArray(), Charsets.UTF_8).trim().lineSequence().lastOrNull().orEmpty()
                        listener.onFailed(reason.ifEmpty { "The phone did not produce any video (screen recording unsupported or blocked)" })
                        return
                    }
                    current = try { open() } catch (e: Exception) {
                        if (!stopped) listener.onFailed(e.message ?: "Could not restart screen recording")
                        return
                    }
                }

                if (now - windowStart >= 1000) {
                    listener.onLive(windowFrames)
                    windowFrames = 0
                    windowStart = now
                }
            }
        } catch (e: Exception) {
            if (!stopped) listener.onFailed(e.message ?: e.javaClass.simpleName)
        } finally {
            current?.stream?.close()
            next?.stream?.close()
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
