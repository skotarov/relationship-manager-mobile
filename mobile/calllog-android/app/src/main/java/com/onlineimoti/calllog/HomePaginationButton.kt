package com.onlineimoti.calllog

import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton

/** Compact shared pager button, styled to match the SMS screen navigator. */
class HomePaginationButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.materialButtonStyle,
) : MaterialButton(context, attrs, defStyleAttr) {
    init {
        minHeight = dp(PAGER_HEIGHT_DP)
        minWidth = 0
        insetTop = 0
        insetBottom = 0
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        refreshColors()
        post {
            applyCompactPagerGeometry()
            refreshColors()
        }
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        if (isAttachedToWindow) refreshColors()
    }

    private fun applyCompactPagerGeometry() {
        val pager = parent as? LinearLayout ?: return
        if (pager.id != R.id.paginationContainer) return

        (layoutParams as? LinearLayout.LayoutParams)?.let { params ->
            params.height = dp(PAGER_HEIGHT_DP)
            params.width = 0
            params.weight = 1f
            layoutParams = params
        }
        pager.findViewById<TextView>(R.id.pageText)?.let { pageText ->
            (pageText.layoutParams as? LinearLayout.LayoutParams)?.let { params ->
                params.width = 0
                params.weight = PAGE_LABEL_WEIGHT
                params.marginStart = 0
                params.marginEnd = 0
                pageText.layoutParams = params
            }
            pageText.gravity = Gravity.CENTER
            pageText.textSize = PAGE_TEXT_SIZE_SP
        }
    }

    private fun refreshColors() {
        val color = context.getColor(
            if (isEnabled) R.color.calllog_accent else R.color.calllog_bg,
        )
        setTextColor(color)
        strokeColor = ColorStateList.valueOf(color)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val PAGER_HEIGHT_DP = 42
        const val PAGE_LABEL_WEIGHT = 0.8f
        const val PAGE_TEXT_SIZE_SP = 12.5f
    }
}
