package com.onlineimoti.calllog

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout

/** Keeps each automatic page in its own stable container and appends only at the end. */
internal object HomePagedListUi {
    private const val PAGE_TAG_PREFIX = "relationship_manager_home_page_"

    fun prepare(
        container: LinearLayout,
        automatic: Boolean,
        pageIndex: Int,
        reset: Boolean,
    ) {
        if (reset && (!automatic || pageIndex == 0)) clear(container)
    }

    fun page(
        container: LinearLayout,
        automatic: Boolean,
        pageIndex: Int,
    ): LinearLayout {
        if (!automatic) return container
        pageFor(container, pageIndex)?.let { return it }
        return LinearLayout(container.context).apply {
            tag = pageTag(pageIndex)
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            val footerIndex = (0 until container.childCount)
                .firstOrNull { HomeLoadingFooterUi.isFooter(container.getChildAt(it)) }
                ?: container.childCount
            container.addView(this, footerIndex)
        }
    }

    fun hasRenderedPage(container: LinearLayout, pageIndex: Int): Boolean {
        return pageFor(container, pageIndex)?.childCount?.let { it > 0 } == true
    }

    fun clear(container: LinearLayout) {
        container.removeAllViews()
    }

    fun visiblePageCount(container: LinearLayout): Int {
        return (0 until container.childCount).count { index ->
            val child = container.getChildAt(index)
            !HomeLoadingFooterUi.isFooter(child) && (!isPage(child) || (child as LinearLayout).childCount > 0)
        }
    }

    fun isPage(view: View?): Boolean {
        return view?.tag?.toString()?.startsWith(PAGE_TAG_PREFIX) == true
    }

    private fun pageFor(container: LinearLayout, pageIndex: Int): LinearLayout? {
        val tagValue = pageTag(pageIndex)
        return (0 until container.childCount).firstNotNullOfOrNull { index ->
            (container.getChildAt(index) as? LinearLayout)?.takeIf { it.tag == tagValue }
        }
    }

    private fun pageTag(pageIndex: Int) = "$PAGE_TAG_PREFIX$pageIndex"
}
