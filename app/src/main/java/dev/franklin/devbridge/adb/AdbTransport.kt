package dev.franklin.devbridge.adb

import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/** A byte pipe to a device. Implementations: TCP here, USB in [UsbTransport]. */
interface AdbTransport : Closeable {
    /** Fills [buf] from [off]; [timeoutMs] of 0 waits until data arrives or the transport closes. */
    @Throws(IOException::class)
    fun readFully(buf: ByteArray, off: Int, len: Int, timeoutMs: Int)

    @Throws(IOException::class)
    fun write(buf: ByteArray, off: Int, len: Int)
}

class TcpTransport(host: String, port: Int, connectTimeoutMs: Int = 5000) : AdbTransport {

    private val socket = Socket()

    init {
        try {
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
        } catch (e: IOException) {
            socket.close()
            throw e
        }
    }

    override fun readFully(buf: ByteArray, off: Int, len: Int, timeoutMs: Int) {
        socket.soTimeout = timeoutMs
        var done = 0
        while (done < len) {
            val n = try {
                socket.getInputStream().read(buf, off + done, len - done)
            } catch (e: SocketTimeoutException) {
                throw e
            }
            if (n < 0) throw IOException("Connection closed by device")
            done += n
        }
    }

    override fun write(buf: ByteArray, off: Int, len: Int) {
        socket.getOutputStream().write(buf, off, len)
        socket.getOutputStream().flush()
    }

    override fun close() {
        try { socket.close() } catch (e: IOException) { /* already closed */ }
    }
}
