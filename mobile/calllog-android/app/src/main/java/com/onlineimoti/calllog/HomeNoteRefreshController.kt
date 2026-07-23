package com.onlineimoti.calllog

import android.os.Handler

/** Refreshes Home once after a note change without starting a polling loop. */
internal class HomeNoteRefreshController(
    private val handler: Handler,
    private val onPrepare: () -> Unit,
    private val onRefresh: () -> Unit,
) {
    private val refreshRunnable = Runnable { onRefresh() }

    fun start() {
        onPrepare()
        handler.removeCallbacks(refreshRunnable)
        handler.post(refreshRunnable)
    }

    fun cancel() {
        handler.removeCallbacks(refreshRunnable)
    }
}
