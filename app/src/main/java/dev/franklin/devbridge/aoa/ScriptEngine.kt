package dev.franklin.devbridge.aoa

/**
 * A small scripting language for replaying keyboard/mouse input blind — when the target phone's own
 * screen can't be seen, so there is no way to click or read anything on it directly. Each line is one
 * step; `REPEAT n` / `END` repeats the lines between them. Comments start with `#`.
 *
 * ```
 * WAIT 500
 * KEY TAB
 * KEY SHIFT+TAB
 * TYPE 1234
 * MOVE 40 0
 * CLICK
 * RCLICK
 * SCROLL -3
 * REPEAT 3
 *   KEY TAB
 * END
 * ```
 */
object ScriptEngine {

    sealed class Step {
        class Wait(val ms: Long) : Step()
        class KeyPress(val name: String, val usage: Int, val modifiers: Int) : Step()
        class Type(val text: String) : Step()
        class Move(val dx: Int, val dy: Int) : Step()
        class Click(val button: Int) : Step()
        class Scroll(val vertical: Int, val horizontal: Int) : Step()
    }

    class ParseError(val line: Int, val message: String)

    class ParseResult(val steps: List<Step>, val errors: List<ParseError>) {
        val isValid: Boolean get() = errors.isEmpty()
    }

    private val NAMED_KEYS: Map<String, Int> = mapOf(
        "ENTER" to HidKeys.ENTER, "RETURN" to HidKeys.ENTER,
        "ESC" to HidKeys.ESCAPE, "ESCAPE" to HidKeys.ESCAPE,
        "TAB" to HidKeys.TAB,
        "SPACE" to HidKeys.SPACE,
        "BACKSPACE" to HidKeys.BACKSPACE,
        "DELETE" to HidKeys.DELETE, "DEL" to HidKeys.DELETE,
        "UP" to HidKeys.UP, "DOWN" to HidKeys.DOWN, "LEFT" to HidKeys.LEFT, "RIGHT" to HidKeys.RIGHT,
        "HOME" to HidKeys.HOME, "END" to HidKeys.END,
        "PAGEUP" to HidKeys.PAGE_UP, "PAGEDOWN" to HidKeys.PAGE_DOWN,
    )

    private val MODIFIER_NAMES: Map<String, Int> = mapOf(
        "SHIFT" to HidKeys.MOD_SHIFT, "CTRL" to HidKeys.MOD_CTRL, "CONTROL" to HidKeys.MOD_CTRL,
        "ALT" to HidKeys.MOD_ALT, "GUI" to HidKeys.MOD_GUI, "META" to HidKeys.MOD_GUI, "WIN" to HidKeys.MOD_GUI,
    )

    private const val MAX_REPEAT = 100
    private const val MAX_EXPANDED_STEPS = 2000

