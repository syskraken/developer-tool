package dev.franklin.devbridge.analysis

/**
 * Looks for apps that are installed but not obvious: no launcher icon, disabled,
 * or holding quiet system-level powers (accessibility, device admin,
 * notification access). Meant for auditing a phone you own.
 */
object HiddenAppAudit {

    fun parseLauncherPackages(text: String): Set<String> =
        text.lineSequence().map { it.trim() }
            .filter { Regex("""^[A-Za-z0-9_.]+/\S+$""").matches(it) }
            .map { it.substringBefore('/') }.toSet()

    /** Components out of `a/b:c/d` style settings values; `null` and empty both mean none. */
    fun parseComponentList(text: String): List<String> {
        val value = text.trim()
        if (value.isEmpty() || value == "null") return emptyList()
        return value.split(':').map { it.trim() }.filter { it.contains('/') }
    }

    fun parseDeviceAdmins(text: String): List<String> =
        Regex("""admin=ComponentInfo\{([^}]+)\}""").findAll(text).map { it.groupValues[1] }.distinct().toList()

    private val SYSTEM_LOOKALIKE = listOf("com.android.", "com.google.android.", "com.samsung.android.", "android.")

    class Result(val findings: List<Finding>, val sections: List<Section>)

    fun run(shell: Shell): Result {
        val findings = mutableListOf<Finding>()
        val sections = mutableListOf<Section>()

        val userApps = PermissionAnalyzer.parsePackageList(shell.tryRun("pm list packages -3"))
        val disabled = PermissionAnalyzer.parsePackageList(shell.tryRun("pm list packages -d"))
        val removedKeepingData = PermissionAnalyzer.parsePackageList(shell.tryRun("pm list packages -u"))
            .toSet() - PermissionAnalyzer.parsePackageList(shell.tryRun("pm list packages")).toSet()

        var launcherText = shell.tryRun("cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER")
        if (safely(emptySet<String>()) { parseLauncherPackages(launcherText) }.isEmpty()) {
            launcherText = shell.tryRun("pm query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER")
        }
        val launcher = safely(emptySet()) { parseLauncherPackages(launcherText) }

        sections += Section("Overview", listOf(
            "Third-party apps: ${userApps.size}",
            "Disabled apps: ${disabled.size}",
            "Apps with a launcher icon: ${launcher.size}",
        ))

        if (launcher.isEmpty()) {
            findings += Finding(Severity.INFO, "Launcher listing unavailable", "This Android version did not list launcher activities, so icon-less apps cannot be identified.")
        } else {
            val iconless = userApps.filter { it !in launcher }
            sections += Section("User apps with no launcher icon (${iconless.size})", iconless.ifEmpty { listOf("None") })
            for (pkg in iconless) {
                findings += Finding(Severity.WARN, pkg, "Installed by the user but has no launcher icon, so it does not appear in the app drawer.")
            }
        }

        sections += Section("Disabled apps (${disabled.size})", disabled.ifEmpty { listOf("None") })
        for (pkg in disabled.filter { it in userApps }) {
            findings += Finding(Severity.INFO, pkg, "Third-party app that is currently disabled.")
        }

        sections += Section("Removed but data kept (${removedKeepingData.size})", removedKeepingData.sorted().ifEmpty { listOf("None") })

        val accessibility = safely(emptyList()) { parseComponentList(shell.tryRun("settings get secure enabled_accessibility_services")) }
        sections += Section("Accessibility services enabled", accessibility.ifEmpty { listOf("None") })
        for (c in accessibility) findings += Finding(Severity.HIGH, c.substringBefore('/'), "Has an active accessibility service: it can read the screen and perform taps. Legitimate for screen readers and password managers; suspicious otherwise.")

        val listeners = safely(emptyList()) { parseComponentList(shell.tryRun("settings get secure enabled_notification_listeners")) }
        sections += Section("Notification access", listeners.ifEmpty { listOf("None") })
        for (c in listeners) findings += Finding(Severity.WARN, c.substringBefore('/'), "Can read every notification, including one-time codes.")

        val admins = safely(emptyList()) { parseDeviceAdmins(shell.tryRun("dumpsys device_policy")) }
        sections += Section("Device administrators", admins.ifEmpty { listOf("None") })
        for (c in admins) findings += Finding(Severity.HIGH, c.substringBefore('/'), "Is a device administrator: it can resist uninstalling and enforce policies.")

        for (pkg in userApps) {
            if (SYSTEM_LOOKALIKE.any { pkg.startsWith(it) }) {
                findings += Finding(Severity.WARN, pkg, "User-installed app with a system-style package name; a common disguise.")
            }
        }

        val order = compareByDescending<Finding> { it.severity.ordinal }
        return Result(findings.distinctBy { it.severity to it.title + it.detail }.sortedWith(order), sections)
    }
}
