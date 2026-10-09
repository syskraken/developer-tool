package dev.franklin.devbridge.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import dev.franklin.devbridge.aoa.Aoa
import dev.franklin.devbridge.aoa.AoaHolder
import dev.franklin.devbridge.aoa.AoaSession
import dev.franklin.devbridge.aoa.HidKeys
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Acts as a mouse and keyboard for the plugged-in phone over USB (Android Open Accessory), with no USB
 * debugging and no approved key. It exists for the moment a phone with a dead touch screen is asking
 * "Allow USB debugging?" and nothing else can answer it.
 */
class TouchpadActivity : AppCompatActivity() {

    private val ui = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    private lateinit var status: TextView
    private lateinit var pad: TouchpadView
    private lateinit var root: LinearLayout
    private lateinit var panel: MaxHeightScrollView
    private lateinit var holdButton: Button

    private val pendingDx = AtomicInteger()
    private val pendingDy = AtomicInteger()
    private val moveQueued = AtomicBoolean(false)
    private var holding = false

    private fun session(): AoaSession? = AoaHolder.session

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Touchpad & keyboard"

        status = label(
            if (session() != null) "Controlling ${AoaHolder.label}. Move the pointer onto Allow and tap, or press Tab / Space / Enter." else "Not connected.",
            13f,
        ).apply { setPadding(dp(12), dp(8), dp(12), dp(4)) }

        pad = TouchpadView(this).apply {
            onMove = { dx, dy -> queueMove(dx, dy) }
            onClick = { withSession { it.click(Aoa.BUTTON_LEFT) } }
            onRightClick = { withSession { it.click(Aoa.BUTTON_RIGHT) } }
            onScroll = { steps -> withSession { it.scroll(steps) } }
        }

        val clicks = wrapRow()
        clicks.addView(button("Left click") { withSession { it.click(Aoa.BUTTON_LEFT) } })
        clicks.addView(button("Right click") { withSession { it.click(Aoa.BUTTON_RIGHT) } })
        holdButton = button("Hold: off") { toggleHold() }
        clicks.addView(holdButton)

        val keys = wrapRow()
        keys.addView(keyButton("Tab", HidKeys.TAB))
        keys.addView(keyButton("⇧ Tab", HidKeys.TAB, HidKeys.MOD_SHIFT))
        keys.addView(keyButton("Space", HidKeys.SPACE))
        keys.addView(keyButton("Enter", HidKeys.ENTER))
        keys.addView(keyButton("Esc", HidKeys.ESCAPE))
        keys.addView(keyButton("←", HidKeys.LEFT))
        keys.addView(keyButton("↑", HidKeys.UP))
        keys.addView(keyButton("↓", HidKeys.DOWN))
        keys.addView(keyButton("→", HidKeys.RIGHT))
        keys.addView(keyButton("⌫", HidKeys.BACKSPACE))

        val field = EditText(this).apply { hint = "Text or PIN to type"; setSingleLine() }
        val typing = wrapRow()
        typing.addView(button("Type") { type(field.text.toString(), enter = false) })
        typing.addView(button("Type + Enter") { type(field.text.toString(), enter = true) })

