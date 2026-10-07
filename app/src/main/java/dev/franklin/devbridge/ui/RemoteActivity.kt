package dev.franklin.devbridge.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import dev.franklin.devbridge.Session
import dev.franklin.devbridge.server.ControlProtocol
import dev.franklin.devbridge.server.InputServer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Remote screen for the connected phone.
 *
 * **Live** mode streams H.264 video (see [LiveStream]) and sends touches as they
 * happen. **Snapshots** mode polls `screencap` and is the fallback for phones
 * where screen recording is unavailable. Both work with a dead panel, and the
 * display fields let either drive a flip phone's cover screen.
 */
class RemoteActivity : AppCompatActivity() {

    private val ui = Handler(Looper.getMainLooper())
    private val input = Executors.newSingleThreadExecutor()
    private val capture = Executors.newSingleThreadExecutor()

    private lateinit var stage: FrameLayout
    private lateinit var screen: FrameLayout
    private lateinit var video: SurfaceView
    private lateinit var snapshot: ImageView
    private lateinit var status: TextView
    private lateinit var liveButton: Button
    private lateinit var logicalField: EditText
    private lateinit var physicalField: EditText

    @Volatile private var running = true
    @Volatile private var paused = false
    @Volatile private var live = true
    @Volatile private var snapshotLoop = false
    @Volatile private var physicalIdSnapshot = ""

    private var deviceW = 0
    private var deviceH = 0
    private var surfaceReady = false
    private var liveStream: LiveStream? = null
    private var motionSupported = false

    /** The helper running on the target phone; null while starting or if it could not start. */
    @Volatile private var helper: InputServer? = null

    // touch state
    private enum class Touch { NONE, PENDING, DRAGGING }
    private var touch = Touch.NONE
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private val pendingMove = AtomicReference<Pair<Int, Int>?>(null)
    private var lastDevicePoint: Pair<Int, Int>? = null
    private val startDrag = Runnable { if (touch == Touch.PENDING) beginDrag() }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Remote control"

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        status = label("Connecting to screen…", 12f).apply { setPadding(dp(8), dp(4), dp(8), dp(4)) }
        root.addView(status)

