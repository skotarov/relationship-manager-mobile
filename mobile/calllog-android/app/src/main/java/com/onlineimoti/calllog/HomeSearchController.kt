package com.onlineimoti.calllog

import android.content.Context
import android.os.Handler
import android.view.View
import com.onlineimoti.calllog.databinding.ActivityHomeBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger

internal class HomeSearchController(
    private val context: Context,
    private val binding: ActivityHomeBinding,
    private val handler: Handler,
    private val searchExecutor: ExecutorService,
    private val searchGeneration: AtomicInteger,
    private val serverCallNotes: HomeServerCallNotesController,
    private val pageSize: () -> Int,
    private val activePhoneFilter: () -> String,
    private val activeSearchQuery: () -> String,
    private val isCrmModeEnabled: () -> Boolean,
    private val pageIndex: () -> Int,
    private val setCurrentCalls: (List<PhoneCallRecord>) -> Unit,
    private val renderEmptyState: () -> Unit,
    private val applyRenderData: (HomeRenderData, Int) -> Unit,
    private val onRenderComplete: () -> Unit,
) {
    private var activeTask: Future<*>? = null
    private var busyToken = 0L
    private val dataSource = HomeSearchDataSource(context)

    fun renderSearchCallsAsync() {
        val query = activeSearchQuery()
        val currentPageSize = pageSize()
        if (HomeCallPageLoader.isSearchTooShort(query)) {
            cancelActiveTask()
            setCurrentCalls(emptyList())
            showSearchStatus(context.getString(R.string.dynamic_home_search_minimum))
            hideInlineStatusForSearch()
            binding.previousCallsButton.isEnabled = false
            binding.nextCallsButton.isEnabled = false
            binding.pageText.text = context.getString(R.string.dynamic_home_page, pageIndex() + 1)
            binding.paginationContainer.visibility = View.VISIBLE
            onRenderComplete()
            return
        }

        cancelActiveTask()
        val activity = binding.root.context as? HomeActivity
        busyToken = activity?.let { HomeBusyTooltipUi.begin(it, HomeBusyWork.SEARCH) } ?: 0L
        val requestBusyToken = busyToken
        val generation = searchGeneration.incrementAndGet()
        val phoneFilter = activePhoneFilter()
        val crmMode = isCrmModeEnabled()
        val crmContactsMode = HomeCrmTimelineModeToggle.isContactsMode()
        val usesCrmFilters = phoneFilter.isBlank() && (crmMode || crmContactsMode)
        val filterScope = if (usesCrmFilters) HomeCrmFilterStore.scopeForContactsMode(crmContactsMode) else null
        val filterState = filterScope?.let { HomeCrmFilterStore.load(context, it) }
        val page = pageIndex()
        showSearchStatus(context.getString(R.string.dynamic_home_searching, query.trim()))
        hideInlineStatusForSearch()
        binding.previousCallsButton.isEnabled = false
        binding.nextCallsButton.isEnabled = false
        binding.paginationContainer.visibility = View.VISIBLE

        activeTask = searchExecutor.submit {
            if (Thread.currentThread().isInterrupted) return@submit
            val localRawResults = dataSource.searchLocalResultsFor(
                query = query,
                phoneFilter = phoneFilter,
                crmContactsMode = crmContactsMode,
                filterState = filterState,
            )
            if (Thread.currentThread().isInterrupted) return@submit
            val localResults = filteredResultsFor(localRawResults, crmContactsMode, filterState)
            val localSections = HomeSearchResultSectionComposer.compose(localResults, emptyList())
            val localRenderData = renderDataFor(localSections.combined, crmContactsMode, page, currentPageSize)
            if (Thread.currentThread().isInterrupted) return@submit
            handler.post {
                finishBusy(requestBusyToken)
                if (!isCurrentSearch(generation, query, phoneFilter, crmMode, crmContactsMode, page, filterState, filterScope)) return@post
                renderLocalResults(localRenderData, localSections.phone.size, currentPageSize)
                onRenderComplete()
            }

            val serverOutcome = dataSource.searchServerResultsFor(
                query = query,
                phoneFilter = phoneFilter,
                crmMode = crmMode || crmContactsMode,
                crmContactsMode = crmContactsMode,
            )
            if (Thread.currentThread().isInterrupted) return@submit
            val serverResults = when (serverOutcome) {
                is HomeServerSearchResult.Success -> filteredResultsFor(serverOutcome.calls, crmContactsMode, filterState)
                HomeServerSearchResult.Failure -> emptyList()
            }
            val sections = HomeSearchResultSectionComposer.compose(localResults, serverResults)
            val mergedRenderData = renderDataFor(sections.combined, crmContactsMode, page, currentPageSize)
            handler.post {
                if (!isCurrentSearch(generation, query, phoneFilter, crmMode, crmContactsMode, page, filterState, filterScope)) return@post
                renderCompletedResults(
                    data = mergedRenderData,
                    localCount = sections.phone.size,
                    serverCount = sections.server.size,
                    serverAvailable = serverOutcome !is HomeServerSearchResult.Failure,
                    pageSize = currentPageSize,
                    page = page,
                    generation = generation,
                    query = query,
                    phoneFilter = phoneFilter,
                    crmMode = crmMode,
                    crmContactsMode = crmContactsMode,
                    filterState = filterState,
                    filterScope = filterScope,
                )
            }
        }
    }

    private fun filteredResultsFor(
        rawResults: List<PhoneCallRecord>,
        crmContactsMode: Boolean,
        filterState: HomeCrmFilterState?,
    ): List<PhoneCallRecord> = when {
        crmContactsMode -> rawResults
        filterState != null -> dataSource.filterCrmSearchResults(rawResults, filterState)
        else -> rawResults
    }

    private fun renderDataFor(
        filteredResults: List<PhoneCallRecord>,
        crmContactsMode: Boolean,
        page: Int,
        currentPageSize: Int,
    ): HomeRenderData {
        val calls = if (crmContactsMode) {
            filteredResults.drop(page * currentPageSize).take(currentPageSize)
        } else {
            TimelinePageMode.page(context, filteredResults, page, currentPageSize) { row -> TimelineGroupKeys.day(row.startedAt) }
        }
        val clientServerNotes = if (crmContactsMode) {
            HomeCrmClientServerNotes.snapshot(context.applicationContext, calls)
        } else HomeCrmClientServerNotesSnapshot()
        return HomeRenderData(
            calls = calls,
            contactNotesByNumber = dataSource.contactNotesForRenderedSearch(calls, crmContactsMode)
                .toMutableMap().apply { putAll(clientServerNotes.contactNotesByNumber) },
            contactAddressesByNumber = ContactAddressRepository.forCalls(context, calls),
            contactNamesByNumber = HomeCallPageLoader.contactNames(context, calls),
            callNotesByCall = HomeCallNotesResolver.localNotes(context, calls) + clientServerNotes.callNotesByCall,
        )
    }

    private fun renderLocalResults(data: HomeRenderData, count: Int, pageSize: Int) {
        if (data.calls.isNotEmpty()) applyRenderData(data, pageSize)
        else setCurrentCalls(emptyList())
        hideInlineStatusForSearch()
        showSearchLocalCount(count)
    }

    private fun renderCompletedResults(
        data: HomeRenderData,
        localCount: Int,
        serverCount: Int,
        serverAvailable: Boolean,
        pageSize: Int,
        page: Int,
        generation: Int,
        query: String,
        phoneFilter: String,
        crmMode: Boolean,
        crmContactsMode: Boolean,
        filterState: HomeCrmFilterState?,
        filterScope: HomeCrmFilterStore.Scope?,
    ) {
        if (data.calls.isEmpty()) {
            setCurrentCalls(emptyList())
            if (!(PageLoadingModeStore.usesPrefetch(context) && page > 0)) HomePagedListUi.clear(binding.homeCallsContainer)
            renderEmptyState()
        } else {
            applyRenderData(data, pageSize)
            serverCallNotes.enrichAsync(data) { enriched ->
                if (isCurrentSearch(generation, query, phoneFilter, crmMode, crmContactsMode, page, filterState, filterScope)) {
                    applyRenderData(enriched, pageSize)
                    showSearchSourceCounts(localCount, serverCount, serverAvailable)
                }
            }
        }
        hideInlineStatusForSearch()
        showSearchSourceCounts(localCount, serverCount, serverAvailable)
    }

    private fun isCurrentSearch(
        generation: Int,
        query: String,
        phoneFilter: String,
        crmMode: Boolean,
        crmContactsMode: Boolean,
        page: Int,
        filterState: HomeCrmFilterState?,
        filterScope: HomeCrmFilterStore.Scope?,
    ): Boolean = generation == searchGeneration.get() &&
        query == activeSearchQuery() &&
        phoneFilter == activePhoneFilter() &&
        crmMode == isCrmModeEnabled() &&
        crmContactsMode == HomeCrmTimelineModeToggle.isContactsMode() &&
        page == pageIndex() &&
        isFilterStateCurrent(filterState, filterScope)

    fun cancelActiveTask() {
        activeTask?.cancel(true)
        activeTask = null
        searchGeneration.incrementAndGet()
        finishBusy(busyToken)
        if (activeSearchQuery().isBlank()) hideSearchStatus()
    }

    private fun finishBusy(token: Long) {
        if (token <= 0L) return
        if (busyToken == token) busyToken = 0L
        (binding.root.context as? HomeActivity)?.let { HomeBusyTooltipUi.end(it, token) }
    }

    private fun isFilterStateCurrent(
        expected: HomeCrmFilterState?,
        scope: HomeCrmFilterStore.Scope?,
    ): Boolean {
        if (expected == null) return true
        return scope != null && expected == HomeCrmFilterStore.load(context, scope)
    }

    private fun showSearchCount(count: Int) {
        if (activeSearchQuery().isBlank()) return
        showSearchStatus(context.getString(R.string.runtime_search_found_count, count))
    }

    private fun showSearchLocalCount(count: Int) {
        if (activeSearchQuery().isBlank()) return
        showSearchStatus(context.getString(R.string.runtime_search_local_then_server, count))
    }

    private fun showSearchSourceCounts(localCount: Int, serverCount: Int, serverAvailable: Boolean) {
        if (activeSearchQuery().isBlank()) return
        val text = if (serverAvailable) {
            context.getString(R.string.runtime_search_source_counts, localCount, serverCount)
        } else {
            context.getString(R.string.runtime_search_server_unavailable, localCount)
        }
        showSearchStatus(text)
    }

    private fun showSearchStatus(text: String) {
        binding.searchStatusText.text = text
        binding.searchStatusText.visibility = View.VISIBLE
    }

    private fun hideSearchStatus() {
        binding.searchStatusText.text = ""
        binding.searchStatusText.visibility = View.GONE
    }

    private fun hideInlineStatusForSearch() {
        binding.homeStatusText.text = ""
        binding.homeStatusText.visibility = View.GONE
    }
}

internal data class HomeRenderData(
    val calls: List<PhoneCallRecord>,
    val contactNotesByNumber: Map<String, String>,
    val contactNamesByNumber: Map<String, String>,
    val callNotesByCall: Map<String, HomeCallNote> = emptyMap(),
    val contactAddressesByNumber: Map<String, List<String>> = emptyMap(),
)
