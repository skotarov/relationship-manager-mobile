package com.onlineimoti.calllog

/** Coordinates Home paging, phone filtering, CRM calls and CRM contacts. */
internal class HomeTimelineCoordinator(
    private val activity: HomeActivity,
    private val callsLoader: HomeCallsLoader,
    private val contactsLoader: HomeCrmContactsLoader,
    private val serverCallNotes: HomeServerCallNotesController,
    private val searchController: HomeSearchController,
    private val contentRenderer: HomeContentRenderer,
    private val crmFilters: HomeCrmFiltersController,
    private val filteredFullLog: FilteredFullLogController,
    private val pullRefresh: HomePullRefreshController,
    private val timelineToggle: HomeCrmTimelineModeToggle,
    private val activePhoneFilter: () -> String,
    private val setActivePhoneFilter: (String) -> Unit,
    private val activeSearchQuery: () -> String,
    private val pageIndex: () -> Int,
    private val setPageIndex: (Int) -> Unit,
    private val pageSize: () -> Int,
    private val isCrmModeEnabled: () -> Boolean,
    private val isCrmContactsMode: () -> Boolean,
    private val setCrmContactsMode: (Boolean) -> Unit,
    private val onCrmModeChanged: () -> Unit,
    private val requestSmsPermission: () -> Unit,
) {
    fun renderCalls() {
        val callsGeneration = callsLoader.invalidate()
        val contactsGeneration = contactsLoader.invalidate()
        serverCallNotes.invalidate()
        if (activeSearchQuery().isBlank()) searchController.cancelActiveTask()
        val size = pageSize()
        val crmEnabled = isCrmModeEnabled()
        val contactsMode = crmEnabled && isCrmContactsMode()
        val showCrmFilters = crmEnabled && activePhoneFilter().isBlank() && activeSearchQuery().isBlank()
        crmFilters.updateVisibility(showCrmFilters)
        timelineToggle.prepare(showCrmFilters, contactsMode)
        contentRenderer.prepareForRender(size, keepExistingRows = showCrmFilters)
        val contactsOnly = contactsMode && activePhoneFilter().isBlank() && activeSearchQuery().isBlank()
        if (!contactsOnly && !PhoneCallReader.hasCallLogPermission(activity)) {
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
            contactsMode -> contactsLoader.renderAsync(size, contactsGeneration)
            crmEnabled -> callsLoader.renderCrmCallsAsync(size, callsGeneration)
            else -> callsLoader.renderLocalCalls(size)
        }
    }

    fun previousPage() {
        if (isFilteredFullLogMode()) filteredFullLog.previousPage()
        else if (pageIndex() > 0) {
            setPageIndex(pageIndex() - 1)
            renderCalls()
        }
    }

    fun nextPage() {
        if (isFilteredFullLogMode()) filteredFullLog.nextPage()
        else if (contentRenderer.currentCalls.size >= pageSize()) {
            setPageIndex(pageIndex() + 1)
            renderCalls()
        }
    }

    fun setCrmMode(enabled: Boolean) {
        if (!HomeCrmModeStore.setEnabled(activity, enabled)) return
        if (!enabled) setCrmContactsMode(false)
        contentRenderer.clearCalls()
        setActivePhoneFilter("")
        setPageIndex(0)
        filteredFullLog.invalidate()
        onCrmModeChanged()
        renderCalls()
    }

    fun toggleCrmContactsMode() {
        if (!isCrmModeEnabled()) return
        setCrmContactsMode(!isCrmContactsMode())
        contentRenderer.clearCalls()
        setPageIndex(0)
        filteredFullLog.invalidate()
        onCrmModeChanged()
        renderCalls()
    }

    fun togglePhoneFilter(number: String) {
        if (isCrmModeEnabled() && !HomeCallPageLoader.isCrmEligible(activity, number)) return
        val key = HomeCallPageLoader.noteKey(number)
        setActivePhoneFilter(
            if (activePhoneFilter().isNotBlank() && HomeCallPageLoader.noteKey(activePhoneFilter()) == key) "" else number,
        )
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

    private fun isFilteredFullLogMode(): Boolean = activePhoneFilter().isNotBlank() && activeSearchQuery().isBlank()
}
