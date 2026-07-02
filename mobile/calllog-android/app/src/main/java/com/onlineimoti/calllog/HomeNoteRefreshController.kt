package com.onlineimoti.calllog

import android.os.Handler

/** Temporarily refreshes Home after saving a note, without keeping timer state in the Activity. */
internal class HomeNoteRefreshController(
    private val handler: Handler,
    private val onPrepare: () -> Unit,
    private val onRefresh: () -> Unit,
) {
    private var refreshUntilMs = 0L
    private val refreshRunnable = object : Runnable {
        override fun run() {
            if (System.currentTimeMillis() > refreshUntilMs) return
            onRefresh()
            handler.postDelayed(this, REFRESH_INTERVAL_MS)
        }
    }

    fun start() {
        onPrepare()
        refreshUntilMs = System.currentTimeMillis() + REFRESH_WINDOW_MS
        handler.removeCallbacks(refreshRunnable)
        handler.postDelayed(refreshRunnable, REFRESH_INTERVAL_MS)
    }

    fun cancel() {
        handler.removeCallbacks(refreshRunnable)
    }

    private companion object {
        private const val REFRESH_WINDOW_MS = 2_000L
        private const val REFRESH_INTERVAL_MS = 400L
    }
}
