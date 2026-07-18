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
        pageToken = { binding.pageText.text.toString() },
        prefetchNext = true,
    )

    init {
        binding.paginationContainer.addOnLayoutChangeListener(paginationLayoutListener)
        HomePageReadyState.setOnReady { bind() }
        updateNavigationVisibility()
    }

    fun bind() {
        updateNavigationVisibility()
        delegate.bind(binding.homeCallsScrollView, binding.homeCallsContainer)
    }

    fun cancel() = delegate.cancelPending()
    fun isTransitioning(): Boolean = delegate.isTransitioning()

    fun release() {
        binding.paginationContainer.removeOnLayoutChangeListener(paginationLayoutListener)
        HomePageReadyState.clearOnReady()
        delegate.release()
    }

    private fun updateNavigationVisibility() {
        if (PageLoadingModeStore.usesPrefetch(binding.root.context)) {
            hideAutomaticModeNavigation()
        } else if (
            HomePageReadyState.isReady() &&
            binding.fullLogProgress.visibility != View.VISIBLE
        ) {
            binding.paginationContainer.visibility = View.VISIBLE
        }
    }

    private fun hideAutomaticModeNavigation() {
        if (
            PageLoadingModeStore.usesPrefetch(binding.root.context) &&
            binding.paginationContainer.visibility != View.GONE
        ) {
            binding.paginationContainer.visibility = View.GONE
        }
    }
}