        stage = FrameLayout(this).apply { setBackgroundColor(0xFF000000.toInt()) }
        screen = FrameLayout(this)
        video = SurfaceView(this)
        snapshot = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_XY }
        val touchLayer = View(this).apply { setOnTouchListener { _, event -> handleTouch(event); true } }
        screen.addView(video, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        screen.addView(snapshot, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        screen.addView(touchLayer, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        stage.addView(screen, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        stage.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fit() }
        root.addView(stage, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        video.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) { surfaceReady = true; maybeStartLive() }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) { surfaceReady = false; stopLive() }
        })

        val keys = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        keys.addView(button("◀") { key(4) })           // BACK
        keys.addView(button("●") { key(3) })           // HOME
        keys.addView(button("▢") { key(187) })         // APP_SWITCH
        keys.addView(button("Power") { key(26) })
        keys.addView(button("Vol−") { key(25) })
        keys.addView(button("Vol+") { key(24) })
        keys.addView(button("Wake") { wake() })
        keys.addView(button("PIN") { pinDialog() })
        keys.addView(button("Text") { textDialog() })
        liveButton = button("Live: on") { toggleLive() }
        keys.addView(liveButton)
        keys.addView(button("↻") { restart() })
        keys.addView(button("Pause") { paused = !paused })
        root.addView(HorizontalScrollView(this).apply { addView(keys) })

        val displays = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(8), 0, dp(8), 0) }
        logicalField = EditText(this).apply { hint = "input display"; setText("0"); setSingleLine(); minEms = 4 }
        physicalField = EditText(this).apply { hint = "screen id (opt.)"; setSingleLine(); minEms = 6 }
        displays.addView(logicalField)
        displays.addView(physicalField)
        displays.addView(button("Detect") { detectDisplays() })
        displays.addView(button("Launch app") { launchDialog() })
        root.addView(HorizontalScrollView(this).apply { addView(displays) })

        setContentView(root)

        if (!Session.isConnected()) {
            status.text = "Not connected."
            running = false
            return
        }
        begin()
    }

    // --- startup / modes ---------------------------------------------------------------------------------

    private fun begin() {
        capture.execute {
            val connection = Session.connection ?: return@execute
            motionSupported = (connection.shell("getprop ro.build.version.sdk").trim().toIntOrNull() ?: 0) >= 31
            Thread({ startHelper(connection) }, "helper-start").apply { isDaemon = true; start() }
            val bitmap = grabSnapshot() ?: run {
                ui.post { status.text = "Could not read the screen. Is the phone unlocked or screen capture blocked?" }
                return@execute
            }
            ui.post {
                setDeviceSize(bitmap)
                snapshot.setImageBitmap(bitmap)
                if (live) maybeStartLive() else startSnapshotLoop()
            }
        }
    }

    /** Copies and launches the on-phone helper; without it, input falls back to `input` commands. */
    private fun startHelper(connection: dev.franklin.devbridge.adb.AdbConnection) {
        if (helper?.isAlive == true) return
        helper = null
        try {
            val started = InputServer.start(applicationContext, connection)
            helper = started
            ui.post { status.text = "Helper running on the phone (fast multi-touch input)" }
        } catch (e: Exception) {
            ui.post { status.text = "Helper unavailable (${e.message}); using slower shell input" }
        }
    }

    private fun restart() {
        stopLive()
        helper?.close()
        helper = null
        snapshotLoop = false
        deviceW = 0
        begin()
    }

    private fun toggleLive() {
        live = !live
        liveButton.text = if (live) "Live: on" else "Live: off"
        if (live) {
            snapshotLoop = false
            maybeStartLive()
        } else {
            stopLive()
            snapshot.visibility = View.VISIBLE
            startSnapshotLoop()
        }
    }

    private fun setDeviceSize(bitmap: Bitmap) {
        deviceW = bitmap.width
        deviceH = bitmap.height
        fit()
    }

    /** Letterboxes the picture to the phone's aspect ratio so touches map linearly. */
    private fun fit() {
        if (deviceW == 0 || stage.width == 0) return
        val scale = min(stage.width.toFloat() / deviceW, stage.height.toFloat() / deviceH)
        val w = (deviceW * scale).toInt()
        val h = (deviceH * scale).toInt()
        val lp = screen.layoutParams as FrameLayout.LayoutParams
        if (lp.width != w || lp.height != h) {
            lp.width = w
            lp.height = h
            screen.layoutParams = lp
        }
    }

    private fun maybeStartLive() {
        if (!live || !surfaceReady || deviceW == 0 || liveStream != null || !running) return
        val connection = Session.connection ?: return
        // Long side capped for speed; both sides rounded down to a multiple of 16, which encoders accept.
        val scale = min(1f, 1280f / max(deviceW, deviceH))
        val w = max(16, (deviceW * scale).toInt() / 16 * 16)
        val h = max(16, (deviceH * scale).toInt() / 16 * 16)
        status.text = "Starting live video…"
        liveStream = LiveStream(connection, video.holder.surface, w, h, physicalField.text.toString().trim(), object : LiveStream.Listener {
            override fun onLive(framesPerSecond: Int) {
                ui.post {
                    if (snapshot.visibility != View.INVISIBLE) snapshot.visibility = View.INVISIBLE
                    status.text = "Live ${w}×$h • $framesPerSecond fps • drag, tap or long-press the picture"
                }
            }

            override fun onFailed(reason: String) {
                ui.post {
                    liveStream = null
                    live = false
                    liveButton.text = "Live: off"
                    snapshot.visibility = View.VISIBLE
                    status.text = "Live video unavailable ($reason). Using snapshots."
                    startSnapshotLoop()
                }
            }
        }).also { it.start() }
    }

    private fun stopLive() {
        liveStream?.stop()
        liveStream = null
    }

    // --- snapshot fallback ---------------------------------------------------------------------------------

    private fun grabSnapshot(): Bitmap? {
        val connection = Session.connection ?: return null
        val physical = physicalIdSnapshot
        val command = if (physical.isEmpty()) "screencap -p" else "screencap -p -d $physical"
        val bytes = connection.shellBytes(command, 15_000)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    private fun startSnapshotLoop() {
        if (snapshotLoop || !running) return
        snapshotLoop = true
        physicalIdSnapshot = physicalField.text.toString().trim()
        capture.execute {
            var errors = 0
            while (running && snapshotLoop) {
                if (paused) { Thread.sleep(200); continue }
                val connection = Session.connection
                if (connection == null || connection.isClosed) {
                    ui.post { status.text = "Connection lost." }
                    break
                }
                try {
                    val started = System.currentTimeMillis()
                    val bitmap = grabSnapshot() ?: throw IllegalStateException("empty screenshot")
                    errors = 0
                    val ms = System.currentTimeMillis() - started
                    ui.post {
                        if (deviceW != bitmap.width || deviceH != bitmap.height) setDeviceSize(bitmap)
                        snapshot.setImageBitmap(bitmap)
                        physicalIdSnapshot = physicalField.text.toString().trim()
                        status.text = "Snapshots ${bitmap.width}×${bitmap.height} • $ms ms/frame • tap, drag or long-press"
                    }
                } catch (e: Exception) {
                    errors++
                    ui.post { status.text = "Screen capture failed: ${e.message}" }
                    try { Thread.sleep(if (errors > 3) 3000 else 1000) } catch (ie: InterruptedException) { break }
                }
            }
            snapshotLoop = false
        }
    }

    // --- touch -> device coordinates -----------------------------------------------------------------------

    private fun toDevice(x: Float, y: Float): Pair<Int, Int>? {
        if (deviceW == 0 || screen.width == 0 || screen.height == 0) return null
        val dx = (x / screen.width * deviceW).toInt().coerceIn(0, deviceW - 1)
        val dy = (y / screen.height * deviceH).toInt().coerceIn(0, deviceH - 1)
        return dx to dy
    }

    /** With the helper, every finger is forwarded as it moves, so taps, drags, pinch and long-press all behave natively. */
    private fun handleTouchWithHelper(helper: InputServer, event: MotionEvent): Boolean {
        helper.displayId = logicalField.text.toString().trim().toIntOrNull() ?: 0
        fun point(index: Int): Pair<Int, Int>? = toDevice(event.getX(index), event.getY(index))
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = event.actionIndex
                point(i)?.let { helper.touch(ControlProtocol.DOWN, event.getPointerId(i), it.first, it.second) }
            }
            MotionEvent.ACTION_MOVE -> for (i in 0 until event.pointerCount) {
                point(i)?.let { helper.touch(ControlProtocol.MOVE, event.getPointerId(i), it.first, it.second) }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val i = event.actionIndex
                point(i)?.let { helper.touch(ControlProtocol.UP, event.getPointerId(i), it.first, it.second) }
            }
            MotionEvent.ACTION_CANCEL -> for (i in 0 until event.pointerCount) {
                point(i)?.let { helper.touch(ControlProtocol.UP, event.getPointerId(i), it.first, it.second) }
            }
        }
        return true
    }

    private fun handleTouch(event: MotionEvent) {
        helper?.takeIf { it.isAlive }?.let {
            handleTouchWithHelper(it, event)
            return
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x; downY = event.y; downTime = event.eventTime
                touch = Touch.PENDING
                lastDevicePoint = toDevice(event.x, event.y)
                if (motionSupported) ui.postDelayed(startDrag, 250)
            }
            MotionEvent.ACTION_MOVE -> {
                lastDevicePoint = toDevice(event.x, event.y)
                if (touch == Touch.PENDING && motionSupported && hypot(event.x - downX, event.y - downY) > dp(8)) beginDrag()
                if (touch == Touch.DRAGGING) lastDevicePoint?.let { sendMove(it) }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                ui.removeCallbacks(startDrag)
                val end = toDevice(event.x, event.y) ?: lastDevicePoint
                when (touch) {
                    Touch.DRAGGING -> end?.let { sendMotion("UP", it) }
                    Touch.PENDING -> finishWithoutMotion(event, end)
                    Touch.NONE -> {}
                }
                touch = Touch.NONE
            }
        }
    }

    /** Phones without `input motionevent` (before Android 12) get a tap or a swipe when the finger lifts. */
    private fun finishWithoutMotion(event: MotionEvent, end: Pair<Int, Int>?) {
        val start = toDevice(downX, downY) ?: return
        val stop = end ?: start
        val held = event.eventTime - downTime
        val moved = hypot(event.x - downX, event.y - downY)
        val flag = displayFlag()
        when {
            moved > dp(12) -> exec("input ${flag}swipe ${start.first} ${start.second} ${stop.first} ${stop.second} ${max(100L, min(held, 1500L))}")
            held > 500 -> exec("input ${flag}swipe ${start.first} ${start.second} ${start.first} ${start.second} $held")
            else -> exec("input ${flag}tap ${start.first} ${start.second}")
        }
    }

    private fun beginDrag() {
        val start = toDevice(downX, downY) ?: return
        touch = Touch.DRAGGING
        sendMotion("DOWN", start)
    }

    private fun sendMotion(action: String, point: Pair<Int, Int>) {
        pendingMove.set(null)
        exec("input ${displayFlag()}motionevent $action ${point.first} ${point.second}")
    }

    /** Only the newest position is sent; older ones are skipped while the phone is still busy. */
    private fun sendMove(point: Pair<Int, Int>) {
        val idle = pendingMove.getAndSet(point) == null
        if (!idle) return
        input.execute {
            val next = pendingMove.getAndSet(null) ?: return@execute
            val connection = Session.connection ?: return@execute
            try { connection.shell("input ${displayFlag()}motionevent MOVE ${next.first} ${next.second}", 5_000) } catch (e: Exception) { /* next move retries */ }
        }
    }

    // --- actions ----------------------------------------------------------------------------------------------

    private fun displayFlag(): String {
        val id = logicalField.text.toString().trim().toIntOrNull() ?: 0
        return if (id == 0) "" else "-d $id "
    }

    private fun exec(command: String, then: ((String) -> Unit)? = null) {
        val connection = Session.connection ?: return
        input.execute {
            val out = try { connection.shell(command, 15_000) } catch (e: Exception) { "error: ${e.message}" }
            if (then != null) ui.post { then(out) }
        }
    }

    private fun key(code: Int) {
        val h = helper?.takeIf { it.isAlive }
        if (h != null) {
            h.displayId = logicalField.text.toString().trim().toIntOrNull() ?: 0
            h.key(code)
        } else {
            exec("input ${displayFlag()}keyevent $code")
        }
    }

    private fun wake() {
        exec("input keyevent 224")                          // WAKEUP
        val w = if (deviceW > 0) deviceW else 1080
        val h = if (deviceH > 0) deviceH else 2000
        ui.postDelayed({
            exec("input ${displayFlag()}swipe ${w / 2} ${h * 4 / 5} ${w / 2} ${h / 4} 300")   // dismiss a swipe lock
        }, 600)
    }

    private fun pinDialog() {
        val field = EditText(this).apply { hint = "PIN"; setSingleLine(); inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD }
        AlertDialog.Builder(this)
            .setTitle("Enter lock-screen PIN")
            .setMessage("Wakes the phone, then types the PIN and presses Enter. The PIN is sent to the phone and not stored.")
            .setView(field)
            .setPositiveButton("Send") { _, _ ->
                val pin = field.text.toString().filter { it.isDigit() }
                if (pin.isNotEmpty()) {
                    wake()
                    ui.postDelayed({ exec("input text $pin"); exec("input keyevent 66") }, 1500)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun textDialog() {
        val field = EditText(this).apply { hint = "Text to type"; setSingleLine() }
        AlertDialog.Builder(this)
            .setTitle("Type text")
            .setView(field)
            .setPositiveButton("Send") { _, _ ->
                val typed = field.text.toString()
                val h = helper?.takeIf { it.isAlive }
                if (typed.isNotEmpty() && h != null) {
                    h.displayId = logicalField.text.toString().trim().toIntOrNull() ?: 0
                    h.text(typed)
                } else {
                    val escaped = typed.replace("'", "'\\''").replace(" ", "%s")
                    if (escaped.isNotEmpty()) exec("input text '$escaped'")
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun detectDisplays() {
        exec("dumpsys SurfaceFlinger --display-id; echo ---; dumpsys display | grep -E 'Display [0-9]+:|mDisplayId=|mBaseDisplayInfo' | head -30") { out ->
            AlertDialog.Builder(this)
                .setTitle("Displays")
                .setMessage(
                    "Use a small number (0, 1…) in the first field for touches and apps. Use the long " +
                        "Display ID from the first block in the second field to show that screen, then tap ↻.\n\n" + out.trim().ifEmpty { "No output." },
                )
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    private fun launchDialog() {
        val field = EditText(this).apply { hint = "package, e.g. com.android.settings"; setSingleLine() }
        AlertDialog.Builder(this)
            .setTitle("Launch app on this display")
            .setView(field)
            .setPositiveButton("Launch") { _, _ ->
                val pkg = field.text.toString().trim()
                if (pkg.isEmpty()) return@setPositiveButton
                val display = logicalField.text.toString().trim().toIntOrNull() ?: 0
                exec("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER $pkg | tail -n 1") { component ->
                    val name = component.trim()
                    if (!name.contains('/')) {
                        status.text = "Could not find a launcher activity for $pkg"
                    } else {
                        exec("am start --display $display -n $name") { out -> status.text = out.trim().lineSequence().lastOrNull().orEmpty() }
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onDestroy() {
        running = false
        snapshotLoop = false
        ui.removeCallbacksAndMessages(null)
        stopLive()
        helper?.close()
        helper = null
        input.shutdownNow()
        capture.shutdownNow()
        super.onDestroy()
    }
}
