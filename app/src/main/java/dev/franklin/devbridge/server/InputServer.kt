package dev.franklin.devbridge.server

import android.content.Context
import androidx.core.content.pm.PackageInfoCompat
import dev.franklin.devbridge.adb.AdbConnection
import dev.franklin.devbridge.adb.AdbException
import dev.franklin.devbridge.adb.AdbSync
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * The controller's end of the helper. [start] copies this app's APK to the
 * target phone, launches [Server] from it with `app_process`, and waits for it
 * to say hello. After that touches, keys and text travel as small binary
 * messages instead of one `input` process per event.
 */
class InputServer private constructor(
    private val stream: AdbConnection.AdbStream,
    val targetSdk: Int,
) : Closeable {

    private val sender = Executors.newSingleThreadExecutor()
    private val latestMove = ConcurrentHashMap<Int, Pair<Int, Int>>()

    @Volatile var displayId = 0
    @Volatile var isAlive = true
        private set

    init {
        // Replies are tiny; reading them also tells us promptly when the helper dies.
        Thread({
            try {
                while (isAlive) {
                    stream.read(1000)
                    if (stream.eof) break
                }
            } catch (e: Exception) {
                // Stream torn down.
            }
            isAlive = false
        }, "helper-reader").apply { isDaemon = true; start() }
    }

    fun touch(action: Int, pointerId: Int, x: Int, y: Int) {
        if (action == ControlProtocol.MOVE) {
            // Only the newest position matters; skip stale ones if the link is busy.
            if (latestMove.put(pointerId, x to y) != null) return
            post {
                val p = latestMove.remove(pointerId) ?: return@post null
                ControlProtocol.touch(ControlProtocol.MOVE, pointerId, p.first, p.second, displayId)
            }
        } else {
            latestMove.remove(pointerId)
            post { ControlProtocol.touch(action, pointerId, x, y, displayId) }
        }
    }

    fun key(keyCode: Int) {
        post { ControlProtocol.key(ControlProtocol.DOWN, keyCode, displayId) }
        post { ControlProtocol.key(ControlProtocol.UP, keyCode, displayId) }
    }

    fun text(value: String) = post { ControlProtocol.text(value, displayId) }

    fun scroll(x: Int, y: Int, horizontal: Float, vertical: Float) =
        post { ControlProtocol.scroll(x, y, horizontal, vertical, displayId) }

    private fun post(build: () -> ByteArray?) {
        if (!isAlive) return
        sender.execute {
            val bytes = build() ?: return@execute
            try {
                stream.write(bytes)
            } catch (e: Exception) {
                isAlive = false
            }
        }
    }

    override fun close() {
        isAlive = false
        sender.shutdownNow()
        stream.close()
    }

    companion object {
        private const val REMOTE_DIR = "/data/local/tmp"

        /** Throws [AdbException] with the reason if the helper cannot be started. */
        fun start(context: Context, connection: AdbConnection): InputServer {
            val apk = File(context.applicationInfo.sourceDir)
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            val tag = "${PackageInfoCompat.getLongVersionCode(info)}-${info.lastUpdateTime}"
            val remote = "$REMOTE_DIR/devbridge-server-$tag.apk"

            if (connection.shell("ls $remote").trim() != remote) {
                connection.shell("rm -f $REMOTE_DIR/devbridge-server-*.apk")
                AdbSync.push(connection, apk.readBytes(), remote)
            }

            val stream = connection.open("exec:CLASSPATH=$remote app_process /system/bin ${Server::class.java.name}", 10_000)
            try {
                return handshake(stream)
            } catch (e: Exception) {
                stream.close()
                throw e
            }
        }

        private fun handshake(stream: AdbConnection.AdbStream): InputServer {
            val buffer = ByteArrayOutputStream()
            val magic = ControlProtocol.MAGIC.toByteArray(Charsets.US_ASCII)
            val deadline = System.currentTimeMillis() + 12_000

            while (true) {
                val wait = deadline - System.currentTimeMillis()
                if (wait <= 0) throw AdbException("Helper did not start in time")
                val chunk = stream.read(wait) ?: continue
                if (stream.eof) {
                    val text = String(buffer.toByteArray(), Charsets.UTF_8).trim().takeLast(300)
                    throw AdbException("Helper exited: ${text.ifEmpty { "no output" }}")
                }
                buffer.write(chunk)

                val bytes = buffer.toByteArray()
                val at = indexOf(bytes, magic)
                if (at < 0) continue
                val start = at + magic.size
                if (bytes.size < start + 3) continue
                val length = ((bytes[start].toInt() and 0xFF) shl 8) or (bytes[start + 1].toInt() and 0xFF)
                if (bytes.size < start + 2 + length) continue
                val type = bytes[start + 2].toInt() and 0xFF
                val body = bytes.copyOfRange(start + 3, start + 2 + length)
                return when (type) {
                    ControlProtocol.HELLO -> {
                        val sdk = if (body.size >= 4) java.nio.ByteBuffer.wrap(body).int else 0
                        InputServer(stream, sdk)
                    }
                    ControlProtocol.ERROR -> throw AdbException(String(body, Charsets.UTF_8))
                    else -> throw AdbException("Unexpected helper reply")
                }
            }
        }

        private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
            outer@ for (i in 0..haystack.size - needle.size) {
                for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
                return i
            }
            return -1
        }
    }
}
