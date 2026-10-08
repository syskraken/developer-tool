package dev.franklin.devbridge

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Android compiles regexes with ICU, which is stricter than the desktop JVM these tests run on: a bare
 * `}` or `]` outside a character class is a syntax error there but passes here. That slipped through
 * once and crashed a whole audit on a real phone, so this test scans the source for it.
 */
class RegexSafetyTest {

    private fun sourceRoot(): File =
        listOf("src/main/java", "app/src/main/java").map { File(it) }.first { it.isDirectory }

    /** Returns a description of the first problem in [pattern], or null. */
    private fun problem(pattern: String): String? {
        var i = 0
        var inClass = false
        while (i < pattern.length) {
            val c = pattern[i]
            when {
                c == '\\' -> i++                                   // escaped character, skip it
                inClass && c == ']' -> inClass = false
                !inClass && c == '[' -> inClass = true
                !inClass && (c == ']' || c == '}') -> return "unescaped '$c' at index $i"
                !inClass && c == '{' -> {
                    // only a quantifier like {3} or {2,5} is allowed
                    val m = Regex("""^\{\d+(,\d*)?\}""").find(pattern.substring(i))
                        ?: return "unescaped '{' at index $i"
                    i += m.value.length - 1
                }
            }
            i++
        }
        return null
    }

    @Test
    fun scannerCatchesTheBugItIsHereFor() {
        assertTrue(problem("""admin=ComponentInfo\{([^}]+)}""") != null)
        assertTrue(problem("""^\[([^\]]+)]: \[(.*)]${'$'}""") != null)
        assertTrue(problem("""admin=ComponentInfo\{([^}]+)\}""") == null)
        assertTrue(problem("""^\[([^\]]+)\]: \[(.*)\]${'$'}""") == null)
        assertTrue(problem("""\d{2,4}[a-z]{3}""") == null)
    }

    @Test
    fun everyRegexInTheAppIsSafeOnAndroid() {
        val literal = Regex("Regex\\(\"\"\"(.*?)\"\"\"", RegexOption.DOT_MATCHES_ALL)
        val failures = ArrayList<String>()
        var checked = 0
        sourceRoot().walkTopDown().filter { it.extension == "kt" }.forEach { file ->
            for (m in literal.findAll(file.readText())) {
                checked++
                problem(m.groupValues[1])?.let { failures += "${file.name}: ${m.groupValues[1]} -> $it" }
            }
        }
        assertTrue("expected to find the app's regexes, found $checked", checked >= 5)
        assertTrue("Regexes ICU would reject:\n" + failures.joinToString("\n"), failures.isEmpty())
    }
}
