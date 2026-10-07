package dev.franklin.devbridge.analysis

enum class Severity { INFO, WARN, HIGH }

class Finding(val severity: Severity, val title: String, val detail: String = "")

class Section(val title: String, val lines: List<String>)

/** Anything that can run a shell command on the target phone. */
fun interface Shell {
    fun run(command: String): String
}

/** Runs [command], returning empty text instead of throwing, so one unsupported probe never sinks a whole report. */
fun Shell.tryRun(command: String): String = try {
    run(command)
} catch (e: Exception) {
    ""
}
