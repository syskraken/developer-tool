package dev.franklin.devbridge

import dev.franklin.devbridge.analysis.HardwareCheck
import dev.franklin.devbridge.analysis.HiddenAppAudit
import dev.franklin.devbridge.analysis.PermissionAnalyzer
import dev.franklin.devbridge.analysis.Severity
import dev.franklin.devbridge.analysis.Shell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalysisTest {

    private fun fakeShell(vararg answers: Pair<String, String>) = Shell { cmd ->
        answers.firstOrNull { cmd == it.first }?.second ?: ""
    }

    @Test
    fun parsesBattery() {
        val b = HardwareCheck.parseBattery(
            """
            Current Battery Service state:
              AC powered: false
              USB powered: true
              Wireless powered: false
              status: 2
              health: 2
              level: 87
              voltage: 4123
              temperature: 462
            """.trimIndent(),
        )!!
        assertEquals(87, b.level)
        assertEquals(46.2, b.tempC!!, 0.01)
        assertEquals("good", b.health)
        assertTrue(b.charging)
    }

    @Test
    fun parsesMemoryStorageAndCpu() {
        val mem = HardwareCheck.parseMeminfo("MemTotal:        7864320 kB\nMemFree:  100 kB\nMemAvailable:    3932160 kB\n")!!
        assertEquals(7864320, mem.totalKb)
        assertEquals(3932160, mem.availableKb)

        val df = HardwareCheck.parseDf("Filesystem       1K-blocks     Used Available Use% Mounted on\n/dev/block/dm-5  112000000 98000000  14000000  88% /data\n")!!
        assertEquals(88, df.usedPercent)

        val cpu = HardwareCheck.parseCpu("processor\t: 0\nprocessor\t: 1\nHardware\t: Qualcomm SM8350\n")
        assertEquals(2, cpu.cores)
        assertEquals("Qualcomm SM8350", cpu.hardware)
    }

    @Test
    fun hardwareReportFlagsHotBatteryAndUnlockedBootloader() {
        val shell = fakeShell(
            "getprop" to "[ro.product.model]: [SM-F711B]\n[ro.product.manufacturer]: [samsung]\n[ro.boot.verifiedbootstate]: [orange]\n",
            "dumpsys battery" to "  level: 50\n  status: 3\n  health: 2\n  temperature: 470\n",
        )
        val result = HardwareCheck.run(shell)
        assertTrue(result.findings.any { it.title == "Battery is hot" && it.severity == Severity.HIGH })
        assertTrue(result.findings.any { it.title.startsWith("Bootloader") })
        assertTrue(result.sections.first().lines.first().contains("SM-F711B"))
    }

    @Test
    fun parsesSensorsAndFeatures() {
        val sensors = HardwareCheck.parseSensors(
            "Sensor List:\n0x00000001) BMI160 Accelerometer | Bosch | ver: 1 | type: android.sensor.accelerometer(1) | perm: n/a\n" +
                "0x00000002) AK09915 Magnetometer | AKM | ver: 1 | type: android.sensor.magnetic_field(2)\n",
        )
        assertEquals(listOf("BMI160 Accelerometer", "AK09915 Magnetometer"), sensors)
        assertTrue("android.hardware.nfc" in HardwareCheck.parseFeatures("feature:android.hardware.nfc\nfeature:reqGlEsVersion=0x30002\n"))
    }

    private val riskyDump = """
        Packages:
          Package [com.spy.app] (abc):
            install permissions:
              android.permission.INTERNET: granted=true
              android.permission.RECEIVE_BOOT_COMPLETED: granted=true
            User 0: ceDataInode=1 installed=true hidden=false
              runtime permissions:
                android.permission.READ_SMS: granted=true, flags=[ USER_SET]
                android.permission.RECORD_AUDIO: granted=true
                android.permission.CAMERA: granted=false, flags=[ USER_SET]
                android.permission.BIND_ACCESSIBILITY_SERVICE: granted=true
    """.trimIndent()

    @Test
    fun parsesGrantedAndDeniedPermissions() {
        val app = PermissionAnalyzer.parsePackageDump("com.spy.app", riskyDump)
        assertTrue("android.permission.READ_SMS" in app.granted)
        assertTrue("android.permission.CAMERA" in app.denied)
        assertFalse("android.permission.CAMERA" in app.granted)
    }

    @Test
    fun flagsRiskyAppsAndIgnoresDeniedPermissions() {
        val shell = fakeShell(
            "pm list packages -3" to "package:com.spy.app\npackage:com.calm.notes\n",
            "dumpsys package com.spy.app" to riskyDump,
            "dumpsys package com.calm.notes" to "  install permissions:\n    android.permission.INTERNET: granted=true\n",
        )
        val result = PermissionAnalyzer.run(shell)
        assertEquals(1, result.findings.size)
        assertEquals(Severity.HIGH, result.findings[0].severity)
        assertEquals("com.spy.app", result.findings[0].title)
        assertTrue(result.findings[0].detail.contains("Accessibility"))
        assertFalse(result.findings[0].detail.contains("camera"))
    }

    @Test
    fun auditFindsIcolessDisabledAndPrivilegedApps() {
        val shell = fakeShell(
            "pm list packages -3" to "package:com.visible.app\npackage:com.hidden.thing\npackage:com.android.systemupdate\n",
            "pm list packages -d" to "package:com.old.app\n",
            "pm list packages -u" to "package:com.visible.app\npackage:com.hidden.thing\npackage:com.gone.app\n",
            "pm list packages" to "package:com.visible.app\npackage:com.hidden.thing\n",
            "cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER" to
                "2 activities found:\n  priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=false\n  com.visible.app/.Main\n",
            "settings get secure enabled_accessibility_services" to "com.hidden.thing/com.hidden.thing.Svc\n",
            "settings get secure enabled_notification_listeners" to "null\n",
            "dumpsys device_policy" to "Enabled Device Admins (User 0):\n  admin=ComponentInfo{com.hidden.thing/com.hidden.thing.Admin}\n",
        )
        val result = HiddenAppAudit.run(shell)
        val titles = result.findings.map { it.title }
        assertTrue("com.hidden.thing" in titles)
        assertTrue("com.android.systemupdate" in titles)
        assertFalse("com.visible.app" in titles)
        assertEquals(Severity.HIGH, result.findings.first().severity)
        val removed = result.sections.first { it.title.startsWith("Removed but data kept") }
        assertEquals(listOf("com.gone.app"), removed.lines)
        assertNotNull(result.sections.firstOrNull { it.title == "Device administrators" })
    }

    @Test
    fun auditToleratesUnsupportedCommands() {
        val result = HiddenAppAudit.run(fakeShell())
        assertTrue(result.findings.any { it.title == "Launcher listing unavailable" })
    }
}