        val speed = SeekBar(this).apply {
            max = 8
            progress = 3
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) { pad.gain = 0.5f + value * 0.5f }
                override fun onStartTrackingTouch(bar: SeekBar) {}
                override fun onStopTrackingTouch(bar: SeekBar) {}
            })
        }
        pad.gain = 0.5f + speed.progress * 0.5f

        val controls = column(8).apply {
            addView(clicks)
            addView(keys)
            addView(field)
            addView(typing)
            spaced(label("Pointer speed", 12f), 8)
            addView(speed)
            spaced(
                label(
                    "To approve the USB debugging prompt: tap Allow with the pointer, or press Tab until it is " +
                        "highlighted, then Enter. Tick “Always allow” so it never asks again. Afterwards go back and " +
                        "use Connect over USB.",
                    12f,
                ),
                8,
            )
            addView(button("Scripts") { startActivity(android.content.Intent(this@TouchpadActivity, ScriptActivity::class.java)) })
            addView(button("Done") { finish() })
        }
        panel = MaxHeightScrollView(this) { (resources.configuration.screenHeightDp * resources.displayMetrics.density * 0.5f).toInt() }
            .apply { addView(controls) }

        root = LinearLayout(this)
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(status)
            addView(pad, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { setMargins(dp(8), dp(4), dp(8), dp(8)) })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        root.addView(panel)
        root.applySystemBarPadding(includeTop = true)
        setContentView(root)
        arrange()
    }

    /** Portrait stacks the pad over the controls; landscape puts the controls in a side panel. */
    private fun arrange() {
        val firstChild = root.getChildAt(0)
        if (isLandscapeWindow()) {
            root.orientation = LinearLayout.HORIZONTAL
            firstChild.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            val width = (resources.configuration.screenWidthDp * 0.4f).toInt().coerceIn(260, 400)
            panel.layoutParams = LinearLayout.LayoutParams(dp(width), ViewGroup.LayoutParams.MATCH_PARENT)
        } else {
            root.orientation = LinearLayout.VERTICAL
            firstChild.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            panel.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        arrange()
    }

    private fun keyButton(text: String, usage: Int, modifiers: Int = 0) =
        button(text) { withSession { it.tapKey(usage, modifiers) } }

    private fun toggleHold() {
        holding = !holding
        holdButton.text = if (holding) "Hold: on" else "Hold: off"
        withSession { it.setButtons(if (holding) Aoa.BUTTON_LEFT else 0) }
    }

    private fun type(text: String, enter: Boolean) {
        if (text.isEmpty() && !enter) return
        withSession { s ->
            val skipped = s.typeText(text)
            if (enter) s.tapKey(HidKeys.ENTER)
            if (skipped > 0) ui.post { status.text = "$skipped character(s) could not be typed (US keyboard only)." }
        }
    }

    /** Pointer motion is merged while the cable is busy, so the cursor stays smooth instead of lagging behind. */
    private fun queueMove(dx: Int, dy: Int) {
        pendingDx.addAndGet(dx)
        pendingDy.addAndGet(dy)
        if (moveQueued.compareAndSet(false, true)) {
            withSession {
                moveQueued.set(false)
                val x = pendingDx.getAndSet(0)
                val y = pendingDy.getAndSet(0)
                if (x != 0 || y != 0) it.moveMouse(x, y)
            }
        }
    }

    private fun withSession(action: (AoaSession) -> Unit) {
        val s = session()
        if (s == null) {
            status.text = "Not connected."
            return
        }
        io.execute {
            try {
                action(s)
            } catch (e: Exception) {
                ui.post { status.text = "USB input stopped: ${e.message}" }
            }
        }
    }

    override fun onDestroy() {
        io.shutdownNow()
        AoaHolder.close()
        super.onDestroy()
    }

    /** A touch surface: drag to move, tap to click, two fingers to scroll, two-finger tap to right-click. */
    class TouchpadView(context: Context) : View(context) {
        var onMove: (Int, Int) -> Unit = { _, _ -> }
        var onClick: () -> Unit = {}
        var onRightClick: () -> Unit = {}
        var onScroll: (Int) -> Unit = {}
        var gain = 2f

        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF22303C.toInt() }
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF8FA3B5.toInt()
            textAlign = Paint.Align.CENTER
            textSize = 15f * resources.displayMetrics.scaledDensity
        }

        private var lastX = 0f
        private var lastY = 0f
        private var downTime = 0L
        private var travelled = 0f
        private var maxPointers = 1
        private var remX = 0f
        private var remY = 0f
        private var scrollAccum = 0f

        override fun onDraw(canvas: Canvas) {
            val r = 16f * resources.displayMetrics.density
            canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), r, r, fill)
            val lines = listOf("Drag to move the pointer", "Tap to click", "Two fingers to scroll")
            val lineHeight = text.textSize * 1.4f
            var y = height / 2f - lineHeight
            for (line in lines) {
                canvas.drawText(line, width / 2f, y, text)
                y += lineHeight
            }
        }

        private fun centre(e: MotionEvent): Pair<Float, Float> {
            var x = 0f
            var y = 0f
            for (i in 0 until e.pointerCount) { x += e.getX(i); y += e.getY(i) }
            return (x / e.pointerCount) to (y / e.pointerCount)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            val slop = 10f * resources.displayMetrics.density
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val (x, y) = centre(e)
                    lastX = x; lastY = y
                    downTime = e.eventTime
                    travelled = 0f
                    maxPointers = 1
                    remX = 0f; remY = 0f; scrollAccum = 0f
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    maxPointers = maxOf(maxPointers, e.pointerCount)
                    val (x, y) = centre(e)
                    lastX = x; lastY = y
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    // Re-anchor on the fingers that remain so the pointer does not jump.
                    val keep = (0 until e.pointerCount).filter { it != e.actionIndex }
                    if (keep.isNotEmpty()) {
                        lastX = keep.map { e.getX(it) }.average().toFloat()
                        lastY = keep.map { e.getY(it) }.average().toFloat()
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    val (x, y) = centre(e)
                    val dx = x - lastX
                    val dy = y - lastY
                    lastX = x; lastY = y
                    travelled += hypot(dx, dy)
                    if (e.pointerCount >= 2) {
                        scrollAccum += dy
                        val step = 12f * resources.displayMetrics.density
                        val steps = (scrollAccum / step).toInt()
                        if (steps != 0) {
                            scrollAccum -= steps * step
                            // Dragging up scrolls content up, as on a laptop with natural scrolling.
                            onScroll(-steps)
                        }
                    } else {
                        remX += dx * gain
                        remY += dy * gain
                        val mx = remX.toInt()
                        val my = remY.toInt()
                        remX -= mx; remY -= my
                        if (mx != 0 || my != 0) onMove(mx, my)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    val quick = e.eventTime - downTime < 300
                    if (quick && travelled < slop) {
                        if (maxPointers >= 2) onRightClick() else onClick()
                    }
                }
            }
            return true
        }
    }
}
