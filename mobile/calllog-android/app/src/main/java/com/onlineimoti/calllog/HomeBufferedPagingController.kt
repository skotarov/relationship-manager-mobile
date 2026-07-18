package com.onlineimoti.calllog

import android.view.View
import com.onlineimoti.calllog.databinding.ActivityHomeBinding

/** Keeps Home buffered paging wiring outside the activity lifecycle class. */
internal class HomeEdgePagingController(
    private val binding: ActivityHomeBinding,
    private val canPrevious: () -> Boolean,
    private val canNext: () -> Boolean,
    private val previousPage: () -> Unit,
    private val nextPage: () -> Unit,
) {
    private var delegate: EdgePageScrollController? = null

    fun bind() {
        if (!PageLoadingModeStore.usesPrefetch(binding.root.context)) {
            delegate?.release()
            delegate = null
            return
        }
        val controller = delegate ?: EdgePageScrollController(
            canPrevious = canPrevious,
            canNext = canNext,
            previousPage = previousPage,
            nextPage = nextPage,
            pageReady = {
                binding.paginationContainer.visibility == View.VISIBLE &&
                    binding.fullLogProgress.visibility != View.VISIBLE
            },
            retainPreviousPages = true,
            protectRetainedPrefix = true,
            pageToken = { binding.pageText.text.toString() },
            prefetchNext = true,
        ).also { delegate = it }
        controller.bind(binding.homeCallsScrollView, binding.homeCallsContainer)
    }

    fun refreshMode() {
        delegate?.release()
        delegate = null
        bind()
    }

    fun cancel() {
        delegate?.cancelPending()
    }

    fun isTransitioning(): Boolean = delegate?.isTransitioning() == true

    fun release() {
        delegate?.release()
        delegate = null
    }
}
