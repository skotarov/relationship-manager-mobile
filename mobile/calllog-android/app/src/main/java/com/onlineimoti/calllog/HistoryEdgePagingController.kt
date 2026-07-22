package com.onlineimoti.calllog

import android.content.Context
import android.widget.LinearLayout
import android.widget.ScrollView

/** Connects whichever History list is active to shared buffered edge paging. */
internal class HistoryEdgePagingController(
    private val canPrevious: () -> Boolean,
    private val canNext: () -> Boolean,
    private val previousPage: () -> Boolean,
    private val nextPage: () -> Boolean,
    private val resetPage: () -> Unit,
    private val pageReady: () -> Boolean = { true },
) {
    private var appContext: Context? = null
    private val delegate = EdgePageScrollController(
        canPrevious = canPrevious,
        canNext = {
            appContext?.let(PageLoadingModeStore::usesPrefetch) == true && canNext()
        },
        previousPage = previousPage,
        nextPage = nextPage,
        pageReady = pageReady,
        retainPreviousPages = false,
        protectRetainedPrefix = false,
        prefetchNext = true,
    )

    fun reset() {
        delegate.cancelPending()
        resetPage()
    }

    fun bind(scrollView: ScrollView, root: LinearLayout) {
        appContext = root.context.applicationContext
        delegate.bind(scrollView, root)
    }

    fun release() {
        appContext = null
        delegate.release()
    }
}
