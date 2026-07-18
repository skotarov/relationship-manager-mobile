package com.onlineimoti.calllog

import java.util.concurrent.atomic.AtomicBoolean

/** Tracks whether the visible Home page has finished attaching its notes. */
internal object HomePageReadyState {
    private val ready = AtomicBoolean(false)

    fun markLoading() = ready.set(false)
    fun markReady() = ready.set(true)
    fun isReady(): Boolean = ready.get()
}
