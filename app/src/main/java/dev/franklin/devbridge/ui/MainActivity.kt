package dev.franklin.devbridge.ui

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
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
import dev.franklin.devbridge.aoa.AoaHolder
import dev.franklin.devbridge.aoa.AoaSession
import dev.franklin.devbridge.aoa.UsbControl
import dev.franklin.devbridge.adb.AdbException
import dev.franklin.devbridge.adb.TcpTransport
import dev.franklin.devbridge.adb.UsbTransport
import dev.franklin.devbridge.update.UpdateChecker
import dev.franklin.devbridge.update.Updater
import java.util.concurrent.Executors

/**
 * Connect screen. One column on phones; connection on the left and tools plus
 * updates on the right once the window is wide enough to afford two panes.
 */
class MainActivity : AppCompatActivity() {

    private val ui = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    private lateinit var status: TextView
    private lateinit var host: EditText
    private lateinit var tools: LinearLayout
    private lateinit var versionInfo: TextView
    private lateinit var updateButton: Button

    /** Set once a check finds something newer, so the button can install it directly. */
    private var availableUpdate: UpdateChecker.Release? = null
    private var renderedUpdateRevision = -1L

    private val updateProgress = object : Runnable {
        override fun run() {
            renderUpdateProgress()
            ui.postDelayed(this, 500)
        }
    }

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

