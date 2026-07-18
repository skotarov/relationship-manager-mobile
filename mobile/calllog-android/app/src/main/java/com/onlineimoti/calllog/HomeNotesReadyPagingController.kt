package com.onlineimoti.calllog

import android.view.View
import com.onlineimoti.calllog.databinding.ActivityHomeBinding

/** Keeps Home buffered paging blocked until the visible page has received its notes. */
internal class HomeEdgePagingController(
    private val binding: ActivityHomeBinding,
    canPrevious: () -> Boolean,
    canNext: () -> Boolean,
    previousPage: () -> Unit,
    nextPage: () -> Unit,
) {
    private val delegate = EdgePageScrollController(
        canPrevious = canPrevious,
        canNext = {
            PageLoadingModeStore.usesPrefetch(binding.root.context) && canNext()
        },
        previousPage = previousPage,
        nextPage = nextPage,
        pageReady = {
            HomePageEnrichmentState.isReady() &&
                binding.paginationContainer.visibility == View.VISIBLE &&
                binding.fullLogProgress.visibility != View.VISIBLE
        },
        retainPreviousPages = true,
        protectRetainedPrefix = true,
        pageToken = { binding.pageText.text.toString() },
        prefetchNext = true,
    )

    fun bind() = delegate.bind(binding.homeCallsScrollView, binding.homeCallsContainer)
    fun cancel() = delegate.cancelPending()
    fun isTransitioning(): Boolean = delegate.isTransitioning()
    fun release() = delegate.release()
}
