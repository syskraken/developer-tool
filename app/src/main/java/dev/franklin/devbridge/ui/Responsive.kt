package dev.franklin.devbridge.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ScrollView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.max

/** Width classes, matching Material's breakpoints in dp. */
enum class WindowWidth {
    /** Phones in portrait. */
    COMPACT,

    /** Large phones in landscape, small tablets, unfolded foldables in portrait. */
    MEDIUM,

    /** Tablets, desktop-mode windows and landscape foldables. */
    EXPANDED,
}

fun Context.windowWidth(): WindowWidth {
    val dp = resources.configuration.screenWidthDp
    return when {
        dp < 600 -> WindowWidth.COMPACT
        dp < 840 -> WindowWidth.MEDIUM
        else -> WindowWidth.EXPANDED
    }
}

/** True when the window is wider than it is tall, so side-by-side layouts make sense. */
fun Context.isLandscapeWindow(): Boolean =
    resources.configuration.screenWidthDp > resources.configuration.screenHeightDp

/**
 * Caps its content's width and centres it, so text lines and buttons stay a
 * readable length on tablets and in freely resized windows.
 */
class MaxWidthLayout(context: Context, private val maxWidthDp: Int) : FrameLayout(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxPx = (maxWidthDp * resources.displayMetrics.density).toInt()
        val size = MeasureSpec.getSize(widthMeasureSpec)
        val spec = if (MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED && size > maxPx) {
            MeasureSpec.makeMeasureSpec(maxPx, MeasureSpec.EXACTLY)
        } else {
            widthMeasureSpec
        }
        super.onMeasure(spec, heightMeasureSpec)
    }
}

/** Wraps [content] in a width-capped, horizontally centred container to use with `setContentView`. */
fun Context.centered(content: View, maxWidthDp: Int = 720): View {
    val holder = MaxWidthLayout(this, maxWidthDp)
    holder.addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    val outer = FrameLayout(this)
    outer.addView(holder, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, android.view.Gravity.CENTER_HORIZONTAL))
    return outer
}

/** Lays children out left to right and starts a new row when one no longer fits. */
class WrapLayout(context: Context, private val gapPx: Int) : ViewGroup(context) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val available = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        val limited = MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED
        var x = 0
        var y = 0
        var rowHeight = 0
        var widest = 0

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            child.measure(
                MeasureSpec.makeMeasureSpec(if (limited) available else 0, if (limited) MeasureSpec.AT_MOST else MeasureSpec.UNSPECIFIED),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
            )
            val w = child.measuredWidth
            if (x > 0 && limited && x + w > available) {
                y += rowHeight + gapPx
                x = 0
                rowHeight = 0
            }
            x += w + gapPx
            rowHeight = max(rowHeight, child.measuredHeight)
            widest = max(widest, x - gapPx)
        }

        val contentHeight = y + rowHeight + paddingTop + paddingBottom
        setMeasuredDimension(
            resolveSize(widest + paddingLeft + paddingRight, widthMeasureSpec),
            resolveSize(contentHeight, heightMeasureSpec),
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val available = r - l - paddingLeft - paddingRight
        var x = 0
        var y = 0
        var rowHeight = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            val w = child.measuredWidth
            val h = child.measuredHeight
            if (x > 0 && x + w > available) {
                y += rowHeight + gapPx
                x = 0
                rowHeight = 0
            }
            child.layout(paddingLeft + x, paddingTop + y, paddingLeft + x + w, paddingTop + y + h)
            x += w + gapPx
            rowHeight = max(rowHeight, h)
        }
    }
}

/** A ScrollView that never grows beyond [maxHeightPx] unless it is given an exact height. */
class MaxHeightScrollView(context: Context, private val maxHeightPx: () -> Int) : ScrollView(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val spec = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) {
            heightMeasureSpec
        } else {
            MeasureSpec.makeMeasureSpec(minOf(MeasureSpec.getSize(heightMeasureSpec).takeIf { it > 0 } ?: Int.MAX_VALUE, maxHeightPx()), MeasureSpec.AT_MOST)
        }
        super.onMeasure(widthMeasureSpec, spec)
    }
}

fun Context.wrapRow(gapDp: Int = 4): WrapLayout = WrapLayout(this, dp(gapDp))

/**
 * Keeps content clear of the status bar, navigation bar and display cutout. On
 * Android 15 apps draw edge to edge by default, so without this the bottom
 * buttons end up under the navigation bar.
 */
fun View.applySystemBarPadding(includeTop: Boolean = false) {
    val left = paddingLeft
    val top = paddingTop
    val right = paddingRight
    val bottom = paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        v.setPadding(left + bars.left, top + if (includeTop) bars.top else 0, right + bars.right, bottom + bars.bottom)
        insets
    }
    ViewCompat.requestApplyInsets(this)
}
