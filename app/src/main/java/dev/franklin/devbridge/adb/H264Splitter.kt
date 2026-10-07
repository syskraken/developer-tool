package dev.franklin.devbridge.adb

import java.io.ByteArrayOutputStream

/**
 * Cuts an Annex-B H.264 byte stream into NAL units, each returned with its
 * start code. The stream arrives in arbitrary chunks, so the trailing NAL is
 * held back until the next start code shows up or [flush] is called.
 */
class H264Splitter {

    private var pending = ByteArray(0)
    private var pendingLength = 0

    /** NAL unit type (low five bits) of a unit produced by this class. */
    fun nalType(nal: ByteArray): Int {
        val offset = if (nal.size > 3 && nal[2] == 1.toByte()) 3 else 4
        return if (nal.size > offset) nal[offset].toInt() and 0x1F else -1
    }

    fun feed(chunk: ByteArray): List<ByteArray> {
        if (chunk.isEmpty()) return emptyList()
        ensure(pendingLength + chunk.size)
        System.arraycopy(chunk, 0, pending, pendingLength, chunk.size)
        pendingLength += chunk.size

        val out = ArrayList<ByteArray>()
        var start = findStart(0)
        if (start < 0) {
            // No start code yet; drop leading garbage but keep a few bytes that could begin one.
            if (pendingLength > 3) keepTail(pendingLength - 3)
            return out
        }
        // A four-byte start code is found at its last three bytes; its leading zero belongs to the unit.
        if (start > 0 && pending[start - 1] == 0.toByte()) start--
        if (start > 0) discardFront(start).also { start = 0 }

        while (true) {
            val next = findStart(start + 3)
            if (next < 0) break
            // A four-byte start code's leading zero belongs to the next unit, not this one.
            val end = if (next > 0 && pending[next - 1] == 0.toByte()) next - 1 else next
            out += pending.copyOfRange(start, end)
            start = end
        }
        if (start > 0) discardFront(start)
        return out
    }

    /** Releases the trailing unit; call when the stream has gone quiet. */
    fun flush(): ByteArray? {
        if (pendingLength < 4 || findStart(0) !in 0..1) return null
        val nal = pending.copyOfRange(0, pendingLength)
        pendingLength = 0
        return nal
    }

    private fun findStart(from: Int): Int {
        var i = from
        while (i + 2 < pendingLength) {
            if (pending[i + 2] > 1) { i += 3; continue }
            if (pending[i] == 0.toByte() && pending[i + 1] == 0.toByte() && pending[i + 2] == 1.toByte()) return i
            i++
        }
        return -1
    }

    private fun ensure(size: Int) {
        if (pending.size < size) pending = pending.copyOf(maxOf(size, pending.size * 2, 4096))
    }

    private fun discardFront(count: Int) {
        System.arraycopy(pending, count, pending, 0, pendingLength - count)
        pendingLength -= count
    }

    private fun keepTail(count: Int) = discardFront(pendingLength - count)
}

/** Groups NAL units into decodable buffers: SPS and PPS ride in front of the next picture. */
class H264Framer {
    private val splitter = H264Splitter()
    private val header = ByteArrayOutputStream()

    /** One decodable unit; [keyFrame] is true when it begins with parameter sets or is an IDR slice. */
    class Unit(val data: ByteArray, val keyFrame: Boolean)

    fun feed(chunk: ByteArray): List<Unit> = splitter.feed(chunk).mapNotNull { accept(it) }

    fun flush(): Unit? = splitter.flush()?.let { accept(it) }

    private fun accept(nal: ByteArray): Unit? {
        return when (splitter.nalType(nal)) {
            7, 8 -> { header.write(nal); null }                       // SPS, PPS: hold for the next picture
            1, 5 -> {
                val key = splitter.nalType(nal) == 5 || header.size() > 0
                val data = if (header.size() > 0) header.toByteArray() + nal else nal
                header.reset()
                Unit(data, key)
            }
            else -> null                                                // SEI, AUD and the rest add nothing
        }
    }
}
