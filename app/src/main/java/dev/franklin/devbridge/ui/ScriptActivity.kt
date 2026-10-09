package dev.franklin.devbridge.ui

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import dev.franklin.devbridge.aoa.AoaHolder
import dev.franklin.devbridge.aoa.AoaScriptActions
import dev.franklin.devbridge.aoa.ScriptEngine
import dev.franklin.devbridge.aoa.ScriptRunner
import java.util.concurrent.Executors

/**
 * Saved, editable blind-input scripts: a list of key presses, typed text and pointer moves to replay
 * on the target phone through the touchpad/keyboard connection, for when its own screen can't be read
 * to click or type on it directly (see [TouchpadActivity]). A script is a guess at what to press, not
 * a way to see whether it worked — reconnect over USB afterwards to find out.
 */
class ScriptActivity : AppCompatActivity() {

    private val ui = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    private lateinit var status: TextView
    private lateinit var nameField: EditText
    private lateinit var editor: EditText
    private lateinit var log: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var scriptList: LinearLayout
    private lateinit var runButton: Button

    private var runner: ScriptRunner? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Scripts"

        if (store().getAll().isEmpty()) seedDefaults()

        val root = column(12)
        root.addView(label("Scripts", 22f, bold = true))
        status = label(
            if (AoaHolder.session?.isAlive == true) "Connected to ${AoaHolder.label}." else "Not connected — open Touchpad & keyboard first.",
            13f,
        )
        root.spaced(status, 4)
        root.spaced(
            label(
                "A script is a list of key presses and pointer moves to try blind, when you can't see the " +
                    "target's screen to click or type on it directly. It does not know whether anything it " +
                    "sends actually worked — after running one, reconnect with Connect over USB to check.",
                12f,
            ),
            4,
        )

        scriptList = wrapRow()
        root.spaced(label("Saved scripts", 15f, bold = true), 16)
        root.addView(scriptList)

        nameField = EditText(this).apply { hint = "Script name"; setSingleLine() }
        root.spaced(nameField, 16)

        editor = EditText(this).apply {
            hint = "WAIT 300\nKEY TAB\nKEY SPACE\nKEY ENTER"
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 13f
            minLines = 8
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        root.addView(editor)

        val fileButtons = wrapRow()
        fileButtons.addView(button("Save") { save() })
        fileButtons.addView(button("Save as new") { saveAsNew() })
        fileButtons.addView(button("Delete") { delete() })
        fileButtons.addView(button("Check syntax") { checkSyntax() })
        root.addView(fileButtons)

        val runButtons = wrapRow()
        runButton = button("Run") { confirmAndRun() }
        runButtons.addView(runButton)
        runButtons.addView(button("Stop") { runner?.cancel() })
        runButtons.addView(button("Clear log") { log.text = "" })
        root.addView(runButtons)

        root.spaced(label("Log", 15f, bold = true), 12)
        log = label("", 12f, mono = true)
        logScroll = ScrollView(this).apply {
            addView(log)
            layoutParams = LinearLayout.LayoutParams(ViewGroup_MATCH, dp(160))
        }
        root.addView(logScroll)

        val scroller = scrolling(root)
        scroller.applySystemBarPadding(includeTop = true)
        setContentView(centered(scroller, 760))

        renderScriptList()
        val first = store().getAll().keys.sorted().firstOrNull()
        if (first != null) load(first)
    }

    private val ViewGroup_MATCH = android.view.ViewGroup.LayoutParams.MATCH_PARENT

    // --- storage --------------------------------------------------------------------------------------

    private fun store() = getSharedPreferences("scripts", MODE_PRIVATE)

    private fun seedDefaults() {
        val defaults = linkedMapOf(
            "Approve — Enter only" to "# Tries the simplest case: OK/Allow already has focus.\nWAIT 300\nKEY ENTER\n",
            "Approve — Tab, Space, Tab, Tab, Enter" to
                "# Tab to the checkbox, tick it with Space, tab to Allow, press Enter.\nWAIT 300\nKEY TAB\nKEY SPACE\nKEY TAB\nKEY TAB\nKEY ENTER\n",
            "Approve — Space, Enter" to "# Checkbox already focused: tick it, then move to the button.\nWAIT 300\nKEY SPACE\nKEY ENTER\n",
            "Wake screen" to "# A small pointer move and a tap, which some phones treat as input that wakes the display.\nMOVE 1 1\nWAIT 200\nMOVE -1 -1\nCLICK\n",
            "Type PIN and Enter" to "# Fill in {PIN} when running this one.\nWAIT 300\nTYPE {PIN}\nKEY ENTER\n",
        )
        val editor = store().edit()
        for ((name, text) in defaults) editor.putString(name, text)
        editor.apply()
    }

    private fun renderScriptList() {
        scriptList.removeAllViews()
        for (name in store().getAll().keys.sorted()) {
            scriptList.addView(button(name) { load(name) })
        }
    }

