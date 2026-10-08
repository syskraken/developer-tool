package dev.franklin.devbridge

import dev.franklin.devbridge.adb.Adb
import dev.franklin.devbridge.adb.AdbCodec
import dev.franklin.devbridge.adb.AdbConnection
import dev.franklin.devbridge.adb.AdbException
import dev.franklin.devbridge.adb.AdbKey
import dev.franklin.devbridge.adb.AdbMessage
import dev.franklin.devbridge.adb.TcpTransport
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.DataInputStream
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket

/** A scripted stand-in for a phone, speaking real ADB framing over a loopback socket. */
class AdbConnectionTest {

    private val server = ServerSocket(0)
    private var worker: Thread? = null

    @After
    fun tearDown() {
        server.close()
        worker?.interrupt()
    }

    private fun InputStream.message(): AdbMessage {
        val header = AdbCodec.decodeHeader(ByteArray(Adb.HEADER_SIZE).also { DataInputStream(this).readFully(it) })
        val data = ByteArray(header.length).also { DataInputStream(this).readFully(it) }
        return AdbMessage(header.command, header.arg0, header.arg1, data)
    }

    private fun Socket.send(m: AdbMessage) {
        getOutputStream().write(AdbCodec.encodeHeader(m))
        getOutputStream().write(m.data)
        getOutputStream().flush()
    }

    private fun device(script: (Socket) -> Unit) {
        worker = Thread {
            try { server.accept().use(script) } catch (e: Exception) { /* test ended */ }
        }.apply { isDaemon = true; start() }
    }

    private fun connection(key: AdbKey = AdbKey.generate()) =
        AdbConnection(TcpTransport("127.0.0.1", server.localPort), key)

    private val token = ByteArray(20) { (it * 7).toByte() }

    @Test
    fun knownKeyAuthenticatesWithoutPrompt() {
        val key = AdbKey.generate()
        var sawSignature: ByteArray? = null
        device { s ->
            val input = s.getInputStream()
            assertEquals(Adb.A_CNXN, input.message().command)
            s.send(AdbMessage(Adb.A_AUTH, Adb.AUTH_TOKEN, 0, token))
            val auth = input.message()
            assertEquals(Adb.AUTH_SIGNATURE, auth.arg0)
            sawSignature = auth.data
            s.send(AdbMessage(Adb.A_CNXN, Adb.VERSION, 4096, "device::ro.product.model=Test".toByteArray()))
            Thread.sleep(500)
        }
        var prompted = false
        val c = connection(key)
        c.connect(5000) { prompted = true }
        assertTrue(!prompted)
        assertTrue(c.banner.startsWith("device::"))
        assertTrue(sawSignature!!.size == 256)
        c.close()
    }

    @Test
    fun unknownKeyFallsBackToPublicKeyAndPrompts() {
        val key = AdbKey.generate()
        var publicKey: ByteArray? = null
        device { s ->
            val input = s.getInputStream()
            input.message()
            s.send(AdbMessage(Adb.A_AUTH, Adb.AUTH_TOKEN, 0, token))
            assertEquals(Adb.AUTH_SIGNATURE, input.message().arg0)
            s.send(AdbMessage(Adb.A_AUTH, Adb.AUTH_TOKEN, 0, token))      // signature rejected
            val pk = input.message()
            assertEquals(Adb.AUTH_RSAPUBLICKEY, pk.arg0)
            publicKey = pk.data
            s.send(AdbMessage(Adb.A_CNXN, Adb.VERSION, 4096, "device::".toByteArray()))
            Thread.sleep(500)
        }
        var prompted = false
        val c = connection(key)
        c.connect(5000) { prompted = true }
        assertTrue(prompted)
        assertEquals(0.toByte(), publicKey!!.last())
        c.close()
    }

    @Test
    fun shellReturnsOutputAcrossChunks() {
        device { s ->
            val input = s.getInputStream()
            input.message()
            s.send(AdbMessage(Adb.A_CNXN, Adb.VERSION, 4096, "device::".toByteArray()))
            val open = input.message()
            assertEquals(Adb.A_OPEN, open.command)
            assertEquals("exec:echo hi\u0000", String(open.data))
            s.send(AdbMessage(Adb.A_OKAY, 77, open.arg0))
            s.send(AdbMessage(Adb.A_WRTE, 77, open.arg0, "hel".toByteArray()))
            assertEquals(Adb.A_OKAY, input.message().command)
            s.send(AdbMessage(Adb.A_WRTE, 77, open.arg0, "lo\n".toByteArray()))
            assertEquals(Adb.A_OKAY, input.message().command)
            s.send(AdbMessage(Adb.A_CLSE, 77, open.arg0))
            Thread.sleep(500)
        }
        val c = connection()
        c.connect(5000)
        assertEquals("hello\n", c.shell("echo hi"))
        c.close()
    }

