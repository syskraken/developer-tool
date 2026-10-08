package dev.franklin.devbridge.adb

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * A minimal ADB client: authenticates, then multiplexes one-shot services
 * (`exec:` / `shell:`) over the single transport. No ADB server or `adb`
 * binary is involved, so it runs on a phone.
 */
class AdbConnection(
    private val transport: AdbTransport,
    private val key: AdbKey,
    private val keyComment: String = "devbridge@android",
) : Closeable {

    internal class Stream {
        val chunks = LinkedBlockingQueue<ByteArray>()
        val opened = CountDownLatch(1)
        @Volatile var remoteId = 0
        @Volatile var rejected = false
    }

    private val sendLock = Any()
    private val streams = ConcurrentHashMap<Int, Stream>()
    private val nextId = AtomicInteger(1)
    private var reader: Thread? = null

    @Volatile var banner: String = ""
        private set

    @Volatile var isClosed = false
        private set

    /** Cleared if the device refuses `exec:` (very old Android), after which `shell:` is used. */
    @Volatile private var execSupported = true

    /** How long to wait in silence before sending the connection request again. */
    internal var resendIntervalMs = 3_000

    /**
     * Handshake and authentication. [onStep] narrates progress so a stall can be pinned to one stage;
     * [onAuthPrompt] fires when the device wants the user to approve this key on its screen, and
     * [authTimeoutMs] is how long to wait for that tap. A phone that stays silent is asked again every
     * few seconds until [firstReplyTimeoutMs] has passed.
     */
    fun connect(
        authTimeoutMs: Int = 60_000,
        firstReplyTimeoutMs: Int = 10_000,
        onStep: (String) -> Unit = {},
        onAuthPrompt: () -> Unit = {},
    ) {
        // 0 = nothing heard yet, 1 = signing in, 2 = key sent and waiting for a person to approve.
        var stage = 0
        try {
            val request = AdbMessage(Adb.A_CNXN, Adb.VERSION, Adb.MAX_PAYLOAD, "host::\u0000".toByteArray())
            send(request)
            onStep("Asked the phone to connect…")
            val deadline = System.currentTimeMillis() + firstReplyTimeoutMs
            var signed = false
            while (true) {
                val wait = when (stage) {
                    0 -> maxOf(1L, minOf(resendIntervalMs.toLong(), deadline - System.currentTimeMillis())).toInt()
                    1 -> 10_000
                    else -> authTimeoutMs
                }
                val m = try {
                    readMessage(wait)
                } catch (e: SocketTimeoutException) {
                    if (stage == 0 && System.currentTimeMillis() < deadline) {
                        send(request)
                        onStep("No answer yet, asking the phone again…")
                        continue
                    }
                    throw e
                }
                when (m.command) {
                    Adb.A_CNXN -> {
                        banner = String(m.data, Charsets.UTF_8).trimEnd('\u0000')
                        onStep("Connected")
                        break
                    }
                    Adb.A_AUTH -> if (m.arg0 == Adb.AUTH_TOKEN) {
                        if (!signed) {
                            stage = 1
                            send(AdbMessage(Adb.A_AUTH, Adb.AUTH_SIGNATURE, 0, key.signToken(m.data)))
                            signed = true
                            onStep("The phone answered. Checking whether it already trusts this app…")
                        } else {
                            stage = 2
                            send(AdbMessage(Adb.A_AUTH, Adb.AUTH_RSAPUBLICKEY, 0, key.publicKeyBytes(keyComment)))
                            onAuthPrompt()
                        }
                    }
                    else -> { /* ignore anything unexpected during the handshake */ }
                }
            }
        } catch (e: SocketTimeoutException) {
            close()
            throw AdbException(
                when (stage) {
                    0 -> "The phone did not answer. Wake and unlock it (a sleeping or locked phone often will not talk), " +
                        "then unplug and replug the cable and try again."
                    1 -> "The phone stopped answering while signing in. Unplug and replug the cable and try again."
                    else -> "The phone was asked to approve this connection, but nothing was approved. " +
                        "Make sure its screen is on and unlocked, then try again."
                },
                e,
            )
        } catch (e: IOException) {
            close()
            throw AdbException("Connection failed: ${e.message}", e)
        }

        reader = Thread({ readLoop() }, "adb-reader").apply {
            isDaemon = true
            start()
        }
    }

    /** A live service stream: read chunks as they arrive, close to stop the service on the device. */
    inner class AdbStream internal constructor(
        private val localId: Int,
        private val stream: Stream,
    ) : Closeable {

        @Volatile var eof = false
            private set

        /**
         * Next chunk, or null if none arrived within [timeoutMs]. At end of
         * stream returns an empty array and [eof] becomes true.
         */
        fun read(timeoutMs: Long): ByteArray? {
            if (eof) return EOF
            val chunk = stream.chunks.poll(timeoutMs, TimeUnit.MILLISECONDS) ?: return null
            if (chunk === EOF) eof = true
            return chunk
        }

        override fun close() {
            val removed = streams.remove(localId)
            if (removed != null && removed.remoteId != 0 && !isClosed) {
                try { send(AdbMessage(Adb.A_CLSE, localId, removed.remoteId)) } catch (e: IOException) { /* gone */ }
            }
            eof = true
        }
    }

    /** Starts a service (for example `exec:screenrecord ...`) and returns a stream to read it. */
    fun open(service: String, timeoutMs: Int = 20_000): AdbStream {
        if (isClosed) throw AdbException("Not connected")
        val localId = nextId.getAndIncrement()
        val stream = Stream()
        streams[localId] = stream
        val handle = AdbStream(localId, stream)
        try {
            send(AdbMessage(Adb.A_OPEN, localId, 0, "$service\u0000".toByteArray()))
            if (!stream.opened.await(timeoutMs.toLong(), TimeUnit.MILLISECONDS)) {
                throw AdbException("Device did not answer '$service'")
            }
            if (stream.rejected) throw AdbException("Device refused '$service'")
        } catch (e: Exception) {
            handle.close()
            throw e
        }
        return handle
    }

    /** Runs a service and returns everything it printed. Binary safe when `exec:` is available. */
    fun run(service: String, timeoutMs: Int = 20_000): ByteArray {
        val stream = open(service, timeoutMs)
        try {
            val out = ByteArrayOutputStream()
            val deadline = System.currentTimeMillis() + timeoutMs
            while (true) {
                val wait = deadline - System.currentTimeMillis()
                if (wait <= 0) throw AdbException("Timed out running '$service'")
                val chunk = stream.read(wait) ?: continue
                if (stream.eof) break
                out.write(chunk)
            }
            return out.toByteArray()
        } finally {
            stream.close()
        }
    }

    /** Runs a shell command and returns its output as text. */
    fun shell(command: String, timeoutMs: Int = 20_000): String {
        if (execSupported) {
            try {
                return String(run("exec:$command", timeoutMs), Charsets.UTF_8)
            } catch (e: AdbException) {
                if (e.message?.startsWith("Device refused") != true) throw e
                execSupported = false
            }
        }
        // shell: allocates a pty on old devices, which turns \n into \r\n.
        return String(run("shell:$command", timeoutMs), Charsets.UTF_8).replace("\r\n", "\n")
    }

    /** Like [shell] but returns raw bytes; used for screenshots. */
    fun shellBytes(command: String, timeoutMs: Int = 20_000): ByteArray {
        if (execSupported) return run("exec:$command", timeoutMs)
        val raw = run("shell:$command", timeoutMs)
        return stripCr(raw)
    }

    private fun stripCr(raw: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(raw.size)
        var i = 0
        while (i < raw.size) {
            if (raw[i] == 0x0D.toByte() && i + 1 < raw.size && raw[i + 1] == 0x0A.toByte()) i++
            out.write(raw[i].toInt())
            i++
        }
        return out.toByteArray()
    }

    private fun readLoop() {
        try {
            while (!isClosed) {
                val m = readMessage(0)
                when (m.command) {
                    Adb.A_OKAY -> streams[m.arg1]?.let {
                        if (it.remoteId == 0) {
                            it.remoteId = m.arg0
                            it.opened.countDown()
                        }
                    }
                    Adb.A_WRTE -> {
                        val stream = streams[m.arg1]
                        stream?.chunks?.offer(m.data)
                        send(AdbMessage(Adb.A_OKAY, m.arg1, m.arg0))
                    }
                    Adb.A_CLSE -> streams[m.arg1]?.let {
                        if (it.remoteId == 0) {
                            it.rejected = true
                            it.opened.countDown()
                        }
                        it.chunks.offer(EOF)
                    }
                    else -> { /* not meaningful once connected */ }
                }
            }
        } catch (e: Exception) {
            // Transport dropped or was closed; fail whatever is still waiting.
        } finally {
            isClosed = true
            for (stream in streams.values) {
                stream.rejected = stream.remoteId == 0
                stream.opened.countDown()
                stream.chunks.offer(EOF)
            }
        }
    }

    private fun send(message: AdbMessage) = synchronized(sendLock) {
        val header = AdbCodec.encodeHeader(message)
        transport.write(header, 0, header.size)
        if (message.data.isNotEmpty()) transport.write(message.data, 0, message.data.size)
    }

    private fun readMessage(timeoutMs: Int): AdbMessage {
        val headerBytes = ByteArray(Adb.HEADER_SIZE)
        transport.readFully(headerBytes, 0, headerBytes.size, timeoutMs)
        val header = AdbCodec.decodeHeader(headerBytes)
        val data = ByteArray(header.length)
        if (header.length > 0) transport.readFully(data, 0, data.size, if (timeoutMs == 0) 0 else 10_000)
        return AdbMessage(header.command, header.arg0, header.arg1, data)
    }

    override fun close() {
        isClosed = true
        transport.close()
        reader?.interrupt()
    }

    private companion object {
        val EOF = ByteArray(0)
    }
}
