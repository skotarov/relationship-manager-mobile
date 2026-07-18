package com.onlineimoti.calllog

import android.content.Context
import android.widget.LinearLayout
import android.widget.ScrollView

/** Connects cumulative History rendering to shared buffered paging. */
internal class HistoryEdgePagingController(
    context: Context,
    private val history: CallReportMergedHistoryController,
) {
    private val appContext = context.applicationContext
    private val delegate = EdgePageScrollController(
        canPrevious = history::canPreviousPage,
        canNext = {
            PageLoadingModeStore.usesPrefetch(appContext) && history.canNextPage()
        },
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
