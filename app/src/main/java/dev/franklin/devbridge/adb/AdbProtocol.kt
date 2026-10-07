package dev.franklin.devbridge.adb

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Wire constants and framing for the ADB protocol (see AOSP `adb/protocol.txt`). */
object Adb {
    const val A_SYNC = 0x434e5953
    const val A_CNXN = 0x4e584e43
    const val A_AUTH = 0x48545541
    const val A_OPEN = 0x4e45504f
    const val A_OKAY = 0x59414b4f
    const val A_WRTE = 0x45545257
    const val A_CLSE = 0x45534c43

    const val VERSION = 0x01000001
    const val MAX_PAYLOAD = 256 * 1024
    const val HEADER_SIZE = 24

    const val AUTH_TOKEN = 1
    const val AUTH_SIGNATURE = 2
    const val AUTH_RSAPUBLICKEY = 3

    /** Refuse absurd lengths from a misbehaving peer rather than allocating them. */
    const val MAX_ACCEPTED_PAYLOAD = 1024 * 1024
}

class AdbException(message: String, cause: Throwable? = null) : Exception(message, cause)

class AdbMessage(val command: Int, val arg0: Int, val arg1: Int, val data: ByteArray = ByteArray(0))

object AdbCodec {

    fun checksum(data: ByteArray): Int {
        var sum = 0
        for (b in data) sum += b.toInt() and 0xFF
        return sum
    }

    fun encodeHeader(message: AdbMessage): ByteArray {
        val buf = ByteBuffer.allocate(Adb.HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(message.command)
        buf.putInt(message.arg0)
        buf.putInt(message.arg1)
        buf.putInt(message.data.size)
        buf.putInt(checksum(message.data))
        buf.putInt(message.command xor -1)
        return buf.array()
    }

    class Header(val command: Int, val arg0: Int, val arg1: Int, val length: Int)

    fun decodeHeader(bytes: ByteArray): Header {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val command = buf.int
        val arg0 = buf.int
        val arg1 = buf.int
        val length = buf.int
        buf.int // data checksum: not verified, TCP and USB both carry their own integrity checks
        val magic = buf.int
        if (magic != (command xor -1)) throw AdbException("Corrupt ADB header (bad magic)")
        if (length < 0 || length > Adb.MAX_ACCEPTED_PAYLOAD) throw AdbException("Unreasonable ADB payload length: $length")
        return Header(command, arg0, arg1, length)
    }
}
