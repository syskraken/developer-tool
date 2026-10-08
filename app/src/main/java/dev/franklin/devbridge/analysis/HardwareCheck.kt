package dev.franklin.devbridge.analysis

/** Reads the phone's hardware state through ordinary shell commands. */
object HardwareCheck {

    class Result(val sections: List<Section>, val findings: List<Finding>)

    fun run(shell: Shell): Result {
        val props = safely(emptyMap()) { parseGetprop(shell.tryRun("getprop")) }
        val sections = mutableListOf<Section>()
        val findings = mutableListOf<Finding>()

        sections += Section(
            "Device",
            listOf(
                "Model: ${props["ro.product.manufacturer"].orEmpty()} ${props["ro.product.model"].orEmpty()}".trim(),
                "Codename: ${props["ro.product.device"] ?: "unknown"}",
                "Android: ${props["ro.build.version.release"] ?: "?"} (API ${props["ro.build.version.sdk"] ?: "?"})",
                "Security patch: ${props["ro.build.version.security_patch"] ?: "unknown"}",
                "Platform: ${props["ro.board.platform"] ?: props["ro.hardware"] ?: "unknown"}",
                "ABI: ${props["ro.product.cpu.abi"] ?: "unknown"}",
                "Verified boot: ${props["ro.boot.verifiedbootstate"] ?: "unknown"}",
                "Encryption: ${props["ro.crypto.state"] ?: "unknown"}",
            ),
        )
        when (props["ro.boot.verifiedbootstate"]) {
            "orange", "yellow" -> findings += Finding(
                Severity.WARN, "Bootloader is unlocked or custom",
                "Verified boot state is '${props["ro.boot.verifiedbootstate"]}'.",
            )
        }

        safely<Battery?>(null) { parseBattery(shell.tryRun("dumpsys battery")) }?.let { battery ->
            sections += Section("Battery", battery.lines)
            battery.tempC?.let {
                if (it >= 45) findings += Finding(Severity.HIGH, "Battery is hot", "%.1f °C".format(it))
                else if (it >= 40) findings += Finding(Severity.WARN, "Battery is warm", "%.1f °C".format(it))
            }
            if (battery.level != null && battery.level <= 15 && !battery.charging) {
                findings += Finding(Severity.WARN, "Battery is low", "${battery.level}%")
            }
            if (battery.health != null && battery.health != "good") {
                findings += Finding(Severity.WARN, "Battery health: ${battery.health}")
            }
        } ?: findings.add(Finding(Severity.INFO, "Battery information unavailable"))

        val mem = safely<Mem?>(null) { parseMeminfo(shell.tryRun("cat /proc/meminfo")) }
        if (mem != null) {
            val usedPct = 100 - (mem.availableKb * 100 / mem.totalKb).toInt()
            sections += Section(
                "Memory",
                listOf("Total: ${mb(mem.totalKb)} MB", "Available: ${mb(mem.availableKb)} MB", "In use: $usedPct%"),
            )
        }

        val storage = safely<Storage?>(null) { parseDf(shell.tryRun("df /data")) }
        if (storage != null) {
            sections += Section(
                "Storage (/data)",
                listOf(
                    "Total: ${mb(storage.totalKb)} MB",
                    "Used: ${mb(storage.usedKb)} MB (${storage.usedPercent}%)",
                    "Free: ${mb(storage.freeKb)} MB",
                ),
            )
            if (storage.usedPercent >= 95) findings += Finding(Severity.HIGH, "Storage almost full", "${storage.usedPercent}% used")
            else if (storage.usedPercent >= 90) findings += Finding(Severity.WARN, "Storage nearly full", "${storage.usedPercent}% used")
        }

        val display = listOf(
            shell.tryRun("wm size").trim().lines().lastOrNull().orEmpty(),
            shell.tryRun("wm density").trim().lines().lastOrNull().orEmpty(),
        ).filter { it.isNotBlank() }
        if (display.isNotEmpty()) sections += Section("Display", display)

        val cpu = safely(Cpu(0, null)) { parseCpu(shell.tryRun("cat /proc/cpuinfo")) }
        sections += Section("CPU", listOf("Cores: ${cpu.cores}") + listOfNotNull(cpu.hardware?.let { "Hardware: $it" }))

        val features = safely(emptySet()) { parseFeatures(shell.tryRun("pm list features")) }
        val wanted = listOf(
            "Camera (rear)" to "android.hardware.camera",
            "Camera (front)" to "android.hardware.camera.front",
            "Flash" to "android.hardware.camera.flash",
            "Fingerprint" to "android.hardware.fingerprint",
            "NFC" to "android.hardware.nfc",
            "Bluetooth" to "android.hardware.bluetooth",
            "Wi-Fi" to "android.hardware.wifi",
            "Telephony" to "android.hardware.telephony",
            "USB host (OTG)" to "android.hardware.usb.host",
            "GPS" to "android.hardware.location.gps",
        )
        if (features.isNotEmpty()) {
            sections += Section("Hardware features", wanted.map { (label, f) -> "$label: ${if (f in features) "present" else "not reported"}" })
        }

        val sensors = safely(emptyList()) { parseSensors(shell.tryRun("dumpsys sensorservice")) }
        sections += Section(
            "Sensors",
            if (sensors.isEmpty()) listOf("Not reported") else listOf("${sensors.size} sensors") + sensors.map { "• $it" },
        )

        val simState = props["gsm.sim.state"]
        val toggles = listOf(
            "Wi-Fi" to shell.tryRun("settings get global wifi_on").trim(),
            "Bluetooth" to shell.tryRun("settings get global bluetooth_on").trim(),
            "Airplane mode" to shell.tryRun("settings get global airplane_mode_on").trim(),
        ).map { (label, v) -> "$label: ${when (v) { "1" -> "on"; "0" -> "off"; else -> "unknown" }}" }
        sections += Section("Radios", toggles + "SIM: ${simState ?: "unknown"}")

        return Result(sections, findings)
    }

