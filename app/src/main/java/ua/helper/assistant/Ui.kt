package ua.helper.assistant

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable

/** Кольори й фони для темного інтерфейсу. */
object Ui {
    val BG = Color.parseColor("#0B1020")
    val SURFACE = Color.parseColor("#161C33")
    val SURFACE2 = Color.parseColor("#222B4D")
    val ACCENT = Color.parseColor("#5B8CFF")
    val ACCENT2 = Color.parseColor("#8B5CF6")
    val TEXT = Color.parseColor("#EAF0FF")
    val MUTED = Color.parseColor("#93A0C8")
    val GREEN = Color.parseColor("#34D399")
    val AMBER = Color.parseColor("#FBBF24")

    fun dp(c: Context, v: Int): Int = (v * c.resources.displayMetrics.density).toInt()

    fun rounded(c: Context, color: Int, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(c, radiusDp).toFloat()
        }

    fun gradient(c: Context, from: Int, to: Int, radiusDp: Int): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(from, to)).apply {
            cornerRadius = dp(c, radiusDp).toFloat()
        }

    fun circle(color: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }

    fun circleGradient(from: Int, to: Int): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(from, to)).apply {
            shape = GradientDrawable.OVAL
        }

    fun ring(color: Int, strokePx: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.TRANSPARENT)
            setStroke(strokePx, color)
        }
}
