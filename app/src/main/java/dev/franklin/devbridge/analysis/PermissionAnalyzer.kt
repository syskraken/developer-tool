package dev.franklin.devbridge.analysis

/** Reads which permissions each third-party app actually holds and flags risky ones. */
object PermissionAnalyzer {

    class AppPermissions(
        val packageName: String,
        val granted: Set<String>,
        val denied: Set<String>,
    )

    private class Rule(val label: String, val weight: Int, val permissions: Set<String>)

    private val RULES = listOf(
        Rule("Reads or sends SMS", 3, setOf("android.permission.READ_SMS", "android.permission.SEND_SMS", "android.permission.RECEIVE_SMS", "android.permission.RECEIVE_MMS")),
        Rule("Reads call log", 3, setOf("android.permission.READ_CALL_LOG", "android.permission.WRITE_CALL_LOG", "android.permission.PROCESS_OUTGOING_CALLS")),
        Rule("Records audio", 2, setOf("android.permission.RECORD_AUDIO")),
        Rule("Uses camera", 2, setOf("android.permission.CAMERA")),
        Rule("Precise location", 2, setOf("android.permission.ACCESS_FINE_LOCATION")),
        Rule("Location in background", 3, setOf("android.permission.ACCESS_BACKGROUND_LOCATION")),
        Rule("Reads contacts", 2, setOf("android.permission.READ_CONTACTS", "android.permission.GET_ACCOUNTS")),
        Rule("Reads phone identity", 1, setOf("android.permission.READ_PHONE_STATE", "android.permission.READ_PHONE_NUMBERS")),
        Rule("Full storage access", 2, setOf("android.permission.MANAGE_EXTERNAL_STORAGE")),
        Rule("Can install other apps", 3, setOf("android.permission.REQUEST_INSTALL_PACKAGES", "android.permission.INSTALL_PACKAGES")),
        Rule("Draws over other apps", 2, setOf("android.permission.SYSTEM_ALERT_WINDOW")),
        Rule("Accessibility service", 4, setOf("android.permission.BIND_ACCESSIBILITY_SERVICE")),
        Rule("Device admin", 4, setOf("android.permission.BIND_DEVICE_ADMIN")),
        Rule("Reads notifications", 3, setOf("android.permission.BIND_NOTIFICATION_LISTENER_SERVICE")),
        Rule("Starts at boot", 1, setOf("android.permission.RECEIVE_BOOT_COMPLETED")),
        Rule("Reads body sensors / activity", 1, setOf("android.permission.BODY_SENSORS", "android.permission.ACTIVITY_RECOGNITION")),
    )

    /** Pulls the permission grants out of one `dumpsys package <name>` block. */
    fun parsePackageDump(name: String, dump: String): AppPermissions {
        val granted = HashSet<String>()
        val denied = HashSet<String>()
        val regex = Regex("""^\s+([A-Za-z0-9_.]+(?:\.[A-Za-z0-9_]+)+):\s*granted=(true|false)""")
        for (line in dump.lineSequence()) {
            val m = regex.find(line) ?: continue
            if (m.groupValues[2] == "true") granted += m.groupValues[1] else denied += m.groupValues[1]
        }
        // Permissions the app asks for but that were never granted may also appear as granted in
        // an earlier "install permissions" list on older Android; a grant always wins.
        denied.removeAll(granted)
        return AppPermissions(name, granted, denied)
    }

    fun parsePackageList(text: String): List<String> =
        text.lineSequence().map { it.trim() }.filter { it.startsWith("package:") }
            .map { it.removePrefix("package:").substringBefore('=').trim() }.filter { it.isNotEmpty() }.sorted().toList()

    class Analysis(val findings: List<Finding>, val sections: List<Section>)

    fun analyze(apps: List<AppPermissions>): Analysis {
        val scored = apps.map { app ->
            val hits = RULES.filter { rule -> rule.permissions.any { it in app.granted } }
            Triple(app, hits, hits.sumOf { it.weight })
        }.sortedByDescending { it.third }

        val findings = mutableListOf<Finding>()
        for ((app, hits, score) in scored) {
            if (hits.isEmpty()) continue
            val severity = when {
                hits.any { it.weight >= 4 } || score >= 9 -> Severity.HIGH
                score >= 5 -> Severity.WARN
                else -> Severity.INFO
            }
            if (severity == Severity.INFO) continue
            findings += Finding(severity, app.packageName, hits.joinToString(", ") { it.label })
        }

        val perPermission = LinkedHashMap<String, MutableList<String>>()
        for (rule in RULES) {
            val holders = apps.filter { a -> rule.permissions.any { it in a.granted } }.map { it.packageName }
            if (holders.isNotEmpty()) perPermission[rule.label] = holders.toMutableList()
        }
        val sections = listOf(
            Section("Summary", listOf("${apps.size} third-party apps analysed", "${findings.size} with notable access")),
            Section(
                "Who holds what",
                perPermission.flatMap { (label, holders) ->
                    listOf("$label (${holders.size})") + holders.map { "  • $it" }
                },
            ),
        )
        return Analysis(findings, sections)
    }

    /** Collects the dumps, reporting progress, and analyses them. */
    fun run(shell: Shell, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): Analysis {
        val packages = safely(emptyList()) { parsePackageList(shell.tryRun("pm list packages -3")) }
        val apps = packages.mapIndexed { i, pkg ->
            onProgress(i, packages.size)
            safely(AppPermissions(pkg, emptySet(), emptySet())) { parsePackageDump(pkg, shell.tryRun("dumpsys package $pkg")) }
        }
        onProgress(packages.size, packages.size)
        return analyze(apps)
    }
}
