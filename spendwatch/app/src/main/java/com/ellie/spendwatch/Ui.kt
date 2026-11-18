package com.ellie.spendwatch

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat

/** Small builders so the screen can be assembled in code without XML per row. */
object Ui {

    fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    fun column(context: Context, padding: Int = 0): LinearLayout {
        val l = LinearLayout(context)
        l.orientation = LinearLayout.VERTICAL
        l.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        if (padding > 0) {
            val p = dp(context, padding)
            l.setPadding(p, p, p, p)
        }
        return l
    }

    fun row(context: Context): LinearLayout {
        val l = LinearLayout(context)
        l.orientation = LinearLayout.HORIZONTAL
        l.gravity = Gravity.CENTER_VERTICAL
        l.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        return l
    }

    fun card(context: Context): LinearLayout {
        val l = column(context, 16)
        l.background = context.getDrawable(R.drawable.card_bg)
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        params.topMargin = dp(context, 10)
        l.layoutParams = params
        return l
    }

    fun text(
        context: Context,
        value: CharSequence,
        sizeSp: Float = 14f,
        color: Int = color(context, R.color.ink),
        bold: Boolean = false,
        mono: Boolean = false,
        topMargin: Int = 0
    ): TextView {
        val t = TextView(context)
        t.text = value
        t.textSize = sizeSp
        t.setTextColor(color)
        if (mono) {
            t.typeface = Typeface.MONOSPACE
            if (bold) t.setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
        } else if (bold) {
            t.setTypeface(t.typeface, Typeface.BOLD)
        }
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        if (topMargin > 0) params.topMargin = dp(context, topMargin)
        t.layoutParams = params
        return t
    }

    /** A section heading: small, spaced-out capitals. */
    fun heading(context: Context, value: String, topMargin: Int = 28): TextView {
        val t = text(
            context,
            value.uppercase(),
            sizeSp = 11f,
            color = color(context, R.color.ink_dim),
            bold = true,
            topMargin = topMargin
        )
        t.letterSpacing = 0.14f
        return t
    }

    fun grow(view: View, weight: Float = 1f): View {
        val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight)
        view.layoutParams = params
        return view
    }

    fun spacer(context: Context, height: Int): View {
        val v = View(context)
        v.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(context, height)
        )
        return v
    }

    fun bar(context: Context, progress: Int, tint: Int): ProgressBar {
        val p = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal)
        p.max = 100
        p.progress = progress.coerceIn(0, 100)
        p.progressDrawable = context.getDrawable(R.drawable.bar)
        p.progressTintList = android.content.res.ColorStateList.valueOf(tint)
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(context, 7)
        )
        params.topMargin = dp(context, 7)
        p.layoutParams = params
        return p
    }

    fun button(context: Context, label: String, onClick: () -> Unit): Button {
        val b = Button(context)
        b.text = label
        b.isAllCaps = false
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        params.topMargin = dp(context, 8)
        b.layoutParams = params
        b.setOnClickListener { onClick() }
        return b
    }

    /** A small pill, used for picking a category on a pending charge. */
    fun chip(context: Context, label: String, tint: Int, onClick: () -> Unit): TextView {
        val t = TextView(context)
        t.text = label
        t.textSize = 12f
        t.setTextColor(color(context, R.color.ink))
        val shape = GradientDrawable()
        shape.shape = GradientDrawable.RECTANGLE
        shape.cornerRadius = dp(context, 999).toFloat()
        shape.setColor(0x22FFFFFF)
        shape.setStroke(dp(context, 1), tint)
        t.background = shape
        val h = dp(context, 9)
        val v = dp(context, 7)
        t.setPadding(h, v, h, v)
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        params.rightMargin = dp(context, 7)
        params.topMargin = dp(context, 7)
        t.layoutParams = params
        t.setOnClickListener { onClick() }
        return t
    }

    fun toggle(
        context: Context,
        label: String,
        checked: Boolean,
        onChange: (Boolean) -> Unit
    ): SwitchCompat {
        val s = SwitchCompat(context)
        s.text = label
        s.textSize = 14f
        s.setTextColor(color(context, R.color.ink))
        s.isChecked = checked
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        params.topMargin = dp(context, 12)
        s.layoutParams = params
        s.setOnCheckedChangeListener { _, value -> onChange(value) }
        return s
    }

    /** A row of chips that wraps onto more lines as needed. */
    fun wrapRow(context: Context): LinearLayout {
        // Simple approach: a vertical stack of horizontal rows, filled by the caller.
        val l = LinearLayout(context)
        l.orientation = LinearLayout.VERTICAL
        l.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        return l
    }

    fun color(context: Context, res: Int): Int = context.getColor(res)
}
