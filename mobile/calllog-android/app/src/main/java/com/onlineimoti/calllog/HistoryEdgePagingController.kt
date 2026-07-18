package com.onlineimoti.calllog

import android.widget.LinearLayout
import android.widget.ScrollView

/** Connects cumulative History rendering to shared buffered paging. */
internal class HistoryEdgePagingController(
    private val history: CallReportMergedHistoryController,
) {
    private val delegate = EdgePageScrollController(
        canPrevious = history::canPreviousPage,
        canNext = history::canNextPage,
        previousPage = { history.previousPage() },
        nextPage = { history.nextPage() },
        pageReady = { true },
        retainPreviousPages = false,
        protectRetainedPrefix = false,
        prefetchNext = true,
    )

    fun reset() {
        delegate.cancelPending()
        history.resetPage()
    }

    fun bind(scrollView: ScrollView, root: LinearLayout) = delegate.bind(scrollView, root)
    fun release() = delegate.release()
}
