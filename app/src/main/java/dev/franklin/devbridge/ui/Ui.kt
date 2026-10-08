package dev.franklin.devbridge.ui

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.franklin.devbridge.analysis.Finding
import dev.franklin.devbridge.analysis.Section
import dev.franklin.devbridge.analysis.Severity

/** Small helpers so screens can be built in code without a layout file each. */
fun Context.dp(value: Int): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

fun Context.column(padding: Int = 16): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(padding), dp(padding), dp(padding), dp(padding))
}

fun Context.scrolling(content: LinearLayout): ScrollView = ScrollView(this).apply {
    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    // Hold focus here so the first text field does not grab it and scroll the page away from the top.
    isFocusableInTouchMode = true
    descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
    addView(content)
}

fun Context.label(text: CharSequence, sizeSp: Float = 14f, bold: Boolean = false, mono: Boolean = false): TextView =
    TextView(this).apply {
        this.text = text
        textSize = sizeSp
        if (bold) setTypeface(typeface, Typeface.BOLD)
        if (mono) typeface = Typeface.MONOSPACE
        setTextIsSelectable(true)
    }

fun Context.button(text: String, onClick: () -> Unit): Button = Button(this).apply {
    this.text = text
    isAllCaps = false
    setOnClickListener { onClick() }
}

fun LinearLayout.spaced(view: android.view.View, topDp: Int = 8) {
    val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    lp.topMargin = context.dp(topDp)
    addView(view, lp)
}

private fun severityColor(s: Severity): Int = when (s) {
    Severity.HIGH -> 0xFFD32F2F.toInt()
    Severity.WARN -> 0xFFE65100.toInt()
    Severity.INFO -> 0xFF607D8B.toInt()
}

fun LinearLayout.addFindings(findings: List<Finding>) {
    for (f in findings) {
        val tag = when (f.severity) { Severity.HIGH -> "HIGH"; Severity.WARN -> "WARN"; Severity.INFO -> "INFO" }
        val title = context.label("[$tag] ${f.title}", 15f, bold = true).apply { setTextColor(severityColor(f.severity)) }
        spaced(title, 12)
        if (f.detail.isNotBlank()) addView(context.label(f.detail, 13f))
    }
}

fun LinearLayout.addSections(sections: List<Section>) {
    for (s in sections) {
        spaced(context.label(s.title, 16f, bold = true), 16)
        addView(context.label(s.lines.joinToString("\n"), 13f, mono = true))
    }
}

/** Plain-text rendering used by the Copy button. */
fun reportText(findings: List<Finding>, sections: List<Section>): String = buildString {
    if (findings.isNotEmpty()) {
        appendLine("FINDINGS")
        for (f in findings) {
            appendLine("[${f.severity}] ${f.title}")
            if (f.detail.isNotBlank()) appendLine("    ${f.detail}")
        }
        appendLine()
    }
    for (s in sections) {
        appendLine(s.title.uppercase())
        s.lines.forEach { appendLine(it) }
        appendLine()
    }
}
