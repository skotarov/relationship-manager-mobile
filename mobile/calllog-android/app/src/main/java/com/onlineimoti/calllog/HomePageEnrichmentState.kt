package com.onlineimoti.calllog

import java.util.concurrent.atomic.AtomicInteger

/** Prevents automatic page advance while the visible Home page is still receiving notes. */
internal object HomePageEnrichmentState {
    private val generation = AtomicInteger(0)
    @Volatile private var currentToken = 0
    @Volatile private var localReady = true
    @Volatile private var serverReady = true

    fun begin(): Int {
        val token = generation.incrementAndGet()
        currentToken = token
        localReady = false
        serverReady = true
        return token
    }

    fun localComplete(token: Int) {
        if (currentToken == token) localReady = true
    }

    fun serverStarted(token: Int) {
        if (currentToken == token) serverReady = false
    }

    fun serverComplete(token: Int) {
        if (currentToken == token) serverReady = true
    }

    fun reset() {
        currentToken = generation.incrementAndGet()
        localReady = true
        serverReady = true
    }

    fun isReady(): Boolean = localReady && serverReady
}
