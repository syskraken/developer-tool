package dev.franklin.devbridge.adb

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The `sync:` file-transfer service, push direction only. */
object AdbSync {

    private const val CHUNK = 64 * 1024
    private const val MODE_FILE_0644 = 33188   // S_IFREG | 0644

    fun push(connection: AdbConnection, data: ByteArray, remotePath: String, mtimeSeconds: Int = (System.currentTimeMillis() / 1000).toInt()) {
        val stream = connection.open("sync:", 10_000)
        try {
            val target = "$remotePath,$MODE_FILE_0644".toByteArray(Charsets.UTF_8)
            stream.write(frame("SEND", target.size) + target)

            var offset = 0
            while (offset < data.size) {
                val n = minOf(CHUNK, data.size - offset)
                stream.write(frame("DATA", n) + data.copyOfRange(offset, offset + n))
                offset += n
            }
            stream.write(frame("DONE", mtimeSeconds))

            val response = readExactly(stream, 8)
            val id = String(response, 0, 4, Charsets.US_ASCII)
            if (id != "OKAY") {
                val length = ByteBuffer.wrap(response, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.coerceIn(0, 4096)
                val message = if (id == "FAIL") String(readExactly(stream, length), Charsets.UTF_8) else "unexpected reply $id"
                throw AdbException("Could not copy to $remotePath: $message")
            }
        } finally {
            stream.close()
        }
    }

    private fun frame(id: String, value: Int): ByteArray =
        ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).put(id.toByteArray(Charsets.US_ASCII)).putInt(value).array()

    private fun readExactly(stream: AdbConnection.AdbStream, count: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val deadline = System.currentTimeMillis() + 15_000
        while (out.size() < count) {
            val wait = deadline - System.currentTimeMillis()
            if (wait <= 0) throw AdbException("Timed out waiting for the device to confirm the copy")
            val chunk = stream.read(wait) ?: continue
            if (stream.eof) throw AdbException("Device closed the transfer early")
            out.write(chunk)
        }
        return out.toByteArray().copyOf(count)
    }
}
