package dev.franklin.devbridge.ui

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import dev.franklin.devbridge.Session
import dev.franklin.devbridge.adb.AdbConnection
import dev.franklin.devbridge.adb.AdbException
import dev.franklin.devbridge.adb.TcpTransport
import dev.franklin.devbridge.adb.UsbTransport
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private val ui = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    private lateinit var status: TextView
    private lateinit var host: EditText
    private lateinit var tools: LinearLayout

    private val importKey = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty()
            Session.importKey(this, text)
            toast("Key imported. Reconnect to use it.")
        } catch (e: Exception) {
            toast("Import failed: ${e.message}")
        }
    }

    private val usbPermission = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device: UsbDevice? = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION") intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }
            if (device != null && intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                connectUsb(device)
            } else {
                setStatus("USB permission was denied.")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "DevBridge"

        val root = column()
        root.addView(label("DevBridge", 26f, bold = true))
        status = label("Not connected", 15f)
        root.spaced(status, 4)
        root.spaced(
            label(
                "Controls another Android phone over ADB. On that phone, USB debugging must already be on. " +
                    "If its screen is broken and it has never trusted this app, import an adbkey from a computer it trusts.",
                13f,
            ),
            8,
        )

        root.spaced(label("Wi-Fi / network", 16f, bold = true), 20)
        host = EditText(this).apply {
            hint = "192.168.1.20:5555"
            setSingleLine()
            setText(getPreferences(MODE_PRIVATE).getString("host", ""))
        }
        root.addView(host)
        root.addView(button("Connect over network") { connectTcp() })

        root.spaced(label("USB cable (OTG)", 16f, bold = true), 20)
        root.addView(button("Connect over USB") { chooseUsb() })

        root.spaced(label("Trusted key", 16f, bold = true), 20)
        root.addView(button("Import adbkey…") { importKey.launch(arrayOf("*/*")) })

        tools = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(tools)

        setContentView(scrolling(root))

        val filter = IntentFilter(ACTION_USB_PERMISSION)
        ContextCompat.registerReceiver(this, usbPermission, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onResume() {
        super.onResume()
        renderTools()
    }

    override fun onDestroy() {
        unregisterReceiver(usbPermission)
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun setStatus(text: String) = ui.post { status.text = text }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    private fun renderTools() {
        tools.removeAllViews()
        if (!Session.isConnected()) return
        tools.spaced(label("Connected: ${Session.label}", 16f, bold = true), 24)
        tools.addView(button("Remote control") { open(RemoteActivity::class.java) })
        tools.addView(button("Hardware check") { report(ReportActivity.MODE_HARDWARE) })
        tools.addView(button("Permission analysis") { report(ReportActivity.MODE_PERMISSIONS) })
        tools.addView(button("Hidden app audit") { report(ReportActivity.MODE_HIDDEN) })
        tools.addView(button("ADB shell") { open(ShellActivity::class.java) })
        tools.addView(button("Disconnect") {
            Session.disconnect()
            setStatus("Disconnected")
            renderTools()
        })
    }

    private fun open(cls: Class<*>) = startActivity(Intent(this, cls))

    private fun report(mode: String) =
        startActivity(Intent(this, ReportActivity::class.java).putExtra(ReportActivity.EXTRA_MODE, mode))

    // --- connecting --------------------------------------------------------------------------------

    private fun connectTcp() {
        val text = host.text.toString().trim()
        if (text.isEmpty()) { toast("Enter the phone's address"); return }
        val name = text.substringBeforeLast(':', text)
        val port = if (text.contains(':')) text.substringAfterLast(':').toIntOrNull() else 5555
        if (port == null) { toast("Bad port"); return }
        getPreferences(MODE_PRIVATE).edit().putString("host", text).apply()

        setStatus("Connecting to $name:$port…")
        worker.execute {
            try {
                finish(AdbConnection(TcpTransport(name, port), Session.loadKey(this)), "$name:$port")
            } catch (e: Exception) {
                setStatus("Failed: ${e.message}")
            }
        }
    }

    private fun chooseUsb() {
        val manager = getSystemService(Context.USB_SERVICE) as UsbManager
        val devices = manager.deviceList.values.filter { UsbTransport.findInterface(it) != null }
        when {
            devices.isEmpty() -> {
                val seen = manager.deviceList.size
                toast(
                    if (seen == 0) "No USB device found. Use an OTG adapter, and turn on USB debugging on the other phone."
                    else "$seen USB device(s) found but none exposes ADB. Turn on USB debugging on the other phone.",
                )
            }
            devices.size == 1 -> requestUsb(manager, devices[0])
            else -> AlertDialog.Builder(this)
                .setTitle("Choose a device")
                .setItems(devices.map { "${it.productName ?: it.deviceName}" }.toTypedArray()) { _, i -> requestUsb(manager, devices[i]) }
                .show()
        }
    }

    private fun requestUsb(manager: UsbManager, device: UsbDevice) {
        if (manager.hasPermission(device)) { connectUsb(device); return }
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(packageName)
        val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        manager.requestPermission(device, PendingIntent.getBroadcast(this, 0, intent, flags))
    }

    private fun connectUsb(device: UsbDevice) {
        setStatus("Connecting over USB…")
        worker.execute {
            try {
                val manager = getSystemService(Context.USB_SERVICE) as UsbManager
                val transport = UsbTransport.open(manager, device)
                finish(AdbConnection(transport, Session.loadKey(this)), device.productName ?: "USB")
            } catch (e: Exception) {
                setStatus("Failed: ${e.message}")
            }
        }
    }

    private fun finish(connection: AdbConnection, label: String) {
        try {
            connection.connect(authTimeoutMs = 90_000) {
                setStatus("Approve this computer on the phone (tick \"Always allow\" and tap Allow)…")
            }
        } catch (e: AdbException) {
            setStatus("Failed: ${e.message}")
            return
        }
        Session.set(connection, label)
        ui.post {
            setStatus("Connected to $label")
            renderTools()
        }
    }

    private companion object {
        const val ACTION_USB_PERMISSION = "dev.franklin.devbridge.USB_PERMISSION"
    }
}
