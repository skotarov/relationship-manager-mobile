package com.onlineimoti.calllog

import java.util.concurrent.atomic.AtomicBoolean

/** Tracks whether the visible Home page has finished attaching its notes. */
internal object HomePageReadyState {
    private val ready = AtomicBoolean(false)
    @Volatile private var onReady: (() -> Unit)? = null

    fun markLoading() = ready.set(false)
    fun markReady() {
        ready.set(true)
        onReady?.invoke()
    }
    fun isReady(): Boolean = ready.get()
    fun setOnReady(listener: () -> Unit) { onReady = listener }
    fun clearOnReady() { onReady = null }
}
