package com.onlineimoti.calllog

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import java.util.WeakHashMap

/** Keeps the indeterminate Home loader as the final row inside the scrollable list. */
internal object HomeLoadingFooterUi {
    private const val FOOTER_TAG = "relationship_manager_home_loading_footer"
    private val footers = WeakHashMap<LinearLayout, LinearLayout>()

    fun show(container: LinearLayout) {
        footer(container).apply {
            visibility = View.VISIBLE
            moveToEnd(container, this)
        }
    }

    fun hide(container: LinearLayout) {
        footer(container).apply {
            visibility = View.INVISIBLE
            moveToEnd(container, this)
        }
    }

    fun isFooter(view: View?): Boolean = view?.tag == FOOTER_TAG

    fun hasRows(container: LinearLayout): Boolean = (0 until container.childCount)
        .any { index -> !isFooter(container.getChildAt(index)) }

    fun keepLast(container: LinearLayout) {
        footers[container]?.let { footer -> moveToEnd(container, footer) }
    }

    private fun footer(container: LinearLayout): LinearLayout {
        footers[container]?.let { return it }
        val density = container.resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()
        return LinearLayout(container.context).apply {
            tag = FOOTER_TAG
            gravity = Gravity.CENTER
            orientation = LinearLayout.HORIZONTAL
            visibility = View.INVISIBLE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(52),
            )
            addView(
                ProgressBar(container.context, null, android.R.attr.progressBarStyleSmall).apply {
                    isIndeterminate = true
                    layoutParams = LinearLayout.LayoutParams(dp(28), dp(28))
                },
            )
        }.also { created ->
            footers[container] = created
            container.addView(created)
        }
    }

    private fun moveToEnd(container: LinearLayout, footer: LinearLayout) {
        if (footer.parent !== container) {
            (footer.parent as? ViewGroup)?.removeView(footer)
            container.addView(footer)
            return
        }
        if (container.indexOfChild(footer) == container.childCount - 1) return
        container.removeView(footer)
        container.addView(footer)
    }
}
