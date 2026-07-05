package com.onlineimoti.calllog

import android.content.res.ColorStateList
import com.google.android.material.button.MaterialButton

/** Keeps disabled Home pagination actions visually unobtrusive while preserving their layout. */
internal object HomePaginationButtonsUi {
    fun apply(button: MaterialButton, enabled: Boolean) {
        button.isEnabled = enabled
        val color = button.context.getColor(
            if (enabled) R.color.calllog_accent else R.color.calllog_bg,
        )
        button.setTextColor(color)
        button.strokeColor = ColorStateList.valueOf(color)
    }

    fun apply(previous: MaterialButton, previousEnabled: Boolean, next: MaterialButton, nextEnabled: Boolean) {
        apply(previous, previousEnabled)
        apply(next, nextEnabled)
    }
}
