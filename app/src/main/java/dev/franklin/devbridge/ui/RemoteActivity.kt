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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import dev.franklin.devbridge.Session
import dev.franklin.devbridge.analysis.DisplayParser
import java.util.concurrent.Executors
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

    private lateinit var root: LinearLayout
    private lateinit var panel: MaxHeightScrollView
    private lateinit var stage: FrameLayout
    private lateinit var screen: FrameLayout
    private lateinit var video: SurfaceView
    private lateinit var snapshot: ImageView
    private lateinit var status: TextView
    private lateinit var liveButton: Button
    private lateinit var controlsToggle: Button
    private lateinit var logicalField: EditText
    private lateinit var physicalField: EditText

    @Volatile private var running = true
    @Volatile private var paused = false
    // Snapshots are the default: they work on every phone. Live video is opt-in because it depends on the phone's screen recorder.
    @Volatile private var live = false
    @Volatile private var snapshotLoop = false
    @Volatile private var physicalIdSnapshot = ""

    private var deviceW = 0
    private var deviceH = 0
    private var surfaceReady = false
    private var liveStream: LiveStream? = null

    // touch state
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Remote control"

        status = label("Connecting to screen…", 12f).apply {
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0x99000000.toInt())
            setTextIsSelectable(false)
        }

        stage = FrameLayout(this).apply { setBackgroundColor(0xFF000000.toInt()) }
        screen = FrameLayout(this)
        video = SurfaceView(this)
        snapshot = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_XY }
        val touchLayer = View(this).apply { setOnTouchListener { _, event -> handleTouch(event); true } }
        screen.addView(video, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        screen.addView(snapshot, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        screen.addView(touchLayer, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        stage.addView(screen, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        // Always-visible handle that folds the controls away to give the picture the whole window.
        controlsToggle = Button(this).apply {
            isAllCaps = false
            textSize = 12f
            setBackgroundColor(0xCC202020.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setOnClickListener { setControlsVisible(panel.visibility != View.VISIBLE) }
        }

        // Over the picture rather than beside it, so the picture view never has to move when the layout changes.
        stage.addView(status, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        stage.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fit() }

        video.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) { surfaceReady = true; maybeStartLive() }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) { surfaceReady = false; stopLive() }
        })

        // Controls wrap onto as many rows as the width allows instead of scrolling sideways.
        val keys = wrapRow()
        keys.addView(button("◀") { key(4) })           // BACK
        keys.addView(button("●") { key(3) })           // HOME
        keys.addView(button("▢") { key(187) })         // APP_SWITCH
        keys.addView(button("Power") { key(26) })
        keys.addView(button("Vol−") { key(25) })
        keys.addView(button("Vol+") { key(24) })
        keys.addView(button("Wake") { wake() })
        keys.addView(button("PIN") { pinDialog() })
        keys.addView(button("Text") { textDialog() })
        liveButton = button("Live video (beta): off") { toggleLive() }
        keys.addView(liveButton)
        keys.addView(button("↻") { restart() })
        keys.addView(button("Pause") { paused = !paused })

        logicalField = EditText(this).apply { hint = "input display"; setText("0"); setSingleLine(); minEms = 4 }
        physicalField = EditText(this).apply { hint = "screen id (opt.)"; setSingleLine(); minEms = 6 }
        val displays = wrapRow()
        displays.addView(logicalField)
        displays.addView(physicalField)
        displays.addView(button("Select display") { pickDisplay() })
        displays.addView(button("Launch app") { launchDialog() })

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
            addView(keys)
            addView(displays)
        }
        panel = MaxHeightScrollView(this) { (resources.configuration.screenHeightDp * resources.displayMetrics.density * 0.42f).toInt() }.apply { addView(controls) }

        stage.addView(
            controlsToggle,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END)
                .apply { setMargins(dp(8), dp(8), dp(8), dp(8)) },
        )

        root = LinearLayout(this)
        root.addView(stage)
        root.addView(panel)
        root.applySystemBarPadding(includeTop = true)
        setContentView(root)
        arrange()
        setControlsVisible(!getPreferences(MODE_PRIVATE).getBoolean("controlsCollapsed", false))

        if (!Session.isConnected()) {
            status.text = "Not connected."
            running = false
            return
        }
        begin()
    }

    /**
     * Portrait: picture filling the screen with the controls underneath.
     * Landscape or wide: picture on the left, controls in a side panel.
     * Runs again on every size change, so rotating, folding or resizing the
     * window re-flows the screen. Nothing is re-parented, so the video surface
     * and the live stream carry on untouched.
     */
    private fun arrange() {
        val sideBySide = isLandscapeWindow()
        if (sideBySide) {
            root.orientation = LinearLayout.HORIZONTAL
            stage.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            val width = (resources.configuration.screenWidthDp * 0.34f).toInt().coerceIn(240, 360)
            panel.layoutParams = LinearLayout.LayoutParams(dp(width), ViewGroup.LayoutParams.MATCH_PARENT)
        } else {
            root.orientation = LinearLayout.VERTICAL
            stage.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            panel.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun setControlsVisible(visible: Boolean) {
        panel.visibility = if (visible) View.VISIBLE else View.GONE
        controlsToggle.text = if (visible) "Hide controls ▾" else "Controls ▴"
        getPreferences(MODE_PRIVATE).edit().putBoolean("controlsCollapsed", !visible).apply()
        stage.post { fit() }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        arrange()
    }

    // --- startup / modes ---------------------------------------------------------------------------------

    private fun begin() {
        capture.execute {
            val connection = Session.connection ?: return@execute
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

    private fun restart() {
        stopLive()
        snapshotLoop = false
        deviceW = 0
        begin()
    }

    private fun toggleLive() {
        live = !live
        liveButton.text = if (live) "Live video (beta): on" else "Live video (beta): off"
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
                    liveButton.text = "Live video (beta): off"
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

    /**
     * One `input` command per gesture, sent when the finger lifts. Android plays a swipe back as a
     * single smooth, correctly timed drag, so scrolling and flicks behave like a real finger. Sending
     * the drag live instead means one `input` process per move event: each takes a few hundred
     * milliseconds to start, so the phone sees a slow, jerky, gappy touch that scrolls poorly.
     */
    private fun handleTouch(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = event.x; downY = event.y; downTime = event.eventTime }
            MotionEvent.ACTION_UP -> {
                val start = toDevice(downX, downY) ?: return
                val end = toDevice(event.x, event.y) ?: return
                val held = event.eventTime - downTime
                val moved = hypot(event.x - downX, event.y - downY)
                val flag = displayFlag()
                when {
                    moved > dp(12) -> exec("input ${flag}swipe ${start.first} ${start.second} ${end.first} ${end.second} ${max(100L, min(held, 1500L))}")
                    held > 500 -> exec("input ${flag}swipe ${start.first} ${start.second} ${start.first} ${start.second} $held")
                    else -> exec("input ${flag}tap ${start.first} ${start.second}")
                }
            }
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

    private fun key(code: Int) = exec("input ${displayFlag()}keyevent $code")

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
                val escaped = field.text.toString().replace("'", "'\\''").replace(" ", "%s")
                if (escaped.isNotEmpty()) exec("input text '$escaped'")
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Lists the phone's screens and switches to the one you choose, so there is no need to type ids. */
    private fun pickDisplay() {
        exec("dumpsys display") { displayDump ->
            exec("dumpsys SurfaceFlinger --display-id") { flingerDump ->
                val choices = try { DisplayParser.choices(displayDump, flingerDump) } catch (e: Exception) { emptyList() }
                if (choices.isEmpty()) {
                    AlertDialog.Builder(this)
                        .setTitle("No displays found")
                        .setMessage(
                            "This phone did not report its screens in a form DevBridge understands. " +
                                "You can still type the display numbers in the two boxes and tap ↻.\n\n" +
                                (flingerDump.trim() + "\n" + displayDump.trim().take(1500)).trim(),
                        )
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                    return@exec
                }
                AlertDialog.Builder(this)
                    .setTitle("Select display")
                    .setItems(choices.map { it.label() }.toTypedArray()) { _, i -> useDisplay(choices[i]) }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
    }

    private fun useDisplay(choice: DisplayParser.Choice) {
        logicalField.setText(choice.logicalId.toString())
        physicalField.setText(choice.physicalId.orEmpty())
        // The very next capture must already use the new screen, not wait for the loop to read the field.
        physicalIdSnapshot = choice.physicalId.orEmpty()
        status.text = "Switching to ${choice.name}…"
        restart()
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
        input.shutdownNow()
        capture.shutdownNow()
        super.onDestroy()
    }
}
