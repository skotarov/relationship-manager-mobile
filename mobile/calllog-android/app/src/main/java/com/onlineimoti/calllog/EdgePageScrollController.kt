package com.onlineimoti.calllog

import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ScrollView

/**
 * Turns existing page buttons into bidirectional edge paging without loading the
 * complete history. Scrolling to the bottom opens the next page; reaching the
 * top while scrolling upward opens the previous page.
 */
internal class EdgePageScrollController(
    private val canPrevious: () -> Boolean,
    private val canNext: () -> Boolean,
    private val previousPage: () -> Unit,
    private val nextPage: () -> Unit,
    private val pageReady: () -> Boolean,
) {
    private enum class Direction { PREVIOUS, NEXT }

    private val handler = Handler(Looper.getMainLooper())
    private var scrollView: ScrollView? = null
    private var contentView: View? = null
    private var nextPageTop: () -> Int = { 0 }
    private var pendingDirection: Direction? = null
    private var suppressScrollEvents = false
    private var readyChecks = 0

    private val readyCheck = object : Runnable {
        override fun run() {
            val direction = pendingDirection ?: return
            val scroll = scrollView
            if (scroll == null || !scroll.isAttachedToWindow || !pageReady()) {
                if (++readyChecks < MAX_READY_CHECKS) handler.postDelayed(this, READY_CHECK_DELAY_MS)
                else cancelPending()
                return
            }
            scroll.post {
                if (pendingDirection != direction || scrollView !== scroll) return@post
                suppressScrollEvents = true
                val targetY = if (direction == Direction.NEXT) {
                    nextPageTop().coerceAtLeast(0)
                } else {
                    val contentHeight = contentView?.height ?: scroll.getChildAt(0)?.height ?: 0
                    (contentHeight - scroll.height).coerceAtLeast(0)
                }
                scroll.scrollTo(0, targetY)
                scroll.post {
                    suppressScrollEvents = false
                    pendingDirection = null
                    readyChecks = 0
                }
            }
        }
    }

    fun bind(
        scrollView: ScrollView,
        contentView: View,
        nextPageTop: () -> Int = { 0 },
    ) {
        if (this.scrollView !== scrollView) {
            this.scrollView?.setOnScrollChangeListener(null as View.OnScrollChangeListener?)
            this.scrollView = scrollView
            scrollView.setOnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
                if (suppressScrollEvents || pendingDirection != null || scrollY == oldScrollY) return@setOnScrollChangeListener
                when {
                    scrollY > oldScrollY && !scrollView.canScrollVertically(1) -> request(Direction.NEXT)
                    scrollY < oldScrollY && !scrollView.canScrollVertically(-1) -> request(Direction.PREVIOUS)
                }
            }
        }
        this.contentView = contentView
        this.nextPageTop = nextPageTop
        if (pendingDirection != null) scheduleReadyCheck()
    }

    fun isTransitioning(): Boolean = pendingDirection != null

    fun cancelPending() {
        handler.removeCallbacks(readyCheck)
        pendingDirection = null
        readyChecks = 0
        suppressScrollEvents = false
    }

    fun release() {
        cancelPending()
        scrollView?.setOnScrollChangeListener(null as View.OnScrollChangeListener?)
        scrollView = null
        contentView = null
    }

    private fun request(direction: Direction) {
        if (pendingDirection != null) return
        val allowed = if (direction == Direction.PREVIOUS) canPrevious() else canNext()
        if (!allowed) return
        pendingDirection = direction
        readyChecks = 0
        if (direction == Direction.PREVIOUS) previousPage() else nextPage()
        scheduleReadyCheck()
    }

    private fun scheduleReadyCheck() {
        handler.removeCallbacks(readyCheck)
        handler.post(readyCheck)
    }

    private companion object {
        const val READY_CHECK_DELAY_MS = 50L
        const val MAX_READY_CHECKS = 120
    }
}
