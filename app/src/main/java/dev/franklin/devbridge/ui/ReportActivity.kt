package dev.franklin.devbridge.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import dev.franklin.devbridge.Session
import dev.franklin.devbridge.analysis.Finding
import dev.franklin.devbridge.analysis.HardwareCheck
import dev.franklin.devbridge.analysis.HiddenAppAudit
import dev.franklin.devbridge.analysis.PermissionAnalyzer
import dev.franklin.devbridge.analysis.Section
import java.util.concurrent.Executors

/** Runs one of the three analyses against the connected phone and shows the result. */
class ReportActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_HARDWARE = "hardware"
        const val MODE_PERMISSIONS = "permissions"
        const val MODE_HIDDEN = "hidden"
    }

    private val ui = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var content: LinearLayout
    private lateinit var progress: TextView
    private var text = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_HARDWARE
        title = when (mode) {
            MODE_PERMISSIONS -> "Permission analysis"
            MODE_HIDDEN -> "Hidden app audit"
            else -> "Hardware check"
        }

        content = column()
        progress = label("Running…", 14f)
        content.addView(progress)
        setContentView(scrolling(content))

        if (!Session.isConnected()) {
            progress.text = "Not connected."
            return
        }

        worker.execute {
            try {
                val shell = Session.shell()
                val (findings, sections) = when (mode) {
                    MODE_PERMISSIONS -> {
                        val r = PermissionAnalyzer.run(shell) { done, total ->
                            ui.post { progress.text = "Reading app permissions… $done / $total" }
                        }
                        r.findings to r.sections
                    }
                    MODE_HIDDEN -> HiddenAppAudit.run(shell).let { it.findings to it.sections }
                    else -> HardwareCheck.run(shell).let { it.findings to it.sections }
                }
                ui.post { show(findings, sections) }
            } catch (e: Exception) {
                ui.post { progress.text = "Failed: ${e.message}" }
            }
        }
    }

    private fun show(findings: List<Finding>, sections: List<Section>) {
        content.removeAllViews()
        text = reportText(findings, sections)

        content.addView(button("Copy report") {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("DevBridge report", text))
            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
        })
        if (findings.isEmpty()) {
            content.spaced(label("Nothing notable found.", 15f, bold = true), 12)
        } else {
            content.spaced(label("Findings (${findings.size})", 18f, bold = true), 12)
            content.addFindings(findings)
        }
        content.addSections(sections)
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }
}
