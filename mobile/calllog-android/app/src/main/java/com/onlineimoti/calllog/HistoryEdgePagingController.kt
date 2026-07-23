package com.onlineimoti.calllog

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView

/** Connects whichever History list is active to shared buffered edge paging. */
internal class HistoryEdgePagingController(
    private val canPrevious: () -> Boolean,
    private val canNext: () -> Boolean,
    private val previousPage: () -> Boolean,
    private val nextPage: () -> Boolean,
    private val resetPage: () -> Unit,
    private val pageReady: () -> Boolean = { true },
) {
    private var appContext: Context? = null
    private var boundScrollView: ScrollView? = null
    private var boundRoot: LinearLayout? = null
    private var boundToFullLog = false

    private val notesDelegate = EdgePageScrollController(
        canPrevious = canPrevious,
        canNext = {
            appContext?.let(PageLoadingModeStore::usesPrefetch) == true && canNext()
        },
        previousPage = { previousPage(); Unit },
        nextPage = { nextPage(); Unit },
        pageReady = pageReady,
        retainPreviousPages = false,
        protectRetainedPrefix = false,
        prefetchNext = true,
    )

    private val fullLogDelegate = EdgePageScrollController(
        canPrevious = canPrevious,
        canNext = {
            appContext?.let(PageLoadingModeStore::usesPrefetch) == true && canNext()
        },
        previousPage = { previousPage(); Unit },
        nextPage = {
            check(nextPage()) { "The next full History page is no longer available" }
        },
        pageReady = pageReady,
        retainPreviousPages = true,
        protectRetainedPrefix = true,
        prefetchNext = false,
        loadAtBottomOnly = true,
        loadingIndicatorLeadMs = FULL_LOG_LOADER_LEAD_MS,
        onLoadingChanged = { loading -> updateFullLogLoading(loading) },
    )

    fun reset() {
        notesDelegate.cancelPending()
        fullLogDelegate.cancelPending()
        if (boundToFullLog) boundRoot?.let(HomeLoadingFooterUi::hide)
        resetPage()
    }

    fun bind(scrollView: ScrollView, root: LinearLayout) {
        appContext = root.context.applicationContext
        val fullLog = root.tag == ContactNotesFullLogUi.FULL_LOG_ROOT_TAG
        if (boundToFullLog && boundRoot !== root) boundRoot?.let(HomeLoadingFooterUi::hide)
        boundScrollView = scrollView
        boundRoot = root
        boundToFullLog = fullLog
        if (fullLog) {
            notesDelegate.cancelPending()
            fullLogDelegate.bind(scrollView, root)
        } else {
            fullLogDelegate.cancelPending()
            notesDelegate.bind(scrollView, root)
        }
    }

    fun release() {
        notesDelegate.release()
        fullLogDelegate.release()
        if (boundToFullLog) boundRoot?.let(HomeLoadingFooterUi::hide)
        appContext = null
        boundScrollView = null
        boundRoot = null
        boundToFullLog = false
    }

    private fun updateFullLogLoading(loading: Boolean) {
        if (!boundToFullLog) return
        val root = boundRoot ?: return
        if (loading) {
            HomeLoadingFooterUi.show(root)
            boundScrollView?.post { scroll -> scroll.fullScroll(View.FOCUS_DOWN) }
        } else {
            HomeLoadingFooterUi.hide(root)
        }
    }

    private companion object {
        const val FULL_LOG_LOADER_LEAD_MS = 180L
    }
}
