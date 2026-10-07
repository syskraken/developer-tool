package dev.franklin.devbridge.server

import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.PrintStream

/**
 * The helper that runs on the target phone. DevBridge copies its own APK there
 * and starts this class with `app_process`, so nothing is installed and it ends
 * when the connection does.
 *
 * It takes touch, key and text messages on stdin and injects them straight into
 * Android's input system, which is far quicker than spawning `input` per event
 * and allows several fingers at once. stdout carries replies; everything else
 * is silenced so stray log lines cannot corrupt the framing.
 */
object Server {

    @JvmStatic
    fun main(args: Array<String>) {
        val input = DataInputStream(System.`in`.buffered())
        val rawOut = DataOutputStream(BufferedOutputStream(FileOutputStream(FileDescriptor.out)))
        val silent = PrintStream(object : OutputStream() { override fun write(b: Int) {} })
        System.setOut(silent)
        System.setErr(silent)

        rawOut.write(ControlProtocol.MAGIC.toByteArray(Charsets.US_ASCII))
        rawOut.flush()

        val injector = try {
            Injector()
        } catch (t: Throwable) {
            rawOut.write(ControlProtocol.error("Input injection unavailable: ${t.javaClass.simpleName}: ${t.message}"))
            rawOut.flush()
            return
        }
        rawOut.write(ControlProtocol.hello(android.os.Build.VERSION.SDK_INT))
        rawOut.flush()

        try {
            while (true) {
                val message = ControlProtocol.read(input) ?: break
                try {
                    handle(message, injector, rawOut)
                } catch (t: Throwable) {
                    // One bad event must not end the session.
                }
            }
        } catch (e: Exception) {
            // The controller went away; exit quietly.
        }
        System.exit(0)
    }

    private fun handle(message: ControlProtocol.Message, injector: Injector, out: DataOutputStream) {
        val b = message.body
        when (message.type) {
            ControlProtocol.TOUCH -> {
                val action = b.readUnsignedByte()
                val pointer = b.readUnsignedByte()
                val x = b.readInt()
                val y = b.readInt()
                injector.touch(action, pointer, x, y, b.readInt())
            }
            ControlProtocol.KEY -> {
                val action = b.readUnsignedByte()
                val code = b.readInt()
                injector.key(action, code, b.readInt())
            }
            ControlProtocol.TEXT -> {
                val display = b.readInt()
                val bytes = ByteArray(message.remaining - 4)
                b.readFully(bytes)
                injector.text(String(bytes, Charsets.UTF_8), display)
            }
            ControlProtocol.SCROLL -> {
                val x = b.readInt()
                val y = b.readInt()
                val h = b.readFloat()
                val v = b.readFloat()
                injector.scroll(x, y, h, v, b.readInt())
            }
            ControlProtocol.PING -> {
                out.write(ControlProtocol.pong())
                out.flush()
            }
        }
    }
}