    private fun mb(kb: Long) = kb / 1024

    fun parseGetprop(text: String): Map<String, String> {
        val map = HashMap<String, String>()
        val regex = Regex("""^\[([^\]]+)\]: \[(.*)\]$""")
        for (line in text.lineSequence()) regex.find(line.trim())?.let { map[it.groupValues[1]] = it.groupValues[2] }
        return map
    }

    class Battery(
        val level: Int?,
        val tempC: Double?,
        val health: String?,
        val charging: Boolean,
        val lines: List<String>,
    )

    fun parseBattery(text: String): Battery? {
        if (text.isBlank()) return null
        val values = HashMap<String, String>()
        for (line in text.lineSequence()) {
            val i = line.indexOf(':')
            if (i > 0) values[line.substring(0, i).trim()] = line.substring(i + 1).trim()
        }
        if (values["level"] == null) return null

        val level = values["level"]?.toIntOrNull()
        val temp = values["temperature"]?.toIntOrNull()?.let { it / 10.0 }
        val health = when (values["health"]?.toIntOrNull()) {
            2 -> "good"; 3 -> "overheating"; 4 -> "dead"; 5 -> "over voltage"; 6 -> "failure"; 7 -> "cold"
            else -> null
        }
        val status = when (values["status"]?.toIntOrNull()) {
            2 -> "charging"; 3 -> "discharging"; 4 -> "not charging"; 5 -> "full"
            else -> "unknown"
        }
        val plugged = values["AC powered"] == "true" || values["USB powered"] == "true" || values["Wireless powered"] == "true"
        val charging = status == "charging" || status == "full" || plugged
        val lines = listOfNotNull(
            level?.let { "Level: $it%" },
            "Status: $status",
            health?.let { "Health: $it" },
            temp?.let { "Temperature: %.1f °C".format(it) },
            values["voltage"]?.let { "Voltage: $it mV" },
            "Power source: " + listOfNotNull(
                "AC".takeIf { values["AC powered"] == "true" },
                "USB".takeIf { values["USB powered"] == "true" },
                "wireless".takeIf { values["Wireless powered"] == "true" },
            ).ifEmpty { listOf("battery") }.joinToString(", "),
        )
        return Battery(level, temp, health, charging, lines)
    }

    class Mem(val totalKb: Long, val availableKb: Long)

    fun parseMeminfo(text: String): Mem? {
        fun field(name: String) = Regex("""^$name:\s+(\d+)""", RegexOption.MULTILINE).find(text)?.groupValues?.get(1)?.toLongOrNull()
        val total = field("MemTotal") ?: return null
        if (total <= 0) return null
        return Mem(total, field("MemAvailable") ?: field("MemFree") ?: 0)
    }

    class Storage(val totalKb: Long, val usedKb: Long, val freeKb: Long, val usedPercent: Int)

    fun parseDf(text: String): Storage? {
        val line = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.drop(1).firstOrNull() ?: return null
        val parts = line.split(Regex("\\s+"))
        if (parts.size < 5) return null
        val total = parts[1].toLongOrNull() ?: return null
        val used = parts[2].toLongOrNull() ?: return null
        val free = parts[3].toLongOrNull() ?: return null
        val pct = parts[4].trimEnd('%').toIntOrNull() ?: if (total > 0) (used * 100 / total).toInt() else 0
        return Storage(total, used, free, pct)
    }

    class Cpu(val cores: Int, val hardware: String?)

    fun parseCpu(text: String): Cpu {
        val cores = text.lineSequence().count { it.startsWith("processor") }
        val hardware = Regex("""^Hardware\s*:\s*(.+)$""", RegexOption.MULTILINE).find(text)?.groupValues?.get(1)?.trim()
        return Cpu(cores, hardware)
    }

    fun parseFeatures(text: String): Set<String> =
        text.lineSequence().map { it.trim() }.filter { it.startsWith("feature:") }
            .map { it.removePrefix("feature:").substringBefore('=') }.toSet()

    fun parseSensors(text: String): List<String> {
        val regex = Regex("""^\s*0x[0-9a-fA-F]+\)\s+(.+?)\s*\|""")
        return text.lineSequence().mapNotNull { regex.find(it)?.groupValues?.get(1)?.trim() }.distinct().toList()
    }
}
