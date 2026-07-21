package com.onlineimoti.calllog

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import android.widget.ScrollView

/** Overlay viewport that reports the nested ScrollView state to PullToRefreshLayout. */
internal class StickyScrollViewport @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {
    override fun canScrollVertically(direction: Int): Boolean {
        val scroll = nestedScrollView(this)
        return scroll?.canScrollVertically(direction) ?: super.canScrollVertically(direction)
    }

    private fun nestedScrollView(view: View): ScrollView? {
        if (view is ScrollView) return view
        val group = view as? FrameLayout ?: return null
        for (index in 0 until group.childCount) {
            nestedScrollView(group.getChildAt(index))?.let { return it }
        }
        return null
    }
}
