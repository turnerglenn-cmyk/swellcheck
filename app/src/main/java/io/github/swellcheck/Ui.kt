package io.github.swellcheck

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Tiny helpers so screens can be built in code without extra libraries. */
object Ui {
    fun dp(ctx: Context, v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).toInt()

    fun isNight(ctx: Context): Boolean =
        (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    fun text(ctx: Context, s: CharSequence = "", sizeSp: Float = 15f, bold: Boolean = false): TextView =
        TextView(ctx).apply {
            text = s
            setTextColor(Palette().text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            typeface = if (bold) Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD) else Typeface.SANS_SERIF
            setPadding(0, dp(ctx, 2), 0, dp(ctx, 2))
        }

    fun column(ctx: Context, padDp: Int = 0): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(ctx, padDp)
            setPadding(p, p, p, p)
        }

    fun row(ctx: Context): LinearLayout =
        LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }

    fun card(ctx: Context): LinearLayout =
        column(ctx, 16).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(ctx, 16).toFloat()
                setColor(Palette().card)
                setStroke(dp(ctx, 1), 0x554FD1E8) // faint cyan instrument-panel outline
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(ctx, 12) }
        }

    /** Soft pill-shaped button in the warm accent colour. */
    fun button(ctx: Context, label: String, filled: Boolean = true, onClick: () -> Unit): Button =
        Button(ctx).apply {
            text = label
            setAllCaps(false)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            val pal = Palette()
            setTextColor(if (filled) pal.onTile else pal.accent)
            background = GradientDrawable().apply {
                cornerRadius = dp(ctx, 12).toFloat()
                if (filled) setColor(pal.accent) else { setColor(pal.card); setStroke(dp(ctx, 1), pal.accent) }
            }
            stateListAnimator = null
            minHeight = dp(ctx, 44); minimumHeight = dp(ctx, 44)
            setPadding(dp(ctx, 18), 0, dp(ctx, 18), 0)
            setOnClickListener { onClick() }
        }

    /** Small capital heading in the muted colour, e.g. "WAVES" or "LOCATION". */
    fun label(ctx: Context, s: String): TextView =
        text(ctx, s.uppercase(), 11f, bold = true).apply {
            setTextColor(Palette().subtle)
            letterSpacing = 0.08f
        }

    /** A "LABEL   value" line with the label in a fixed-width column. */
    fun kv(ctx: Context, key: String, value: CharSequence): LinearLayout =
        row(ctx).apply {
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(label(ctx, key), LinearLayout.LayoutParams(dp(ctx, 88), LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(text(ctx, value, 14f), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            setPadding(0, dp(ctx, 3), 0, dp(ctx, 3))
        }

    /** Rounded outlined box used behind text fields and dropdowns. */
    fun fieldBackground(ctx: Context): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = dp(ctx, 12).toFloat()
            setColor(Palette().bg)
            setStroke(dp(ctx, 1), Palette().ring)
        }

    fun styleField(v: View) {
        val ctx = v.context
        v.background = fieldBackground(ctx)
        v.setPadding(dp(ctx, 14), dp(ctx, 10), dp(ctx, 14), dp(ctx, 10))
        if (v is TextView) {
            v.setTextColor(Palette().text)
            v.setHintTextColor(Palette().subtle)
            v.typeface = Typeface.SANS_SERIF
        }
    }

    fun spaced(v: View, leftDp: Int = 0, topDp: Int = 0): View {
        val lp = (v.layoutParams as? LinearLayout.LayoutParams)
            ?: LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.leftMargin = dp(v.context, leftDp); lp.topMargin = dp(v.context, topDp)
        v.layoutParams = lp
        return v
    }

    /** "label: value" with the value coloured and bold. */
    fun badge(label: String, value: String, color: Int): CharSequence {
        val b = SpannableStringBuilder()
        b.append(label)
        val start = b.length
        b.append(value)
        b.setSpan(ForegroundColorSpan(color), start, b.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        b.setSpan(StyleSpan(Typeface.BOLD), start, b.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return b
    }

    /** Keep content clear of the status and navigation bars (Android 15 is edge-to-edge). */
    fun applySystemBarPadding(activity: Activity, view: View) {
        val base = dp(activity, 16)
        view.setPadding(base, base, base, base)
        // Only Android 15+ forces apps to draw behind the system bars.
        if (Build.VERSION.SDK_INT < 35) return
        view.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            v.setPadding(base + bars.left, base + bars.top, base + bars.right, base + bars.bottom)
            insets
        }
        view.requestApplyInsets()
    }
}
