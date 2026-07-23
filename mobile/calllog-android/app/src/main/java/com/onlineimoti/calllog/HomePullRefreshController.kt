package com.onlineimoti.calllog

import android.os.Handler
import com.onlineimoti.calllog.databinding.ActivityHomeBinding

/** Owns Home's refresh spinner and protects it from stuck async reloads. */
internal class HomePullRefreshController(
    private val binding: ActivityHomeBinding,
    private val handler: Handler,
) {
    private var inProgress = false
    private val timeoutWatcher = Runnable { complete() }

    fun bind(onRefresh: () -> Unit) {
        binding.homeCallsRefreshLayout.setOnRefreshListener { request(onRefresh) }
    }

    fun request(refresh: () -> Unit) {
        if (inProgress) complete()
        inProgress = true
        handler.removeCallbacks(timeoutWatcher)
        handler.postDelayed(timeoutWatcher, REFRESH_TIMEOUT_MS)
        runCatching { refresh() }.onFailure { complete() }
    }

    fun complete() {
        if (!inProgress) {
            binding.homeCallsRefreshLayout.setRefreshing(false)
            return
        }
        inProgress = false
        handler.removeCallbacks(timeoutWatcher)
        binding.homeCallsRefreshLayout.setRefreshing(false)
    }

    fun cancel() {
        inProgress = false
        handler.removeCallbacks(timeoutWatcher)
        binding.homeCallsRefreshLayout.setRefreshing(false)
    }

    private companion object {
        const val REFRESH_TIMEOUT_MS = 8_000L
    }
}
