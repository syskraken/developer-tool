package dev.franklin.devbridge.update

/** Dotted-version comparison, kept free of Android types so it is easy to test. */
object Versions {

    /**
     * Compares numerically, so 1.10 correctly beats 1.9 where a string comparison
     * would not. A suffix marks a pre-release, making 1.0 newer than 1.0-beta.
     * Returns >0 when [a] is newer than [b].
     */
    fun compare(a: String, b: String): Int {
        val left = numbers(a)
        val right = numbers(b)
        for (i in 0 until maxOf(left.size, right.size)) {
            val x = left.getOrElse(i) { 0 }
            val y = right.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        val suffixA = suffix(a)
        val suffixB = suffix(b)
        return when {
            suffixA.isEmpty() && suffixB.isNotEmpty() -> 1
            suffixA.isNotEmpty() && suffixB.isEmpty() -> -1
            else -> suffixA.compareTo(suffixB)
        }
    }

    fun normalise(version: String): String = version.trim().removePrefix("v").removePrefix("V")

    private fun numbers(version: String): List<Int> =
        normalise(version).substringBefore('-').split('.')
            .map { segment -> segment.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }

    private fun suffix(version: String): String {
        val normalised = normalise(version)
        val dash = normalised.indexOf('-')
        return if (dash >= 0) normalised.substring(dash + 1) else ""
    }
}
