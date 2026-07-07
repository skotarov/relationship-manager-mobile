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
    private var fullLogReturnState: FullLogReturnState? = null

    fun renderCalls() {
        val callsGeneration = callsLoader.invalidate()
        val contactsGeneration = contactsLoader.invalidate()
        serverCallNotes.invalidate()
        if (activeSearchQuery().isBlank()) searchController.cancelActiveTask()
        val size = pageSize()
        val crmEnabled = isCrmModeEnabled()
        val remoteReady = CallReportRemoteAccess.isReady(ConfigStore.load(activity))
        val contactsMode = remoteReady && isCrmContactsMode()
        // Search must not replace CRM filters. It searches first, then HomeSearchController
        // reapplies these visible filters over the cached search result set.
        val showCrmFilters = (crmEnabled || contactsMode) && activePhoneFilter().isBlank()
        crmFilters.updateVisibility(showCrmFilters)
        contentRenderer.prepareForRender(size, keepExistingRows = showCrmFilters)
        // Prepare after content controls so the CRM calls page can replace the brand group.
        timelineToggle.prepare(remoteReady, contactsMode)
        // CRM Clients is server-backed and remains usable during a search even
        // on the public Play build, which intentionally has no Call Log permission.
        val contactsOnly = contactsMode && activePhoneFilter().isBlank()
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

    fun isOnLaterPage(): Boolean = if (isFilteredFullLogMode()) {
        filteredFullLog.isOnLaterPage()
    } else {
        pageIndex() > 0
    }

    fun goToFirstPage() {
        if (isFilteredFullLogMode()) {
            filteredFullLog.goToFirstPage()
        } else if (pageIndex() > 0) {
            setPageIndex(0)
            renderCalls()
        }
    }

    fun setCrmMode(enabled: Boolean) {
        if (DistributionCapabilities.isPlayBusinessBuild || !HomeCrmModeStore.setEnabled(activity, enabled)) return
        fullLogReturnState = null
        contentRenderer.clearCalls()
        setActivePhoneFilter("")
        setPageIndex(0)
        filteredFullLog.invalidate()
        onCrmModeChanged()
        renderCalls()
    }

    /** Opens CRM calls from the overflow menu; it is now a link, not an on/off toggle. */
    fun toggleCrmCallLogFromOverflow() {
        if (DistributionCapabilities.isPlayBusinessBuild || !HomeCrmModeStore.isAvailable(activity)) return
        if (isCrmContactsMode()) {
            fullLogReturnState = null
            setCrmContactsMode(false)
            setCrmMode(true)
            return
        }
        if (!isCrmModeEnabled()) setCrmMode(true)
    }

    fun toggleCrmContactsMode() {
        if (DistributionCapabilities.isPlayBusinessBuild || !CallReportRemoteAccess.isReady(ConfigStore.load(activity))) return
        if (isCrmContactsMode()) {
            returnToCallLog()
            return
        }
        fullLogReturnState = null
        setCrmContactsMode(true)
        contentRenderer.clearCalls()
        setPageIndex(0)
        filteredFullLog.invalidate()
        onCrmModeChanged()
        renderCalls()
    }

    /** Returns from CRM contacts or CRM calls to the regular Home call log. */
    fun returnToCallLog(): Boolean {
        if (DistributionCapabilities.isPlayBusinessBuild) return false
        if (isCrmContactsMode()) {
            fullLogReturnState = null
            setCrmContactsMode(false)
            contentRenderer.clearCalls()
            setActivePhoneFilter("")
            setPageIndex(0)
            filteredFullLog.invalidate()
            onCrmModeChanged()
            renderCalls()
            return true
        }
        if (isCrmModeEnabled()) {
            setCrmMode(false)
            return true
        }
        return false
    }

    fun togglePhoneFilter(number: String) {
        if (DistributionCapabilities.isPlayBusinessBuild ||
            (isCrmModeEnabled() && !HomeCallPageLoader.isCrmEligible(activity, number))
        ) return
        val key = HomeCallPageLoader.noteKey(number)
        val currentPhone = activePhoneFilter()
        if (currentPhone.isNotBlank() && HomeCallPageLoader.noteKey(currentPhone) == key) {
            returnFromFullLog()
            return
        }
        if (currentPhone.isBlank()) {
            fullLogReturnState = FullLogReturnState(
                pageIndex = pageIndex(),
                crmContactsMode = isCrmContactsMode(),
            )
        }
        setActivePhoneFilter(number)
        setPageIndex(0)
        filteredFullLog.invalidate()
        onCrmModeChanged()
        renderCalls()
    }

    /**
     * Returns from the filtered full-log screen to the exact Home list state from
     * which it was opened. It is shared by the toolbar arrow and Android Back.
     */
    fun returnFromFullLog(): Boolean {
        if (!isFilteredFullLogMode()) return false
        val returnState = fullLogReturnState
        fullLogReturnState = null
        setActivePhoneFilter("")
        setPageIndex(returnState?.pageIndex ?: 0)
        returnState?.let { setCrmContactsMode(it.crmContactsMode) }
        filteredFullLog.invalidate()
        onCrmModeChanged()
        renderCalls()
        return true
    }

    fun clearPhoneFilter() {
        if (returnFromFullLog()) return
        if (activePhoneFilter().isBlank()) return
        setActivePhoneFilter("")
        setPageIndex(0)
        filteredFullLog.invalidate()
        onCrmModeChanged()
        renderCalls()
    }

    private fun isFilteredFullLogMode(): Boolean = activePhoneFilter().isNotBlank() && activeSearchQuery().isBlank()

    private data class FullLogReturnState(
        val pageIndex: Int,
        val crmContactsMode: Boolean,
    )
}
