package dev.franklin.devbridge

import dev.franklin.devbridge.aoa.Aoa
import dev.franklin.devbridge.aoa.HidKeys
import dev.franklin.devbridge.aoa.ScriptActions
import dev.franklin.devbridge.aoa.ScriptEngine
import dev.franklin.devbridge.aoa.ScriptRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptEngineTest {

    private class Recorder : ScriptActions {
        val calls = ArrayList<String>()
        override fun key(usage: Int, modifiers: Int) { calls += "KEY $usage/$modifiers" }
        override fun type(text: String) { calls += "TYPE $text" }
        override fun move(dx: Int, dy: Int) { calls += "MOVE $dx,$dy" }
        override fun click(button: Int) { calls += "CLICK $button" }
        override fun scroll(vertical: Int, horizontal: Int) { calls += "SCROLL $vertical,$horizontal" }
    }

    @Test
    fun parsesEveryCommand() {
        val r = ScriptEngine.parse(
            """
            # a comment, and a blank line follows

            WAIT 250
            KEY TAB
            KEY SHIFT+TAB
            TYPE hello world
            MOVE 10 -5
            CLICK
            RCLICK
            SCROLL -2 1
            """.trimIndent(),
        )
        assertTrue(r.errors.joinToString(), r.isValid)
        assertEquals(8, r.steps.size)
        assertTrue((r.steps[0] as ScriptEngine.Step.Wait).ms == 250L)
        val tab = r.steps[1] as ScriptEngine.Step.KeyPress
        assertEquals(HidKeys.TAB, tab.usage)
        assertEquals(0, tab.modifiers)
        val shiftTab = r.steps[2] as ScriptEngine.Step.KeyPress
        assertEquals(HidKeys.MOD_SHIFT, shiftTab.modifiers)
        assertEquals("hello world", (r.steps[3] as ScriptEngine.Step.Type).text)
        val move = r.steps[4] as ScriptEngine.Step.Move
        assertEquals(10, move.dx); assertEquals(-5, move.dy)
        assertEquals(Aoa.BUTTON_LEFT, (r.steps[5] as ScriptEngine.Step.Click).button)
        assertEquals(Aoa.BUTTON_RIGHT, (r.steps[6] as ScriptEngine.Step.Click).button)
        val scroll = r.steps[7] as ScriptEngine.Step.Scroll
        assertEquals(-2, scroll.vertical); assertEquals(1, scroll.horizontal)
    }

    @Test
    fun singleCharacterKeysUseTheUsLayout() {
        val r = ScriptEngine.parse("KEY a\nKEY A\nKEY 1")
        assertTrue(r.isValid)
        val a = r.steps[0] as ScriptEngine.Step.KeyPress
        assertEquals(0x04, a.usage); assertEquals(0, a.modifiers)
        val shiftA = r.steps[1] as ScriptEngine.Step.KeyPress
        assertEquals(0x04, shiftA.usage); assertEquals(HidKeys.MOD_SHIFT, shiftA.modifiers)
        val one = r.steps[2] as ScriptEngine.Step.KeyPress
        assertEquals(0x1E, one.usage)
    }

    @Test
    fun repeatExpandsItsBody() {
        val r = ScriptEngine.parse(
            """
            REPEAT 3
            KEY TAB
            WAIT 100
            END
            KEY ENTER
            """.trimIndent(),
        )
        assertTrue(r.isValid)
        assertEquals(7, r.steps.size)                   // 3 x (KEY, WAIT) + ENTER
        assertEquals(HidKeys.ENTER, (r.steps.last() as ScriptEngine.Step.KeyPress).usage)
    }

    @Test
    fun nestedRepeatWorks() {
        val r = ScriptEngine.parse(
            """
            REPEAT 2
            REPEAT 2
            KEY TAB
            END
            END
            """.trimIndent(),
        )
        assertTrue(r.errors.joinToString(), r.isValid)
        assertEquals(4, r.steps.size)
    }

    @Test
    fun reportsLineNumbersForEachMistake() {
        val r = ScriptEngine.parse(
            """
            KEY nonsense
            WAIT -5
            MOVE 1
            SCROLL
            NOTACOMMAND
            REPEAT 0
            KEY TAB
            END
            END
            """.trimIndent(),
        )
        assertFalse(r.isValid)
        val lines = r.errors.map { it.line }
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 8, 9), lines)
    }

    @Test
    fun unclosedRepeatIsReported() {
        val r = ScriptEngine.parse("REPEAT 2\nKEY TAB")
        assertFalse(r.isValid)
        assertTrue(r.errors.single().message.contains("no matching END"))
    }

    @Test
    fun repeatWithNoBodyIsReported() {
        val r = ScriptEngine.parse("REPEAT 2\nEND")
        assertFalse(r.isValid)
        assertTrue(r.errors.single().message.contains("no steps"))
    }

    @Test
    fun runnerExecutesStepsInOrderAndReportsProgress() {
        val recorder = Recorder()
        val waits = ArrayList<Long>()
        val runner = ScriptRunner(recorder, sleep = { waits += it })
        val steps = ScriptEngine.parse("WAIT 500\nKEY ENTER\nTYPE hi").steps
        var seen = 0
        runner.run(steps) { i, _ -> assertEquals(seen, i); seen++ }
        assertEquals(3, seen)
        assertEquals(listOf(500L), waits)
        assertEquals(listOf("KEY ${HidKeys.ENTER}/0", "TYPE hi"), recorder.calls)
    }

    @Test
    fun cancellingStopsBeforeTheNextStep() {
        val recorder = Recorder()
        val runner = ScriptRunner(recorder, sleep = {})
        val steps = ScriptEngine.parse("KEY TAB\nKEY TAB\nKEY TAB").steps
        var count = 0
        runner.run(steps) { _, _ ->
            count++
            if (count == 2) runner.cancel()
        }
        assertEquals(1, recorder.calls.size)
    }

    @Test
    fun veryLargeRepeatsAreRejectedRatherThanHangingTheApp() {
        val r = ScriptEngine.parse("REPEAT 100\nKEY TAB\nKEY TAB\nKEY TAB\nKEY TAB\nKEY TAB\nKEY TAB\nKEY TAB\nKEY TAB\nKEY TAB\nKEY TAB\nKEY TAB\nKEY TAB\nKEY TAB\nKEY TAB\nKEY TAB\nKEY TAB\nKEY TAB\nKEY TAB\nKEY TAB\nKEY TAB\nKEY TAB\nEND")
        assertFalse(r.isValid)
    }
}
