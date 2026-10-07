package dev.franklin.devbridge.adb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import java.io.IOException

/**
 * ADB over USB host mode (OTG): this phone is the host and the target phone,
 * with USB debugging on, is the peripheral.
 */
class UsbTransport private constructor(
    private val connection: UsbDeviceConnection,
    private val iface: UsbInterface,
    private val bulkIn: UsbEndpoint,
    private val bulkOut: UsbEndpoint,
) : AdbTransport {

    @Volatile private var closed = false

    override fun readFully(buf: ByteArray, off: Int, len: Int, timeoutMs: Int) {
        val deadline = if (timeoutMs == 0) Long.MAX_VALUE else System.currentTimeMillis() + timeoutMs
        var done = 0
        while (done < len) {
            if (closed) throw IOException("USB closed")
            val n = connection.bulkTransfer(bulkIn, buf, off + done, len - done, POLL_MS)
            if (n > 0) {
                done += n
            } else if (System.currentTimeMillis() > deadline) {
                throw java.net.SocketTimeoutException("USB read timed out")
            }
        }
    }

    override fun write(buf: ByteArray, off: Int, len: Int) {
        var done = 0
        while (done < len) {
            if (closed) throw IOException("USB closed")
            val n = connection.bulkTransfer(bulkOut, buf, off + done, len - done, WRITE_MS)
            if (n < 0) throw IOException("USB write failed")
            done += n
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            connection.releaseInterface(iface)
            connection.close()
        } catch (e: Exception) {
            // Device already unplugged.
        }
    }

    companion object {
        private const val POLL_MS = 1000
        private const val WRITE_MS = 5000

        /** ADB's USB interface: vendor-specific class, subclass 0x42, protocol 1. */
        fun findInterface(device: UsbDevice): UsbInterface? {
            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                if (iface.interfaceClass == 0xFF && iface.interfaceSubclass == 0x42 && iface.interfaceProtocol == 1) return iface
            }
            return null
        }

        fun open(manager: UsbManager, device: UsbDevice): UsbTransport {
            val iface = findInterface(device) ?: throw AdbException("${device.deviceName} has no ADB interface (is USB debugging on?)")
            var bulkIn: UsbEndpoint? = null
            var bulkOut: UsbEndpoint? = null
            for (i in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(i)
                if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (ep.direction == UsbConstants.USB_DIR_IN) bulkIn = ep else bulkOut = ep
            }
            if (bulkIn == null || bulkOut == null) throw AdbException("ADB interface is missing its bulk endpoints")

            val connection = manager.openDevice(device) ?: throw AdbException("Could not open the USB device (permission?)")
            if (!connection.claimInterface(iface, true)) {
                connection.close()
                throw AdbException("Could not claim the ADB interface; another app may be using it")
            }
            return UsbTransport(connection, iface, bulkIn, bulkOut)
        }
    }
}
