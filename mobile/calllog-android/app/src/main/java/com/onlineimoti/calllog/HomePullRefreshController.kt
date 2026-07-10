package com.onlineimoti.calllog

import android.os.Handler
import android.view.View
import com.onlineimoti.calllog.databinding.ActivityHomeBinding

/** Owns Home's refresh spinner and protects it from stuck async reloads. */
internal class HomePullRefreshController(
    private val binding: ActivityHomeBinding,
    private val handler: Handler,
    private val isFilteredFullLogMode: () -> Boolean,
) {
    private var inProgress = false
    private val timeoutWatcher = Runnable { complete() }
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
        if (inProgress) {
            // A second pull while a reload is still finishing must not leave the
            // SwipeRefreshLayout spinner locked on screen.
            complete()
        }
        inProgress = true
        handler.removeCallbacks(timeoutWatcher)
        handler.postDelayed(timeoutWatcher, REFRESH_TIMEOUT_MS)
        runCatching { refresh() }.onFailure { complete() }
        if (isFilteredFullLogMode()) {
            handler.removeCallbacks(filteredHistoryWatcher)
            handler.post(filteredHistoryWatcher)
        }
    }

    fun complete() {
        if (!inProgress) {
            binding.homeCallsRefreshLayout.setRefreshing(false)
            return
        }
        inProgress = false
        handler.removeCallbacks(filteredHistoryWatcher)
        handler.removeCallbacks(timeoutWatcher)
        binding.homeCallsRefreshLayout.setRefreshing(false)
    }

    fun cancel() {
        inProgress = false
        handler.removeCallbacks(filteredHistoryWatcher)
        handler.removeCallbacks(timeoutWatcher)
        binding.homeCallsRefreshLayout.setRefreshing(false)
    }

    private companion object {
        const val FILTERED_REFRESH_CHECK_DELAY_MS = 80L
        const val REFRESH_TIMEOUT_MS = 8_000L
    }
}