    private val exportKey = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            val pem = Session.exportKey(this)
            contentResolver.openOutputStream(uri, "wt")?.use { it.write(pem.toByteArray(Charsets.UTF_8)) }
                ?: throw IllegalStateException("could not open the file")
            toast("Key saved. Keep that file private.")
        } catch (e: Exception) {
            toast("Export failed: ${e.message}")
        }
    }

    /** True while a USB permission request is for the touchpad/keyboard rather than for ADB. */
    private var pendingInput = false

    private val usbPermission = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device: UsbDevice? = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION") intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }
            if (device != null && intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                useUsb(device)
            } else {
                setStatus("USB permission was denied.")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "DevBridge"

        // --- connection pane -----------------------------------------------------------------------
        val connect = column()
        connect.addView(label("DevBridge", 26f, bold = true))
        status = label(if (Session.isConnected()) "Connected to ${Session.label}" else "Not connected", 15f)
        connect.spaced(status, 4)
        connect.spaced(
            label(
                "Controls another Android phone over ADB. On that phone, USB debugging must already be on. " +
                    "If its screen is broken and it has never trusted this app, import an adbkey from a computer it trusts.",
                13f,
            ),
            8,
        )

        connect.spaced(label("Wi-Fi / network", 16f, bold = true), 20)
        host = EditText(this).apply {
            hint = "192.168.1.20:5555"
            setSingleLine()
            setText(getPreferences(MODE_PRIVATE).getString("host", ""))
        }
        connect.addView(host)
        connect.addView(button("Connect over network") { connectTcp() })

        connect.spaced(label("USB cable (OTG)", 16f, bold = true), 20)
        connect.addView(button("Connect over USB") { chooseUsb(forInput = false) })
        connect.addView(button("Touchpad & keyboard (no USB debugging needed)") { chooseUsb(forInput = true) })
        connect.addView(button("Scripts") { startActivity(Intent(this, ScriptActivity::class.java)) })
        connect.spaced(
            label(
                "For a phone whose touch screen is dead: use this working phone as its mouse and keyboard to tap " +
                    "Allow on the USB debugging prompt. Scripts replays a saved sequence of key presses blind, " +
                    "for when you can't see the screen at all; connect Touchpad & keyboard first.",
                12f,
            ),
            4,
        )

        connect.spaced(label("Trusted key", 16f, bold = true), 20)
        connect.addView(button("Import adbkey…") { importKey.launch(arrayOf("*/*")) })
        connect.addView(button("Export key…") { confirmExport() })
        connect.spaced(
            label(
                "Phones that approved this key will not ask again. Export it before uninstalling so you can import it back afterwards.",
                12f,
            ),
            4,
        )

        // --- tools + updates pane ----------------------------------------------------------------------
        tools = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val side = column()
        side.addView(tools)
        side.spaced(label("Updates", 16f, bold = true), 24)
        versionInfo = label("Version ${currentVersion()}", 13f)
        side.spaced(versionInfo, 4)
        updateButton = button("Check for updates") { onUpdateButton() }
        side.addView(updateButton)

        val content = if (windowWidth() == WindowWidth.EXPANDED) {
            // Two panes, each scrolling on its own so a short window never hides the buttons.
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(scrolling(connect), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
                addView(scrolling(side), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
            }
        } else {
            // One column; the tools sit right under the connection controls, so drop the second
            // set of padding that would otherwise indent them further than everything above.
            side.setPadding(0, 0, 0, 0)
            connect.addView(side, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            scrolling(connect)
        }
        val maxWidth = if (windowWidth() == WindowWidth.EXPANDED) 1200 else 640
        content.applySystemBarPadding(includeTop = true)
        setContentView(centered(content, maxWidth))

        val filter = IntentFilter(ACTION_USB_PERMISSION)
        ContextCompat.registerReceiver(this, usbPermission, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        // Quiet check on launch: it only speaks up when something newer exists.
        runUpdateCheck(announce = false)
    }

    override fun onResume() {
        super.onResume()
        renderTools()
        ui.post(updateProgress)
    }

    override fun onPause() {
        ui.removeCallbacks(updateProgress)
        super.onPause()
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
        if (!Session.isConnected()) {
            tools.addView(label("Tools appear here once a phone is connected.", 13f))
            return
        }
        tools.addView(label("Connected: ${Session.label}", 16f, bold = true))
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

    private fun confirmExport() {
        AlertDialog.Builder(this)
            .setTitle("Save your key")
            .setMessage(
                "This file is the key every phone you approved trusts. Anyone who has it can connect to those phones " +
                    "(USB debugging must still be on). Save it somewhere private, not in a shared folder or chat.",
            )
            .setPositiveButton("Save…") { _, _ -> exportKey.launch("devbridge-adbkey.pem") }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // --- updates -------------------------------------------------------------------------------------------

    private fun currentVersion(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"
    } catch (e: Exception) {
        "unknown"
    }

    private fun onUpdateButton() {
        val release = availableUpdate
        if (release == null) {
            runUpdateCheck(announce = true)
            return
        }
        if (release.apkUrl == null) {
            // Nothing to install in-app; send the person to the release page.
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release.pageUrl)))
            return
        }
        if (!Updater.canInstall(this)) {
            AlertDialog.Builder(this)
                .setTitle("Allow installing updates")
                .setMessage(
                    "Android needs your permission before DevBridge can install an update. " +
                        "On the next screen, turn on “Allow from this source”, then come back and tap Update again.",
                )
                .setPositiveButton("Open settings") { _, _ -> startActivity(Updater.unknownSourcesSettings(this)) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }
        Updater.reset()
        worker.execute { Updater.downloadAndInstall(applicationContext, release) }
    }

    private fun runUpdateCheck(announce: Boolean) {
        if (announce) {
            updateButton.isEnabled = false
            updateButton.text = "Checking…"
        }
        worker.execute {
            val result = UpdateChecker.check(currentVersion())
            ui.post { applyUpdateResult(result, announce) }
        }
    }

    private fun applyUpdateResult(result: UpdateChecker.Result, announce: Boolean) {
        updateButton.isEnabled = true
        when (result) {
            is UpdateChecker.Result.Available -> {
                availableUpdate = result.release
                versionInfo.text = "Version ${currentVersion()} — ${result.release.version} is available"
                updateButton.text = if (result.release.apkUrl != null) "Update to ${result.release.version}" else "Download ${result.release.version}"
            }
            UpdateChecker.Result.UpToDate -> {
                availableUpdate = null
                updateButton.text = "Check for updates"
                if (announce) toast("DevBridge is up to date")
            }
            UpdateChecker.Result.NoReleases -> {
                updateButton.text = "Check for updates"
                if (announce) toast("No releases published yet")
            }
            is UpdateChecker.Result.Failed -> {
                updateButton.text = "Check for updates"
                if (announce) toast("Update check failed: ${result.reason}")
            }
        }
    }

    private fun renderUpdateProgress() {
        val revision = Updater.revision()
        if (revision == renderedUpdateRevision) return
        renderedUpdateRevision = revision
        val release = availableUpdate
        when (val s = Updater.status) {
            Updater.Status.Idle -> {}
            is Updater.Status.Downloading -> {
                updateButton.isEnabled = false
                updateButton.text = "Downloading… ${s.percent}%"
            }
            Updater.Status.Verifying -> updateButton.text = "Verifying…"
            Updater.Status.Installing -> updateButton.text = "Installing…"
            Updater.Status.Success -> {
                updateButton.isEnabled = true
                updateButton.text = "Updated"
            }
            is Updater.Status.Failed -> {
                updateButton.isEnabled = true
                updateButton.text = if (release != null) "Retry update to ${release.version}" else "Check for updates"
                AlertDialog.Builder(this)
                    .setTitle("Update failed")
                    .setMessage(s.reason)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
                Updater.reset()
            }
        }
    }

    // --- connecting ----------------------------------------------------------------------------------------

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
                finishConnect(AdbConnection(TcpTransport(name, port), Session.loadKey(this)), "$name:$port")
            } catch (e: Exception) {
                setStatus("Failed: ${e.message}")
            }
        }
    }

    private fun chooseUsb(forInput: Boolean) {
        pendingInput = forInput
        val manager = getSystemService(Context.USB_SERVICE) as UsbManager
        // Mouse/keyboard input works with any attached phone; ADB needs one that exposes the ADB interface.
        val devices = manager.deviceList.values.filter { forInput || UsbTransport.findInterface(it) != null }
        when {
            devices.isEmpty() -> {
                val seen = manager.deviceList.size
                toast(
                    if (seen == 0) "No USB device found. Use an OTG adapter, and make sure this phone is the USB host (see the README)."
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
        if (manager.hasPermission(device)) { useUsb(device); return }
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(packageName)
        val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        manager.requestPermission(device, PendingIntent.getBroadcast(this, 0, intent, flags))
    }

    private fun useUsb(device: UsbDevice) {
        if (pendingInput) startInput(device) else connectUsb(device)
    }

    private fun startInput(device: UsbDevice) {
        val startedAt = System.currentTimeMillis()
        fun elapsed() = "%.1fs".format((System.currentTimeMillis() - startedAt) / 1000.0)
        fun step(text: String) = setStatus("$text (${elapsed()} so far)")

        step("Opening the USB device…")
        worker.execute {
            val manager = getSystemService(Context.USB_SERVICE) as UsbManager
            val connection = manager.openDevice(device)
            if (connection == null) {
                setStatus("Could not open the USB device. (${elapsed()})")
                return@execute
            }
            step("USB device opened…")
            val control = UsbControl(connection)
            val session = AoaSession(control)
            try {
                session.start(onStep = { step(it) })
            } catch (e: Exception) {
                control.close()
                setStatus("Touchpad unavailable after ${elapsed()}: ${e.message}")
                return@execute
            }
            val name = device.productName ?: "USB device"
            AoaHolder.set(session, control, name)
            ui.post {
                setStatus("Touchpad & keyboard ready for $name (took ${elapsed()})")
                startActivity(Intent(this, TouchpadActivity::class.java))
            }
        }
    }

    private fun connectUsb(device: UsbDevice) {
        setStatus("Connecting over USB…")
        worker.execute {
            try {
                val manager = getSystemService(Context.USB_SERVICE) as UsbManager
                val transport = UsbTransport.open(manager, device)
                finishConnect(AdbConnection(transport, Session.loadKey(this)), device.productName ?: "USB")
            } catch (e: Exception) {
                setStatus("Failed: ${e.message}")
            }
        }
    }

    private fun finishConnect(connection: AdbConnection, name: String) {
        try {
            connection.connect(authTimeoutMs = 90_000, onStep = { setStatus(it) }) {
                setStatus("Waiting for approval: on the phone, tick \"Always allow\" and tap Allow. If no prompt shows, make sure its screen is on and unlocked.")
            }
        } catch (e: AdbException) {
            setStatus("Failed: ${e.message}")
            return
        }
        Session.set(connection, name)
        ui.post {
            setStatus("Connected to $name")
            renderTools()
        }
    }

    private companion object {
        const val ACTION_USB_PERMISSION = "dev.franklin.devbridge.USB_PERMISSION"
    }
}
