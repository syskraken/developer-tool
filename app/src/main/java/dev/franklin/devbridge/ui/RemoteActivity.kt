package dev.franklin.devbridge.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import dev.franklin.devbridge.Session
import java.util.concurrent.Executors
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Mirrors the target's screen by polling `screencap` and sends taps, swipes and
 * keys back with `input`. Works when the physical panel is dead, because the
 * framebuffer is still composed. The display fields let it drive a flip phone's
 * cover screen instead of the main one.
 */
class RemoteActivity : AppCompatActivity() {

    private val ui = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val frames = Executors.newSingleThreadExecutor()

    private lateinit var image: ImageView
    private lateinit var status: TextView
    private lateinit var logicalField: EditText
    private lateinit var physicalField: EditText

    @Volatile private var running = true
    @Volatile private var paused = false
    private var frameWidth = 0
    private var frameHeight = 0

    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Remote control"

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        status = label("Connecting to screen…", 12f).apply { setPadding(dp(8), dp(4), dp(8), dp(4)) }
        root.addView(status)

        image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(0xFF000000.toInt())
            setOnTouchListener { _, event -> handleTouch(event); true }
        }
        root.addView(image, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

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
        keys.addView(button("Pause") { paused = !paused })
        root.addView(HorizontalScrollView(this).apply { addView(keys) })

        val displays = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(8), 0, dp(8), 0) }
        logicalField = EditText(this).apply { hint = "input display"; setText("0"); setSingleLine(); minEms = 4 }
        physicalField = EditText(this).apply { hint = "screencap id (opt.)"; setSingleLine(); minEms = 6 }
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
        startFrames()
    }

    private fun displayFlag(): String {
        val id = logicalField.text.toString().trim().toIntOrNull() ?: 0
        return if (id == 0) "" else "-d $id "
    }

    private fun startFrames() {
        frames.execute {
            var errors = 0
            while (running) {
                if (paused) { Thread.sleep(200); continue }
                val connection = Session.connection
                if (connection == null || connection.isClosed) {
                    ui.post { status.text = "Connection lost." }
                    break
                }
                val physical = physicalIdSnapshot
                val command = if (physical.isEmpty()) "screencap -p" else "screencap -p -d $physical"
                try {
                    val started = System.currentTimeMillis()
                    val bytes = connection.shellBytes(command, 15_000)
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bitmap == null) {
                        throw IllegalStateException(String(bytes, Charsets.UTF_8).trim().ifEmpty { "empty screenshot" })
                    }
                    errors = 0
                    val ms = System.currentTimeMillis() - started
                    ui.post { showFrame(bitmap, ms) }
                } catch (e: Exception) {
                    errors++
                    ui.post { status.text = "Screen capture failed: ${e.message}" }
                    try { Thread.sleep(if (errors > 3) 3000 else 1000) } catch (e: InterruptedException) { break }
                }
            }
        }
    }

    // Read on the UI thread, consumed by the frame thread.
    @Volatile private var physicalIdSnapshot = ""

    private fun showFrame(bitmap: Bitmap, ms: Long) {
        frameWidth = bitmap.width
        frameHeight = bitmap.height
        image.setImageBitmap(bitmap)
        physicalIdSnapshot = physicalField.text.toString().trim()
        status.text = "${bitmap.width}×${bitmap.height} • ${ms} ms/frame • tap, drag or long-press the picture"
    }

    // --- touch -> device coordinates -----------------------------------------------------------

    private fun toDevice(x: Float, y: Float): Pair<Int, Int>? {
        if (frameWidth == 0 || frameHeight == 0) return null
        val scale = min(image.width.toFloat() / frameWidth, image.height.toFloat() / frameHeight)
        val offsetX = (image.width - frameWidth * scale) / 2f
        val offsetY = (image.height - frameHeight * scale) / 2f
        val dx = ((x - offsetX) / scale).toInt()
        val dy = ((y - offsetY) / scale).toInt()
        if (dx !in 0 until frameWidth || dy !in 0 until frameHeight) return null
        return dx to dy
    }

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

    // --- actions ----------------------------------------------------------------------------------

    private fun exec(command: String, then: ((String) -> Unit)? = null) {
        val connection = Session.connection ?: return
        worker.execute {
            val out = try { connection.shell(command, 15_000) } catch (e: Exception) { "error: ${e.message}" }
            if (then != null) ui.post { then(out) }
        }
    }

    private fun key(code: Int) = exec("input ${displayFlag()}keyevent $code")

    private fun wake() {
        exec("input keyevent 224")                          // WAKEUP
        val w = if (frameWidth > 0) frameWidth else 1080
        val h = if (frameHeight > 0) frameHeight else 2000
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

    private fun detectDisplays() {
        exec("dumpsys SurfaceFlinger --display-id; echo ---; dumpsys display | grep -E 'Display [0-9]+:|mDisplayId=|mBaseDisplayInfo' | head -30") { out ->
            AlertDialog.Builder(this)
                .setTitle("Displays")
                .setMessage(
                    "Use a small number (0, 1…) in the first field for taps and apps. Use the long " +
                        "Display ID from the first block in the second field to capture that screen.\n\n" + out.trim().ifEmpty { "No output." },
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
        worker.shutdownNow()
        frames.shutdownNow()
        super.onDestroy()
    }
}
