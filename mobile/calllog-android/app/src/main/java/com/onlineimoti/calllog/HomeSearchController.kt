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
            val rawResults = dataSource.searchResultsFor(
                query = query,
                phoneFilter = phoneFilter,
                crmMode = crmMode || crmContactsMode,
                crmContactsMode = crmContactsMode,
                filterState = filterState,
            )
            if (Thread.currentThread().isInterrupted) return@submit
            val filteredResults = when {
                // The Clients page search is already filtered by the server using
                // the current owner/profile, phase and company filters. Do not run
                // a second local filter over a partial client list.
                crmContactsMode -> rawResults
                filterState != null -> dataSource.filterCrmSearchResults(rawResults, filterState)
                else -> rawResults
            }
            val calls = filteredResults
                .drop(page * currentPageSize)
                .take(currentPageSize)
            val localCallNotes = HomeCallNotesResolver.localNotes(context, calls)
            val clientServerNotes = if (crmContactsMode) {
                HomeCrmClientServerNotes.snapshot(context.applicationContext, calls)
            } else {
                HomeCrmClientServerNotesSnapshot()
            }
            val contactNotes = dataSource.contactNotesForRenderedSearch(calls, crmContactsMode)
                .toMutableMap()
                .apply { putAll(clientServerNotes.contactNotesByNumber) }
            val renderData = HomeRenderData(
                calls = calls,
                contactNotesByNumber = contactNotes,
                contactNamesByNumber = HomeCallPageLoader.contactNames(context, calls),
                callNotesByCall = localCallNotes + clientServerNotes.callNotesByCall,
            )
            if (Thread.currentThread().isInterrupted) return@submit
            handler.post {
                if (generation != searchGeneration.get()) return@post
                if (
                    query != activeSearchQuery() ||
                    phoneFilter != activePhoneFilter() ||
                    crmMode != isCrmModeEnabled() ||
                    crmContactsMode != HomeCrmTimelineModeToggle.isContactsMode() ||
                    page != pageIndex() ||
                    !isFilterStateCurrent(filterState, filterScope)
                ) {
                    return@post
                }
                setCurrentCalls(renderData.calls)
                if (renderData.calls.isEmpty()) {
                    binding.homeCallsContainer.removeAllViews()
                    renderEmptyState()
                    hideInlineStatusForSearch()
                } else {
                    applyRenderData(renderData, currentPageSize)
                    hideInlineStatusForSearch()
                    serverCallNotes.enrichAsync(renderData) { enriched ->
                        if (generation != searchGeneration.get()) return@enrichAsync
                        if (
                            query != activeSearchQuery() ||
                            phoneFilter != activePhoneFilter() ||
                            crmMode != isCrmModeEnabled() ||
                            crmContactsMode != HomeCrmTimelineModeToggle.isContactsMode() ||
                            page != pageIndex() ||
                            !isFilterStateCurrent(filterState, filterScope)
                        ) {
                            return@enrichAsync
                        }
                        applyRenderData(enriched, currentPageSize)
                        hideInlineStatusForSearch()
                        showSearchCount(filteredResults.size)
                    }
                }
                showSearchCount(filteredResults.size)
                onRenderComplete()
            }
        }
    }

    fun cancelActiveTask() {
        activeTask?.cancel(true)
        activeTask = null
        // Invalidate a callback that may have been posted immediately before
        // cancellation, such as when Home goes to the background.
        searchGeneration.incrementAndGet()
        if (activeSearchQuery().isBlank()) hideSearchStatus()
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
)
