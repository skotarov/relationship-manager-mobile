package com.onlineimoti.calllog

import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView

/**
 * Keeps already rendered pages in memory and loads the next page before the
 * user reaches the end. Previous pages remain above the current one, so
 * scrolling back never starts another data read.
 */
internal class EdgePageScrollController(
    @Suppress("UNUSED_PARAMETER") canPrevious: () -> Boolean,
    private val canNext: () -> Boolean,
    @Suppress("UNUSED_PARAMETER") previousPage: () -> Unit,
    private val nextPage: () -> Unit,
    private val pageReady: () -> Boolean,
    private val retainPreviousPages: Boolean = true,
    private val protectRetainedPrefix: Boolean = false,
    private val pageToken: () -> Any? = { null },
    private val prefetchNext: Boolean = true,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var scrollView: ScrollView? = null
    private var content: LinearLayout? = null
    private var retainedPages: LinearLayout? = null
    private var pendingNext = false
    private var suppressCallbacks = false
    private var initialPrefetchDone = false
    private var initialChecks = 0
    private var readyChecks = 0
    private var stableChecks = 0
    private var lastChildCount = -1
    private var preservedScrollY = 0
    private var hierarchyScrollY = 0
    private var lastPageToken: Any? = null

    private val hierarchyListener = object : ViewGroup.OnHierarchyChangeListener {
        override fun onChildViewAdded(parent: View?, child: View?) {
            if (suppressCallbacks || pendingNext || !protectRetainedPrefix) return
            if (retainedPages?.parent !== content) schedulePrefixRestore()
        }

        override fun onChildViewRemoved(parent: View?, child: View?) {
            if (suppressCallbacks || pendingNext || !protectRetainedPrefix) return
            if (child === retainedPages) {
                hierarchyScrollY = scrollView?.scrollY ?: 0
                schedulePrefixRestore()
            }
        }
    }

    private val initialPrefetch = object : Runnable {
        override fun run() {
            if (initialPrefetchDone || pendingNext || !prefetchNext) return
            val scroll = scrollView
            val list = content
            val ready = scroll != null && list != null && scroll.isAttachedToWindow &&
                list.childCount > 0 && pageReady()
            if (!ready) {
                if (++initialChecks < MAX_INITIAL_CHECKS) handler.postDelayed(this, READY_CHECK_DELAY_MS)
                return
            }
            initialChecks = 0
            if (canNext()) requestNext()
            else initialPrefetchDone = true
        }
    }

    private val readyCheck = object : Runnable {
        override fun run() {
            if (!pendingNext) return
            val scroll = scrollView
            val list = content
            val freshPageVisible = scroll != null && list != null && scroll.isAttachedToWindow &&
                pageReady() && hasFreshPage(list)
            if (!freshPageVisible) {
                if (++readyChecks < MAX_READY_CHECKS) handler.postDelayed(this, READY_CHECK_DELAY_MS)
                else abortPending()
                return
            }
            val childCount = list!!.childCount
            stableChecks = if (childCount == lastChildCount) stableChecks + 1 else 0
            lastChildCount = childCount
            if (stableChecks < REQUIRED_STABLE_CHECKS) {
                handler.postDelayed(this, READY_CHECK_DELAY_MS)
                return
            }
            finishNextPage(scroll!!, list)
        }
    }

    private val restorePrefix = Runnable {
        val scroll = scrollView ?: return@Runnable
        val list = content ?: return@Runnable
        val prefix = retainedPages ?: return@Runnable
        if (pendingNext || !protectRetainedPrefix || list.childCount == 0 || prefix.parent === list) return@Runnable
        val token = pageToken()
        if (lastPageToken != null && token != lastPageToken) {
            retainedPages = null
            initialPrefetchDone = false
            scheduleInitialPrefetch()
            return@Runnable
        }
        suppressCallbacks = true
        (prefix.parent as? ViewGroup)?.removeView(prefix)
        list.addView(prefix, 0)
        suppressCallbacks = false
        scroll.post { scroll.scrollTo(0, hierarchyScrollY.coerceAtLeast(0)) }
    }

    fun bind(
        scrollView: ScrollView,
        contentView: View,
        @Suppress("UNUSED_PARAMETER") nextPageTop: () -> Int = { 0 },
    ) {
        val list = contentView as? LinearLayout ?: return
        if (this.scrollView !== scrollView) {
            this.scrollView?.setOnScrollChangeListener(null as View.OnScrollChangeListener?)
            this.content?.setOnHierarchyChangeListener(null)
            this.scrollView = scrollView
            this.content = list
            list.setOnHierarchyChangeListener(hierarchyListener)
            scrollView.setOnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
                if (suppressCallbacks || pendingNext || scrollY <= oldScrollY) return@setOnScrollChangeListener
                preservedScrollY = scrollY
                val child = scrollView.getChildAt(0) ?: return@setOnScrollChangeListener
                val remaining = child.height - scrollY - scrollView.height
                val threshold = maxOf(scrollView.height, MIN_PREFETCH_DISTANCE_PX)
                if (remaining <= threshold) requestNext()
            }
        } else if (this.content !== list) {
            this.content?.setOnHierarchyChangeListener(null)
            this.content = list
            list.setOnHierarchyChangeListener(hierarchyListener)
        }
        if (!pendingNext) preservedScrollY = scrollView.scrollY
        if (lastPageToken == null) lastPageToken = pageToken()
        if (pendingNext) scheduleReadyCheck() else scheduleInitialPrefetch()
    }

    fun isTransitioning(): Boolean = pendingNext

    /** Cancels the current paging context, including all retained page views. */
    fun cancelPending() {
        handler.removeCallbacksAndMessages(null)
        pendingNext = false
        initialPrefetchDone = false
        initialChecks = 0
        readyChecks = 0
        stableChecks = 0
        lastChildCount = -1
        retainedPages = null
        lastPageToken = null
        suppressCallbacks = false
    }

    fun release() {
        cancelPending()
        scrollView?.setOnScrollChangeListener(null as View.OnScrollChangeListener?)
        content?.setOnHierarchyChangeListener(null)
        scrollView = null
        content = null
    }

    private fun requestNext() {
        if (pendingNext || !canNext()) return
        val scroll = scrollView ?: return
        val list = content ?: return
        if (retainPreviousPages) retainCurrentPage(list)
        pendingNext = true
        preservedScrollY = scroll.scrollY
        readyChecks = 0
        stableChecks = 0
        lastChildCount = -1
        nextPage()
        scheduleReadyCheck()
    }

    private fun retainCurrentPage(list: LinearLayout) {
        val prefix = retainedPages ?: LinearLayout(list.context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }.also { retainedPages = it }
        val currentChildren = (0 until list.childCount)
            .map { list.getChildAt(it) }
            .filter { it !== prefix }
        if (currentChildren.isEmpty()) return
        val page = LinearLayout(list.context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        suppressCallbacks = true
        currentChildren.forEach { child ->
            (child.parent as? ViewGroup)?.removeView(child)
            page.addView(child)
        }
        (prefix.parent as? ViewGroup)?.removeView(prefix)
        prefix.addView(page)
        if (prefix.parent == null) list.addView(prefix)
        suppressCallbacks = false
    }

    private fun hasFreshPage(list: LinearLayout): Boolean {
        val prefix = retainedPages ?: return list.childCount > 0
        return list.childCount > 1 || list.getChildAt(0) !== prefix
    }

    private fun finishNextPage(scroll: ScrollView, list: LinearLayout) {
        retainedPages?.let { prefix ->
            if (prefix.parent !== list) {
                suppressCallbacks = true
                (prefix.parent as? ViewGroup)?.removeView(prefix)
                list.addView(prefix, 0)
                suppressCallbacks = false
            }
        }
        scroll.post {
            suppressCallbacks = true
            scroll.scrollTo(0, preservedScrollY.coerceAtLeast(0))
            scroll.post {
                suppressCallbacks = false
                pendingNext = false
                initialPrefetchDone = true
                readyChecks = 0
                stableChecks = 0
                lastPageToken = pageToken()
            }
        }
    }

    private fun scheduleInitialPrefetch() {
        if (!prefetchNext || initialPrefetchDone || pendingNext) return
        handler.removeCallbacks(initialPrefetch)
        handler.postDelayed(initialPrefetch, INITIAL_PREFETCH_DELAY_MS)
    }

    private fun scheduleReadyCheck() {
        handler.removeCallbacks(readyCheck)
        handler.post(readyCheck)
    }

    private fun schedulePrefixRestore() {
        handler.removeCallbacks(restorePrefix)
        handler.postDelayed(restorePrefix, PREFIX_RESTORE_DELAY_MS)
    }

    private fun abortPending() {
        handler.removeCallbacks(readyCheck)
        pendingNext = false
        readyChecks = 0
        stableChecks = 0
        lastChildCount = -1
    }

    private companion object {
        const val READY_CHECK_DELAY_MS = 60L
        const val PREFIX_RESTORE_DELAY_MS = 90L
        const val INITIAL_PREFETCH_DELAY_MS = 1_500L
        const val MAX_READY_CHECKS = 160
        const val MAX_INITIAL_CHECKS = 160
        const val REQUIRED_STABLE_CHECKS = 2
        const val MIN_PREFETCH_DISTANCE_PX = 600
    }
}
