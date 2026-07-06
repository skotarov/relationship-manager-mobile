package com.onlineimoti.calllog

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.util.AttributeSet
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.button.MaterialButton

/**
 * Shared compact pager button for the Home screens. It uses the same filled,
 * 42dp Material-button treatment as the SMS page and moves the pager to the
 * natural end of the scrolling list rather than pinning it to the screen.
 */
class HomePaginationButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.materialButtonStyle,
) : MaterialButton(context, attrs, defStyleAttr) {
    init {
        isAllCaps = false
        gravity = Gravity.CENTER
        minHeight = 0
        minWidth = 0
        insetTop = 0
        insetBottom = 0
        applySmsStyle()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post {
            movePagerToListEnd()
            applyCompactPagerGeometry()
            applySmsStyle()
        }
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        if (isAttachedToWindow) applySmsStyle()
    }

    private fun movePagerToListEnd() {
        val pager = rootView.findViewById<LinearLayout>(R.id.paginationContainer) ?: return
        val scroll = rootView.findViewById<ScrollView>(R.id.homeCallsScrollView) ?: return
        val list = rootView.findViewById<LinearLayout>(R.id.homeCallsContainer) ?: return
        if (list.parent !== scroll) return

        val oldPagerParent = pager.parent as? ViewGroup ?: return
        val scrollContent = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.removeView(list)
        oldPagerParent.removeView(pager)
        scrollContent.addView(
            list,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        scrollContent.addView(
            pager,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(PAGER_TOP_MARGIN_DP)
                bottomMargin = dp(PAGER_BOTTOM_MARGIN_DP)
            },
        )
        scroll.addView(
            scrollContent,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
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

    private fun applySmsStyle() {
        val enabledFill = context.getColor(R.color.calllog_accent)
        val disabledFill = withAlpha(enabledFill, DISABLED_FILL_ALPHA)
        backgroundTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(enabledFill, disabledFill),
        )
        setTextColor(
            ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_enabled), intArrayOf()),
                intArrayOf(Color.WHITE, withAlpha(Color.WHITE, DISABLED_TEXT_ALPHA)),
            ),
        )
        strokeWidth = 0
    }

    private fun withAlpha(color: Int, alpha: Float): Int = Color.argb(
        (Color.alpha(color) * alpha).toInt().coerceIn(0, 255),
        Color.red(color),
        Color.green(color),
        Color.blue(color),
    )

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val PAGER_HEIGHT_DP = 42
        const val PAGER_TOP_MARGIN_DP = 8
        const val PAGER_BOTTOM_MARGIN_DP = 12
        const val PAGE_LABEL_WEIGHT = 0.8f
        const val PAGE_TEXT_SIZE_SP = 12.5f
        const val DISABLED_FILL_ALPHA = 0.38f
        const val DISABLED_TEXT_ALPHA = 0.38f
    }
}
