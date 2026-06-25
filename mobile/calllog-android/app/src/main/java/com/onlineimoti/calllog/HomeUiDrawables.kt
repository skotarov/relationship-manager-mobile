package com.onlineimoti.calllog

import android.graphics.drawable.GradientDrawable

internal fun homeRoundedRect(
    color: Int,
    radius: Int,
    strokeColor: Int,
    strokeWidth: Int,
): GradientDrawable {
    return GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radius.toFloat()
        setColor(color)
        if (strokeWidth > 0) setStroke(strokeWidth, strokeColor)
    }
}
