package com.onlineimoti.calllog

/** Coordinates Home paging, phone filtering, CRM mode and timeline rendering. */
internal class HomeTimelineCoordinator(
    private val activity: HomeActivity,
    private val callsLoader: HomeCallsLoader,
    private val serverCallNotes: HomeServerCallNotesController,
    private val searchController: HomeSearchController,
    private val contentRenderer: HomeContentRenderer,
    private val crmFilters: HomeCrmFiltersController,
    private val filteredFullLog: FilteredFullLogController,
    private val pullRefresh: HomePullRefreshController,
    private val activePhoneFilter: () -> String,
    private val setActivePhoneFilter: (String) -> Unit,
    private val activeSearchQuery: () -> String,
    private val pageIndex: () -> Int,
    private val setPageIndex: (Int) -> Unit,
    private val pageSize: () -> Int,
    private val isCrmModeEnabled: () -> Boolean,
    private val requestSmsPermission: () -> Unit,
) {
    fun renderCalls() {
        val renderGeneration = callsLoader.invalidate()
        serverCallNotes.invalidate()
        if (activeSearchQuery().isBlank()) searchController.cancelActiveTask()
        val size = pageSize()
        val crmModeEnabled = isCrmModeEnabled()
        val showCrmFilters = crmModeEnabled && activePhoneFilter().isBlank() && activeSearchQuery().isBlank()
        crmFilters.updateVisibility(showCrmFilters)
        contentRenderer.prepareForRender(size, keepExistingRows = showCrmFilters)
        if (!PhoneCallReader.hasCallLogPermission(activity)) {
            contentRenderer.showMissingCallLogPermission()
            pullRefresh.complete()
            return
        }
        when {
            activeSearchQuery().isNotBlank() -> searchController.renderSearchCallsAsync()
            activePhoneFilter().isNotBlank() -> {
                requestSmsPermission()
                filteredFullLog.render(activePhoneFilter())
            }
            crmModeEnabled -> callsLoader.renderCrmCallsAsync(size, renderGeneration)
            else -> callsLoader.renderLocalCalls(size)
        }
    }

    fun previousPage() {
        if (isFilteredFullLogMode()) {
            filteredFullLog.previousPage()
        } else if (pageIndex() > 0) {
            setPageIndex(pageIndex() - 1)
            renderCalls()
        }
    }

    fun nextPage() {
        if (isFilteredFullLogMode()) {
            filteredFullLog.nextPage()
        } else if (contentRenderer.currentCalls.size >= pageSize()) {
            setPageIndex(pageIndex() + 1)
            renderCalls()
        }
    }

    fun setCrmMode(enabled: Boolean) {
        if (!HomeCrmModeStore.setEnabled(activity, enabled)) return
        contentRenderer.clearCalls()
        setActivePhoneFilter("")
        setPageIndex(0)
        filteredFullLog.invalidate()
        renderCalls()
    }

    fun togglePhoneFilter(number: String) {
        if (isCrmModeEnabled() && !HomeCallPageLoader.isCrmEligible(activity, number)) return
        val key = HomeCallPageLoader.noteKey(number)
        val nextPhone = if (
            activePhoneFilter().isNotBlank() && HomeCallPageLoader.noteKey(activePhoneFilter()) == key
        ) {
            ""
        } else {
            number
        }
        setActivePhoneFilter(nextPhone)
        setPageIndex(0)
        filteredFullLog.invalidate()
        renderCalls()
    }

    fun clearPhoneFilter() {
        if (activePhoneFilter().isBlank()) return
        setActivePhoneFilter("")
        setPageIndex(0)
        filteredFullLog.invalidate()
        renderCalls()
    }

    private fun isFilteredFullLogMode(): Boolean {
        return activePhoneFilter().isNotBlank() && activeSearchQuery().isBlank()
    }
}
