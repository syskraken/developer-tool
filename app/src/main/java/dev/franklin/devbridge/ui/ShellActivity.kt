package dev.franklin.devbridge.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import dev.franklin.devbridge.Session
import java.util.concurrent.Executors

/** Runs adb shell commands on the target and shows their output. */
class ShellActivity : AppCompatActivity() {

    private val ui = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var output: TextView
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private val history = ArrayList<String>()
    private var historyIndex = 0

    private val quick = listOf(
        "Wake screen" to "input keyevent KEYCODE_WAKEUP",
        "Home" to "input keyevent KEYCODE_HOME",
        "Logcat tail" to "logcat -d -t 200",
        "Packages" to "pm list packages -3",
        "Reboot" to "reboot",
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "ADB shell"

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(12), dp(12), dp(12)) }

        input = EditText(this).apply {
            hint = "command, e.g. getprop ro.product.model"
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, _, _ -> submit(); true }
        }
        root.addView(input)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(button("Run") { submit() })
        row.addView(button("↑") { recall(-1) })
        row.addView(button("↓") { recall(1) })
        row.addView(button("Clear") { output.text = "" })
        root.addView(row)

        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val chipScroll = android.widget.HorizontalScrollView(this).apply { addView(chips) }
        for ((name, cmd) in quick) chips.addView(button(name) { run(cmd) })
        root.addView(chipScroll)

        output = label("", 12f, mono = true)
        scroll = ScrollView(this).apply { addView(output) }
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        if (!Session.isConnected()) append("Not connected.\n")
    }

    private fun submit() {
        val command = input.text.toString().trim()
        if (command.isEmpty()) return
        history += command
        historyIndex = history.size
        input.setText("")
        run(command)
    }

    private fun recall(delta: Int) {
        if (history.isEmpty()) return
        historyIndex = (historyIndex + delta).coerceIn(0, history.size - 1)
        input.setText(history[historyIndex])
        input.setSelection(input.text.length)
    }

    private fun run(command: String) {
        append("$ $command\n")
        val connection = Session.connection
        if (connection == null || connection.isClosed) {
            append("Not connected.\n")
            return
        }
        worker.execute {
            val result = try {
                connection.shell(command, 30_000)
            } catch (e: Exception) {
                "error: ${e.message}\n"
            }
            ui.post { append(if (result.endsWith("\n") || result.isEmpty()) result else result + "\n") }
        }
    }

    private fun append(text: String) {
        output.append(text)
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }
}
