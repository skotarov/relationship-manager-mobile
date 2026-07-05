package com.onlineimoti.calllog

import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import com.google.android.material.button.MaterialButton

/** Outlined pagination button that disappears visually while it is unavailable. */
class HomePaginationButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.materialButtonStyle,
) : MaterialButton(context, attrs, defStyleAttr) {
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        refreshColors()
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        if (isAttachedToWindow) refreshColors()
    }

    private fun refreshColors() {
        val color = context.getColor(
            if (isEnabled) R.color.calllog_accent else R.color.calllog_bg,
        )
        setTextColor(color)
        strokeColor = ColorStateList.valueOf(color)
    }
}