    private fun load(name: String) {
        nameField.setText(name)
        editor.setText(store().getString(name, ""))
    }

    private fun save() {
        val name = nameField.text.toString().trim()
        if (name.isEmpty()) { toast("Name the script first"); return }
        store().edit().putString(name, editor.text.toString()).apply()
        renderScriptList()
        toast("Saved \"$name\"")
    }

    private fun saveAsNew() {
        val base = nameField.text.toString().trim().ifEmpty { "Script" }
        var name = base
        var n = 2
        while (store().contains(name)) { name = "$base ($n)"; n++ }
        nameField.setText(name)
        store().edit().putString(name, editor.text.toString()).apply()
        renderScriptList()
        toast("Saved as \"$name\"")
    }

    private fun delete() {
        val name = nameField.text.toString().trim()
        if (name.isEmpty() || !store().contains(name)) { toast("No saved script with that name"); return }
        AlertDialog.Builder(this)
            .setTitle("Delete \"$name\"?")
            .setPositiveButton("Delete") { _, _ ->
                store().edit().remove(name).apply()
                renderScriptList()
                nameField.setText("")
                editor.setText("")
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // --- running ----------------------------------------------------------------------------------------

    private fun checkSyntax(): ScriptEngine.ParseResult {
        val result = ScriptEngine.parse(editor.text.toString())
        if (result.isValid) {
            toast("${result.steps.size} step(s), no errors")
        } else {
            AlertDialog.Builder(this)
                .setTitle("${result.errors.size} problem(s)")
                .setMessage(result.errors.joinToString("\n") { "Line ${it.line}: ${it.message}" })
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
        return result
    }

    private fun confirmAndRun() {
        val result = checkSyntax()
        if (!result.isValid) return
        val session = AoaHolder.session
        if (session == null || !session.isAlive) {
            toast("Not connected. Open Touchpad & keyboard first.")
            return
        }

        val tokens = Regex("\\{([A-Za-z0-9_]+)\\}").findAll(editor.text.toString()).map { it.groupValues[1] }.distinct().toList()
        if (tokens.isEmpty()) {
            runScript(result, emptyMap())
            return
        }

        val fields = tokens.associateWith { token ->
            EditText(this).apply {
                hint = token
                if (token.equals("PIN", ignoreCase = true)) inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            }
        }
        val form = column(8)
        for ((token, field) in fields) {
            form.addView(label(token, 12f))
            form.addView(field)
        }
        AlertDialog.Builder(this)
            .setTitle("Fill in before running")
            .setView(scrolling(form))
            .setPositiveButton("Run") { _, _ ->
                val values = fields.mapValues { it.value.text.toString() }
                runScript(result, values)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun runScript(parsed: ScriptEngine.ParseResult, values: Map<String, String>) {
        val session = AoaHolder.session
        if (session == null || !session.isAlive) { toast("Not connected."); return }

        val steps = if (values.isEmpty()) parsed.steps else substitute(parsed.steps, values)
        val runner = ScriptRunner(AoaScriptActions(session))
        this.runner = runner
        runButton.isEnabled = false
        appendLog("--- running ${steps.size} step(s) ---")

        io.execute {
            try {
                runner.run(steps) { i, step -> ui.post { appendLog("${i + 1}/${steps.size}  ${describe(step)}") } }
                ui.post { appendLog("--- done. Reconnect with Connect over USB to see whether it worked. ---") }
            } catch (e: Exception) {
                ui.post { appendLog("--- stopped: ${e.message} ---") }
            } finally {
                ui.post { runButton.isEnabled = true }
            }
        }
    }

    private fun substitute(steps: List<ScriptEngine.Step>, values: Map<String, String>): List<ScriptEngine.Step> = steps.map { step ->
        if (step is ScriptEngine.Step.Type) {
            var text = step.text
            for ((token, value) in values) text = text.replace("{$token}", value)
            ScriptEngine.Step.Type(text)
        } else {
            step
        }
    }

    private fun describe(step: ScriptEngine.Step): String = when (step) {
        is ScriptEngine.Step.Wait -> "wait ${step.ms} ms"
        is ScriptEngine.Step.KeyPress -> "key ${step.name}"
        is ScriptEngine.Step.Type -> "type \"${step.text}\""
        is ScriptEngine.Step.Move -> "move ${step.dx},${step.dy}"
        is ScriptEngine.Step.Click -> if (step.button == dev.franklin.devbridge.aoa.Aoa.BUTTON_LEFT) "left click" else "right click"
        is ScriptEngine.Step.Scroll -> "scroll ${step.vertical}"
    }

    private fun appendLog(line: String) {
        log.append(if (log.text.isEmpty()) line else "\n$line")
        logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun toast(text: String) = android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        runner?.cancel()
        io.shutdownNow()
        super.onDestroy()
    }
}
