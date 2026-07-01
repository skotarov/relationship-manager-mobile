package com.onlineimoti.calllog

import android.content.res.Resources
import android.graphics.drawable.GradientDrawable

/** Shared dp and rounded-rectangle helpers for Home UI collaborators. */
internal class HomeUiGeometry(resources: Resources) {
    private val density = resources.displayMetrics.density

    fun dp(value: Int): Int = (value * density).toInt()

    fun roundedRect(color: Int, radius: Int, strokeColor: Int, strokeWidth: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius.toFloat()
            setColor(color)
            if (strokeWidth > 0) setStroke(strokeWidth, strokeColor)
        }
}