    @Test
    fun fallsBackToShellWhenExecIsRefusedAndNormalisesLineEndings() {
        device { s ->
            val input = s.getInputStream()
            input.message()
            s.send(AdbMessage(Adb.A_CNXN, Adb.VERSION, 4096, "device::".toByteArray()))
            val first = input.message()                                    // exec: refused
            s.send(AdbMessage(Adb.A_CLSE, 0, first.arg0))
            val second = input.message()
            assertEquals("shell:id\u0000", String(second.data))
            s.send(AdbMessage(Adb.A_OKAY, 5, second.arg0))
            s.send(AdbMessage(Adb.A_WRTE, 5, second.arg0, "a\r\nb\r\n".toByteArray()))
            input.message()
            s.send(AdbMessage(Adb.A_CLSE, 5, second.arg0))
            Thread.sleep(500)
        }
        val c = connection()
        c.connect(5000)
        assertEquals("a\nb\n", c.shell("id"))
        c.close()
    }

    @Test
    fun failsPromptlyWhenDeviceDisconnects() {
        device { s ->
            val input = s.getInputStream()
            input.message()
            s.send(AdbMessage(Adb.A_CNXN, Adb.VERSION, 4096, "device::".toByteArray()))
            input.message()                                                // OPEN, then vanish
            s.close()
        }
        val c = connection()
        c.connect(5000)
        try {
            c.shell("anything", 5000)
            fail("expected failure")
        } catch (e: AdbException) {
            // expected
        }
    }

    @Test
    fun headerRoundTripsAndRejectsBadMagic() {
        val m = AdbMessage(Adb.A_WRTE, 1, 2, byteArrayOf(1, 2, 3))
        val header = AdbCodec.encodeHeader(m)
        val decoded = AdbCodec.decodeHeader(header)
        assertEquals(Adb.A_WRTE, decoded.command)
        assertEquals(3, decoded.length)
        header[20] = 0
        try { AdbCodec.decodeHeader(header); fail() } catch (e: AdbException) { /* expected */ }
        assertArrayEquals(byteArrayOf(0, 0, 0, 0), ByteArray(4))
    }

    @Test
    fun streamDeliversChunksLiveAndStopsWhenClosed() {
        val closed = java.util.concurrent.CountDownLatch(1)
        device { sock ->
            val input = sock.getInputStream()
            input.message()
            sock.send(AdbMessage(Adb.A_CNXN, Adb.VERSION, 4096, "device::".toByteArray()))
            val open = input.message()
            sock.send(AdbMessage(Adb.A_OKAY, 9, open.arg0))
            sock.send(AdbMessage(Adb.A_WRTE, 9, open.arg0, byteArrayOf(1, 2)))
            input.message()                                                // OKAY for first chunk
            sock.send(AdbMessage(Adb.A_WRTE, 9, open.arg0, byteArrayOf(3)))
            input.message()
            if (input.message().command == Adb.A_CLSE) closed.countDown() // client stops the stream
        }
        val c = connection()
        c.connect(5000)
        val stream = c.open("exec:screenrecord --output-format=h264 -")
        assertArrayEquals(byteArrayOf(1, 2), stream.read(2000))
        assertArrayEquals(byteArrayOf(3), stream.read(2000))
        assertEquals(null, stream.read(50))
        stream.close()
        assertTrue(closed.await(3, java.util.concurrent.TimeUnit.SECONDS))
        c.close()
    }

    @Test
    fun aSilentPhoneIsAskedAgainAndThenReportedAsNotAnswering() {
        var requests = 0
        device { s ->
            val input = s.getInputStream()
            while (true) { input.message(); requests++ }
        }
        val c = connection()
        c.resendIntervalMs = 100
        val steps = ArrayList<String>()
        try {
            c.connect(authTimeoutMs = 300, firstReplyTimeoutMs = 450, onStep = { steps += it })
            fail("expected a timeout")
        } catch (e: AdbException) {
            assertTrue(e.message!!, e.message!!.contains("did not answer"))
        }
        assertTrue("expected the request to be re-sent, saw $requests", requests >= 3)
        assertTrue(steps.any { it.contains("asking the phone again") })
    }

    @Test
    fun aPhoneThatNeverApprovesIsReportedAsNotApproved() {
        device { s ->
            val input = s.getInputStream()
            input.message()
            s.send(AdbMessage(Adb.A_AUTH, Adb.AUTH_TOKEN, 0, token))
            input.message()                                                // signature
            s.send(AdbMessage(Adb.A_AUTH, Adb.AUTH_TOKEN, 0, token))      // rejected
            input.message()                                                // public key, then silence
            Thread.sleep(2000)
        }
        val c = connection()
        var prompted = false
        try {
            c.connect(authTimeoutMs = 300, firstReplyTimeoutMs = 2000) { prompted = true }
            fail("expected a timeout")
        } catch (e: AdbException) {
            assertTrue(e.message!!, e.message!!.contains("nothing was approved"))
        }
        assertTrue(prompted)
    }

    @Test
    fun theHandshakeReportsEachStep() {
        device { s ->
            val input = s.getInputStream()
            input.message()
            s.send(AdbMessage(Adb.A_AUTH, Adb.AUTH_TOKEN, 0, token))
            input.message()
            s.send(AdbMessage(Adb.A_CNXN, Adb.VERSION, 4096, "device::".toByteArray()))
            Thread.sleep(300)
        }
        val c = connection()
        val steps = ArrayList<String>()
        c.connect(5000, onStep = { steps += it })
        assertEquals(listOf("Asked the phone to connect…"), steps.take(1))
        assertEquals("Connected", steps.last())
        assertTrue(steps.size >= 3)
        c.close()
    }
}
