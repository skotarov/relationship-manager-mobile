package com.onlineimoti.calllog

import android.os.Handler
import android.view.View
import com.onlineimoti.calllog.databinding.ActivityHomeBinding

/** Owns Home's refresh spinner and the delayed completion for filtered history. */
internal class HomePullRefreshController(
    private val binding: ActivityHomeBinding,
    private val handler: Handler,
    private val isFilteredFullLogMode: () -> Boolean,
) {
    private var inProgress = false
    private val filteredHistoryWatcher = object : Runnable {
        override fun run() {
            if (!inProgress) return
            if (binding.fullLogProgress.visibility == View.VISIBLE) {
                handler.postDelayed(this, FILTERED_REFRESH_CHECK_DELAY_MS)
            } else {
                complete()
            }
        }
    }

    fun bind(onRefresh: () -> Unit) {
        binding.homeCallsRefreshLayout.setOnRefreshListener { request(onRefresh) }
    }

    fun request(refresh: () -> Unit) {
        if (inProgress) return
        inProgress = true
        refresh()
        if (isFilteredFullLogMode()) {
            handler.removeCallbacks(filteredHistoryWatcher)
            handler.post(filteredHistoryWatcher)
        }
    }

    fun complete() {
        if (!inProgress) return
        inProgress = false
        handler.removeCallbacks(filteredHistoryWatcher)
        binding.homeCallsRefreshLayout.setRefreshing(false)
    }

    fun cancel() {
        inProgress = false
        handler.removeCallbacks(filteredHistoryWatcher)
        binding.homeCallsRefreshLayout.setRefreshing(false)
    }

    private companion object {
        const val FILTERED_REFRESH_CHECK_DELAY_MS = 80L
    }
}
