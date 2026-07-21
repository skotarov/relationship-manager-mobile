package com.onlineimoti.calllog

import android.view.View
import com.onlineimoti.calllog.databinding.ActivityHomeBinding

internal class HomeEdgePagingController(
    private val binding: ActivityHomeBinding,
    canPrevious: () -> Boolean,
    canNext: () -> Boolean,
    previousPage: () -> Unit,
    nextPage: () -> Unit,
) {
    private val originalPaginationHeight = binding.paginationContainer.layoutParams.height
    private val pageRangeTooltip = HomePageRangeTooltipUi(binding)
    private val stickyGroups = StickyGroupHeaderController(
        binding.homeCallsScrollView,
        binding.homeCallsContainer,
        binding.stickyGroupHeader,
    )
    private val paginationLayoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        hideAutomaticModeNavigation()
    }
    private val delegate = EdgePageScrollController(
        canPrevious = canPrevious,
        canNext = { PageLoadingModeStore.usesPrefetch(binding.root.context) && canNext() },
        previousPage = previousPage,
        nextPage = nextPage,
        pageReady = {
            val prefetch = PageLoadingModeStore.usesPrefetch(binding.root.context)
            HomePageReadyState.isReady() &&
                binding.fullLogProgress.visibility != View.VISIBLE &&
                (prefetch || binding.paginationContainer.visibility == View.VISIBLE)
        },
        retainPreviousPages = true,
        protectRetainedPrefix = true,
        prefetchNext = true,
        onLoadingChanged = { loading ->
            binding.fullLogProgress.visibility = View.GONE
            if (loading) {
                HomeLoadingFooterUi.show(binding.homeCallsContainer)
            } else {
                HomeLoadingFooterUi.hide(binding.homeCallsContainer)
                binding.homeCallsContainer.post {
                    pageRangeTooltip.show(HomePagedListUi.visiblePageCount(binding.homeCallsContainer))
                }
            }
        },
    )

    init {
        binding.paginationContainer.addOnLayoutChangeListener(paginationLayoutListener)
        HomePageReadyState.setOnReady { bind() }
        updateNavigationVisibility()
        stickyGroups.bind()
    }

    fun bind() {
        updateNavigationVisibility()
        delegate.bind(binding.homeCallsScrollView, binding.homeCallsContainer)
        stickyGroups.bind()
    }

    fun cancel() {
        delegate.cancelPending()
        pageRangeTooltip.reset()
    }

    fun isTransitioning(): Boolean = delegate.isTransitioning()

    fun release() {
        binding.paginationContainer.removeOnLayoutChangeListener(paginationLayoutListener)
        HomePageReadyState.clearOnReady()
        delegate.release()
        stickyGroups.release()
        pageRangeTooltip.release()
    }

    private fun updateNavigationVisibility() {
        if (PageLoadingModeStore.usesPrefetch(binding.root.context)) {
            collapseAutomaticModeNavigation()
        } else {
            restoreManualModeNavigationHeight()
            if (
                HomePageReadyState.isReady() &&
                binding.fullLogProgress.visibility != View.VISIBLE
            ) {
                binding.paginationContainer.visibility = View.VISIBLE
            }
        }
    }

    private fun hideAutomaticModeNavigation() {
        if (PageLoadingModeStore.usesPrefetch(binding.root.context)) {
            collapseAutomaticModeNavigation()
        }
    }

    private fun collapseAutomaticModeNavigation() {
        val params = binding.paginationContainer.layoutParams
        if (params.height != 0) {
            params.height = 0
            binding.paginationContainer.layoutParams = params
        }
        binding.paginationContainer.visibility = View.GONE
    }

    private fun restoreManualModeNavigationHeight() {
        val params = binding.paginationContainer.layoutParams
        if (params.height != originalPaginationHeight) {
            params.height = originalPaginationHeight
            binding.paginationContainer.layoutParams = params
        }
    }
}
