package dev.franklin.devbridge

import dev.franklin.devbridge.adb.Adb
import dev.franklin.devbridge.adb.AdbCodec
import dev.franklin.devbridge.adb.AdbConnection
import dev.franklin.devbridge.adb.AdbKey
import dev.franklin.devbridge.adb.AdbMessage
import dev.franklin.devbridge.adb.AdbSync
import dev.franklin.devbridge.adb.TcpTransport
import dev.franklin.devbridge.server.ControlProtocol
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

class HelperTest {

    private val server = ServerSocket(0)
    private var worker: Thread? = null

    @After
    fun tearDown() { server.close(); worker?.interrupt() }

    private fun InputStream.message(): AdbMessage {
        val header = AdbCodec.decodeHeader(ByteArray(Adb.HEADER_SIZE).also { DataInputStream(this).readFully(it) })
        val data = ByteArray(header.length).also { DataInputStream(this).readFully(it) }
        return AdbMessage(header.command, header.arg0, header.arg1, data)
    }

    private fun Socket.send(m: AdbMessage) {
        getOutputStream().write(AdbCodec.encodeHeader(m)); getOutputStream().write(m.data); getOutputStream().flush()
    }

    private fun device(script: (Socket) -> Unit) {
        worker = Thread { try { server.accept().use(script) } catch (e: Exception) { /* ended */ } }.apply { isDaemon = true; start() }
    }

    private fun connect(): AdbConnection {
        val c = AdbConnection(TcpTransport("127.0.0.1", server.localPort), AdbKey.generate())
        c.connect(5000)
        return c
    }

    @Test
    fun largeWritesAreSplitAndEachChunkIsAcknowledged() {
        val received = ByteArrayOutputStream()
        val chunks = ArrayList<Int>()
        device { s ->
            val input = s.getInputStream()
            input.message()
            s.send(AdbMessage(Adb.A_CNXN, Adb.VERSION, 4096, "device::".toByteArray()))
            val open = input.message()
            s.send(AdbMessage(Adb.A_OKAY, 3, open.arg0))
            while (received.size() < 10_000) {
                val w = input.message()
                assertEquals(Adb.A_WRTE, w.command)
                received.write(w.data); chunks += w.data.size
                s.send(AdbMessage(Adb.A_OKAY, 3, open.arg0))              // ack
            }
            Thread.sleep(300)
        }
        val c = connect()
        val stream = c.open("exec:cat")
        val payload = ByteArray(10_000) { (it % 251).toByte() }
        stream.write(payload)
        Thread.sleep(200)
        assertArrayEquals(payload, received.toByteArray())
        assertEquals(listOf(4096, 4096, 1808), chunks)
        c.close()
    }

    @Test
    fun syncPushSendsFramedFileAndAcceptsOkay() {
        val file = ByteArray(70_000) { (it * 3).toByte() }       // spans more than one 64 KB DATA frame
        val got = ByteArrayOutputStream()
        device { s ->
            val input = s.getInputStream()
            input.message()
            s.send(AdbMessage(Adb.A_CNXN, Adb.VERSION, 65536, "device::".toByteArray()))
            val open = input.message()
            assertEquals("sync:\u0000", String(open.data))
            s.send(AdbMessage(Adb.A_OKAY, 4, open.arg0))
            // Collect the raw sync byte stream until DONE has been seen.
            while (true) {
                val w = input.message()
                got.write(w.data)
                s.send(AdbMessage(Adb.A_OKAY, 4, open.arg0))
                val b = got.toByteArray()
                if (b.size >= 8 && String(b, b.size - 8, 4) == "DONE") break
            }
            s.send(AdbMessage(Adb.A_WRTE, 4, open.arg0, "OKAY".toByteArray() + ByteArray(4)))
            input.message()                                                 // our ack of that reply
            Thread.sleep(300)
        }
        val c = connect()
        AdbSync.push(c, file, "/data/local/tmp/x.apk", 1234)
        val b = ByteBuffer.wrap(got.toByteArray()).order(ByteOrder.LITTLE_ENDIAN)
        val sendId = ByteArray(4).also { b.get(it) }
        assertEquals("SEND", String(sendId))
        val pathLen = b.int
        val path = ByteArray(pathLen).also { b.get(it) }
        assertEquals("/data/local/tmp/x.apk,33188", String(path))
        val rebuilt = ByteArrayOutputStream()
        while (true) {
            val id = String(ByteArray(4).also { b.get(it) })
            val n = b.int
            if (id == "DONE") { assertEquals(1234, n); break }
            assertEquals("DATA", id)
            rebuilt.write(ByteArray(n).also { b.get(it) })
        }
        assertArrayEquals(file, rebuilt.toByteArray())
        c.close()
    }

    @Test
    fun controlFramesRoundTrip() {
        val touch = ControlProtocol.read(DataInputStream(ControlProtocol.touch(ControlProtocol.MOVE, 2, -5, 1999, 1).inputStream()))!!
        assertEquals(ControlProtocol.TOUCH, touch.type)
        assertEquals(ControlProtocol.MOVE, touch.body.readUnsignedByte())
        assertEquals(2, touch.body.readUnsignedByte())
        assertEquals(-5, touch.body.readInt())
        assertEquals(1999, touch.body.readInt())
        assertEquals(1, touch.body.readInt())

        val text = ControlProtocol.read(DataInputStream(ControlProtocol.text("héllo wörld", 0).inputStream()))!!
        assertEquals(0, text.body.readInt())
        val bytes = ByteArray(text.remaining - 4).also { text.body.readFully(it) }
        assertEquals("héllo wörld", String(bytes, Charsets.UTF_8))

        val hello = ControlProtocol.read(DataInputStream(ControlProtocol.hello(34).inputStream()))!!
        assertEquals(ControlProtocol.HELLO, hello.type)
        assertEquals(34, hello.body.readInt())
    }

    @Test
    fun readReturnsNullAtEndAndRejectsBadLengths() {
        assertEquals(null, ControlProtocol.read(DataInputStream(ByteArray(0).inputStream())))
        val bad = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 1)
        try { ControlProtocol.read(DataInputStream(bad.inputStream())); org.junit.Assert.fail() } catch (e: java.io.IOException) { assertNotNull(e.message) }
        assertTrue(ControlProtocol.MAGIC.endsWith("\n"))
    }
}