    fun parse(text: String): ParseResult {
        val steps = ArrayList<Step>()
        val errors = ArrayList<ParseError>()
        // Each open REPEAT pushes where its body starts and how many times to copy it at END.
        data class Open(val startIndex: Int, val count: Int, val line: Int)
        val stack = ArrayList<Open>()

        text.lines().forEachIndexed { i, raw ->
            val lineNo = i + 1
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) return@forEachIndexed

            val parts = line.split(Regex("\\s+"), limit = 2)
            val command = parts[0].uppercase()
            val rest = parts.getOrElse(1) { "" }.trim()

            when (command) {
                "WAIT" -> {
                    val ms = rest.toLongOrNull()
                    if (ms == null || ms < 0) errors += ParseError(lineNo, "WAIT needs a non-negative number of milliseconds")
                    else steps += Step.Wait(ms.coerceAtMost(60_000))
                }
                "KEY" -> parseKey(rest)?.let { steps += it } ?: errors.add(ParseError(lineNo, "Unknown key '$rest'"))
                "TYPE" -> if (rest.isEmpty()) errors += ParseError(lineNo, "TYPE needs text") else steps += Step.Type(rest)
                "MOVE" -> {
                    val xy = rest.split(Regex("\\s+"))
                    val dx = xy.getOrNull(0)?.toIntOrNull()
                    val dy = xy.getOrNull(1)?.toIntOrNull()
                    if (dx == null || dy == null) errors += ParseError(lineNo, "MOVE needs two numbers: dx dy")
                    else steps += Step.Move(dx, dy)
                }
                "CLICK" -> steps += Step.Click(Aoa.BUTTON_LEFT)
                "RCLICK" -> steps += Step.Click(Aoa.BUTTON_RIGHT)
                "SCROLL" -> {
                    val vh = rest.split(Regex("\\s+"))
                    val v = vh.getOrNull(0)?.toIntOrNull()
                    val h = vh.getOrNull(1)?.toIntOrNull() ?: 0
                    if (v == null) errors += ParseError(lineNo, "SCROLL needs a number (vertical steps)")
                    else steps += Step.Scroll(v, h)
                }
                "REPEAT" -> {
                    val count = rest.toIntOrNull()
                    if (count == null || count < 1 || count > MAX_REPEAT) {
                        errors += ParseError(lineNo, "REPEAT needs a count from 1 to $MAX_REPEAT")
                    } else {
                        stack.add(Open(steps.size, count, lineNo))
                    }
                }
                "END" -> {
                    val open = stack.removeLastOrNull()
                    if (open == null) {
                        errors += ParseError(lineNo, "END with no matching REPEAT")
                    } else {
                        val body = steps.subList(open.startIndex, steps.size).toList()
                        if (body.isEmpty()) {
                            errors += ParseError(open.line, "REPEAT has no steps before END")
                        } else {
                            repeat(open.count - 1) { steps.addAll(body) }
                            if (steps.size > MAX_EXPANDED_STEPS) {
                                errors += ParseError(open.line, "Script is too long once REPEAT is expanded")
                            }
                        }
                    }
                }
                else -> errors += ParseError(lineNo, "Unknown command '$command'")
            }
        }
        for (open in stack) errors += ParseError(open.line, "REPEAT with no matching END")

        return ParseResult(steps, errors)
    }

    private fun parseKey(spec: String): Step.KeyPress? {
        if (spec.isEmpty()) return null
        val parts = spec.split('+')
        var modifiers = 0
        for (i in 0 until parts.size - 1) {
            modifiers = modifiers or (MODIFIER_NAMES[parts[i].uppercase()] ?: return null)
        }
        val keyName = parts.last()
        val named = NAMED_KEYS[keyName.uppercase()]
        if (named != null) return Step.KeyPress(spec, named, modifiers)
        if (keyName.length == 1) {
            val key = HidKeys.forChar(keyName[0]) ?: return null
            return Step.KeyPress(spec, key.usage, key.modifiers or modifiers)
        }
        return null
    }
}

/** What a script step actually does to the target; implemented over [AoaSession], faked in tests. */
interface ScriptActions {
    fun key(usage: Int, modifiers: Int)
    fun type(text: String)
    fun move(dx: Int, dy: Int)
    fun click(button: Int)
    fun scroll(vertical: Int, horizontal: Int)
}

/** Sends an [AoaSession]'s calls straight through. */
class AoaScriptActions(private val session: AoaSession) : ScriptActions {
    override fun key(usage: Int, modifiers: Int) = session.tapKey(usage, modifiers)
    override fun type(text: String) {
        session.typeText(text)
    }
    override fun move(dx: Int, dy: Int) = session.moveMouse(dx, dy)
    override fun click(button: Int) = session.click(button)
    override fun scroll(vertical: Int, horizontal: Int) = session.scroll(vertical, horizontal)
}

/** Runs a parsed script step by step, reporting progress and honouring cancellation. */
class ScriptRunner(
    private val actions: ScriptActions,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) {
    @Volatile private var cancelled = false

    fun cancel() {
        cancelled = true
    }

    /** Runs every step in order; [onStep] fires before each one. Stops early if cancelled or a step throws. */
    fun run(steps: List<ScriptEngine.Step>, onStep: (index: Int, step: ScriptEngine.Step) -> Unit = { _, _ -> }) {
        cancelled = false
        for ((i, step) in steps.withIndex()) {
            if (cancelled) return
            onStep(i, step)
            if (cancelled) return
            when (step) {
                is ScriptEngine.Step.Wait -> sleep(step.ms)
                is ScriptEngine.Step.KeyPress -> actions.key(step.usage, step.modifiers)
                is ScriptEngine.Step.Type -> actions.type(step.text)
                is ScriptEngine.Step.Move -> actions.move(step.dx, step.dy)
                is ScriptEngine.Step.Click -> actions.click(step.button)
                is ScriptEngine.Step.Scroll -> actions.scroll(step.vertical, step.horizontal)
            }
        }
    }
}
