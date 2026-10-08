package dev.franklin.devbridge.aoa

import android.hardware.usb.UsbDeviceConnection

/** Sends AOA control transfers through an open USB connection. No interface needs claiming for these. */
class UsbControl(private val connection: UsbDeviceConnection) : ControlTransfer {
    override fun transfer(requestType: Int, request: Int, value: Int, index: Int, data: ByteArray?): Int =
        connection.controlTransfer(requestType, request, value, index, data, data?.size ?: 0, TIMEOUT_MS)

    fun close() = connection.close()

    private companion object {
        const val TIMEOUT_MS = 1000
    }
}

/** The one live input session, shared between the home screen that opens it and the touchpad screen that uses it. */
object AoaHolder {
    @Volatile var session: AoaSession? = null
        private set
    @Volatile var label: String = ""
        private set
    private var control: UsbControl? = null

    fun set(session: AoaSession, control: UsbControl, label: String) {
        close()
        this.session = session
        this.control = control
        this.label = label
    }

    fun close() {
        session?.close()
        session = null
        control?.close()
        control = null
        label = ""
    }
}
