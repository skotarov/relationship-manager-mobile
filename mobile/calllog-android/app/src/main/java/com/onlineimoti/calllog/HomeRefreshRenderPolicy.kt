package com.onlineimoti.calllog

/** One-shot rendering hint used only by the delayed refresh of an already visible Call Log. */
internal object HomeRefreshRenderPolicy {
    private var keepExistingRowsOnce = false

    @Synchronized
    fun requestKeepExistingRows() {
        keepExistingRowsOnce = true
    }

    @Synchronized
    fun consumeKeepExistingRows(): Boolean {
        val requested = keepExistingRowsOnce
        keepExistingRowsOnce = false
        return requested
    }

    @Synchronized
    fun clear() {
        keepExistingRowsOnce = false
    }
}
