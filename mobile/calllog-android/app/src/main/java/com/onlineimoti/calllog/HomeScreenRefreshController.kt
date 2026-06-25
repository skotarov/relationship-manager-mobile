package com.onlineimoti.calllog

import android.os.Handler

internal class HomeScreenRefreshController(
    private val handler: Handler,
    private val windowMs: Long,
    private val intervalMs: Long,
    private val onStart: () -> Unit,
    private val onRefresh: () -> Unit,
) {
    private var refreshUntilMs = 0L
    private val runnable = object : Runnable {
        override fun run() {
            if (System.currentTimeMillis() > refreshUntilMs) return
            onRefresh()
            handler.postDelayed(this, intervalMs)
        }
    }

    fun start() {
        onStart()
        refreshUntilMs = System.currentTimeMillis() + windowMs
        handler.removeCallbacks(runnable)
        handler.postDelayed(runnable, intervalMs)
    }

    fun cancel() {
        handler.removeCallbacks(runnable)
    }
}
