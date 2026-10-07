package dev.franklin.devbridge

import android.content.Context
import dev.franklin.devbridge.adb.AdbConnection
import dev.franklin.devbridge.adb.AdbKey
import dev.franklin.devbridge.analysis.Shell
import java.io.File

/** The one live connection, shared by every screen. */
object Session {

    @Volatile var connection: AdbConnection? = null
        private set

    @Volatile var label: String = ""
        private set

    fun set(connection: AdbConnection, label: String) {
        this.connection?.close()
        this.connection = connection
        this.label = label
    }

    fun disconnect() {
        connection?.close()
        connection = null
        label = ""
    }

    fun isConnected(): Boolean = connection?.isClosed == false

    /** Shell bound to whatever is connected now; throws if nothing is. */
    fun shell(): Shell {
        val c = connection ?: throw IllegalStateException("Not connected")
        return Shell { command -> c.shell(command) }
    }

    // --- key storage ----------------------------------------------------------------------

    private fun keyFile(context: Context) = File(context.filesDir, "adbkey")

    /** Loads the saved key, creating and saving a new one on first use. */
    fun loadKey(context: Context): AdbKey {
        val file = keyFile(context)
        if (file.exists()) {
            try {
                return AdbKey.fromPem(file.readText())
            } catch (e: Exception) {
                // Corrupt key file: fall through and replace it.
            }
        }
        val key = AdbKey.generate()
        file.writeText(key.toPem())
        return key
    }

    fun importKey(context: Context, pem: String) {
        val key = AdbKey.fromPem(pem)       // validates before overwriting the working key
        keyFile(context).writeText(key.toPem())
    }
}
