package dev.franklin.devbridge.server

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException

/**
 * Framing between the controller app and the helper that runs on the target
 * phone. Every message is `u16 length, u8 type, payload`, big endian. Plain
 * Java types only, so the same file is used on both ends.
 */
object ControlProtocol {

    /** Printed first by the helper; the controller skips any noise before it. */
    const val MAGIC = "DBSRV1\n"

    // controller -> helper
    const val TOUCH = 1
    const val KEY = 2
    const val TEXT = 3
    const val SCROLL = 4
    const val PING = 5

    // helper -> controller
    const val HELLO = 0x81
    const val ERROR = 0x82
    const val PONG = 0x83

    const val DOWN = 0
    const val UP = 1
    const val MOVE = 2

    const val MAX_FRAME = 8192

    private fun frame(type: Int, body: DataOutputStream.() -> Unit): ByteArray {
        val payload = ByteArrayOutputStream()
        val out = DataOutputStream(payload)
        out.writeByte(type)
        out.body()
        val bytes = payload.toByteArray()
        val framed = ByteArrayOutputStream(bytes.size + 2)
        DataOutputStream(framed).apply { writeShort(bytes.size); write(bytes) }
        return framed.toByteArray()
    }

    fun touch(action: Int, pointerId: Int, x: Int, y: Int, displayId: Int) = frame(TOUCH) {
        writeByte(action); writeByte(pointerId); writeInt(x); writeInt(y); writeInt(displayId)
    }

    fun key(action: Int, keyCode: Int, displayId: Int) = frame(KEY) {
        writeByte(action); writeInt(keyCode); writeInt(displayId)
    }

    fun text(value: String, displayId: Int): ByteArray {
        val bytes = value.toByteArray(Charsets.UTF_8).let { if (it.size > MAX_FRAME - 16) it.copyOf(MAX_FRAME - 16) else it }
        return frame(TEXT) { writeInt(displayId); write(bytes) }
    }

    fun scroll(x: Int, y: Int, horizontal: Float, vertical: Float, displayId: Int) = frame(SCROLL) {
        writeInt(x); writeInt(y); writeFloat(horizontal); writeFloat(vertical); writeInt(displayId)
    }

    fun ping() = frame(PING) {}

    fun hello(sdk: Int) = frame(HELLO) { writeInt(sdk) }
    fun error(message: String) = frame(ERROR) { write(message.toByteArray(Charsets.UTF_8).take(2000).toByteArray()) }
    fun pong() = frame(PONG) {}

    /** One decoded message: [type] plus a stream positioned at its payload. */
    class Message(val type: Int, val body: DataInputStream, val remaining: Int)

    /** Reads one frame, or returns null at a clean end of stream. */
    fun read(input: DataInputStream): Message? {
        val length = try { input.readUnsignedShort() } catch (e: EOFException) { return null }
        if (length < 1 || length > MAX_FRAME) throw java.io.IOException("Bad control frame length $length")
        val payload = ByteArray(length)
        input.readFully(payload)
        val stream = DataInputStream(payload.inputStream())
        val type = stream.readUnsignedByte()
        return Message(type, stream, length - 1)
    }
}
