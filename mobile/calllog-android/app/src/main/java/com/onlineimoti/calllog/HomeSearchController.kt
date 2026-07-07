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
    private val cachedSearchLock = Any()
    private var cachedSearch: CachedSearch? = null

    fun renderSearchCallsAsync() {
        val query = activeSearchQuery()
        val currentPageSize = pageSize()
        if (HomeCallPageLoader.isSearchTooShort(query)) {
            cancelActiveTask()
            setCurrentCalls(emptyList())
            binding.homeStatusText.text = context.getString(R.string.dynamic_home_search_minimum)
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
        val filterState = if (usesCrmFilters) HomeCrmFilterStore.load(context) else null
        val page = pageIndex()
        binding.homeStatusText.text = context.getString(R.string.dynamic_home_searching, query.trim())
        binding.homeStatusText.visibility = View.VISIBLE
        binding.previousCallsButton.isEnabled = false
        binding.nextCallsButton.isEnabled = false
        binding.paginationContainer.visibility = View.VISIBLE

        activeTask = searchExecutor.submit {
            if (Thread.currentThread().isInterrupted) return@submit
            val rawResults = searchResultsFor(
                query = query,
                phoneFilter = phoneFilter,
                crmMode = crmMode || crmContactsMode,
                crmContactsMode = crmContactsMode,
            )
            if (Thread.currentThread().isInterrupted) return@submit
            val filteredResults = if (filterState != null) filterCrmSearchResults(rawResults, filterState) else rawResults
            val calls = filteredResults
                .drop(page * currentPageSize)
                .take(currentPageSize)
            val renderData = HomeRenderData(
                calls = calls,
                contactNotesByNumber = HomeCallPageLoader.contactNotes(context, calls),
                contactNamesByNumber = HomeCallPageLoader.contactNames(context, calls),
                callNotesByCall = HomeCallNotesResolver.localNotes(context, calls),
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
                    (filterState != null && filterState != HomeCrmFilterStore.load(context))
                ) {
                    return@post
                }
                setCurrentCalls(renderData.calls)
                if (renderData.calls.isEmpty()) {
                    binding.homeCallsContainer.removeAllViews()
                    renderEmptyState()
                } else {
                    applyRenderData(renderData, currentPageSize)
                    serverCallNotes.enrichAsync(renderData) { enriched ->
                        if (generation != searchGeneration.get()) return@enrichAsync
                        if (
                            query != activeSearchQuery() ||
                            phoneFilter != activePhoneFilter() ||
                            crmMode != isCrmModeEnabled() ||
                            crmContactsMode != HomeCrmTimelineModeToggle.isContactsMode() ||
                            page != pageIndex() ||
                            (filterState != null && filterState != HomeCrmFilterStore.load(context))
                        ) {
                            return@enrichAsync
                        }
                        applyRenderData(enriched, currentPageSize)
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
    }

    /**
     * Search is the first operation. The cache retains that complete result set
     * while the user changes CRM buttons; filters are reapplied later to this list.
     */
    private fun searchResultsFor(
        query: String,
        phoneFilter: String,
        crmMode: Boolean,
        crmContactsMode: Boolean,
    ): List<PhoneCallRecord> {
        val key = SearchKey(query.trim(), phoneFilter, crmMode, crmContactsMode)
        synchronized(cachedSearchLock) {
            cachedSearch?.takeIf { it.key == key }?.let { return it.calls }
        }
        val loaded = when {
            crmContactsMode && phoneFilter.isBlank() -> searchCrmContacts(query)
            crmMode && phoneFilter.isBlank() -> searchCrmCalls(query)
            else -> HomeCallPageLoader.calls(
                context = context,
                activePhoneFilter = phoneFilter,
                searchQuery = query,
                pageIndex = 0,
                // Search sources are already bounded internally. Load the complete
                // bounded set once, then page after CRM filters have been applied.
                pageSize = SEARCH_RESULT_SCAN_LIMIT,
                crmMode = crmMode,
            )
        }
        synchronized(cachedSearchLock) {
            cachedSearch = CachedSearch(key, loaded)
        }
        return loaded
    }

    /** CRM Clients searches the authenticated server list, not Android's call log. */
    private fun searchCrmContacts(query: String): List<PhoneCallRecord> {
        val terms = SearchQueryTerms.from(query)
        return HomeCrmContactCandidates.load(context.applicationContext)
            .filter { contact ->
                terms.matches(
                    contact.displayName,
                    contact.number,
                    ContactNoteReader.generalNoteForPhone(context, contact.number),
                )
            }
            .sortedByDescending { it.startedAt }
    }

    /**
     * CRM Calls searches the same complete bounded timeline that the CRM Calls
     * page shows before company/phase buttons are applied. It does not consult the
     * broad device contact/note index, so search results never leak in from another
     * page or from non-CRM phone activity.
     */
    private fun searchCrmCalls(query: String): List<PhoneCallRecord> {
        val terms = SearchQueryTerms.from(query)
        val candidates = HomeTimelineLoader.crmCandidates(context.applicationContext)
        val callNotes = HomeCallNotesResolver.localNotes(context, candidates)
        return candidates.filter { call ->
            terms.matches(
                call.displayName,
                call.number,
                call.smsBody,
                ContactNoteReader.generalNoteForPhone(context, call.number),
                callNotes[HomeCallNotesResolver.keyFor(call)]?.text.orEmpty(),
            )
        }
    }

    private fun filterCrmSearchResults(
        calls: List<PhoneCallRecord>,
        state: HomeCrmFilterState,
    ): List<PhoneCallRecord> {
        val phaseFiltered = HomeCrmFilterEngine.filterLocal(context, calls, state)
        if (!state.isCompanyFiltered || phaseFiltered.isEmpty()) return phaseFiltered
        val appContext = context.applicationContext
        val memberships = HomeCrmCompanyMembershipStore.resolve(
            context = appContext,
            config = ConfigStore.load(appContext),
            phones = phaseFiltered.map { it.number },
        )
        return HomeCrmFilterEngine.filterByCompany(
            calls = phaseFiltered,
            state = state,
            companyIdsByPhoneKey = memberships.companyIdsByPhoneKey,
        )
    }

    private fun showSearchCount(count: Int) {
        if (activeSearchQuery().isBlank()) return
        binding.homeStatusText.text = context.getString(R.string.runtime_search_found_count, count)
        binding.homeStatusText.visibility = View.VISIBLE
    }

    private data class SearchKey(
        val query: String,
        val phoneFilter: String,
        val crmMode: Boolean,
        val crmContactsMode: Boolean,
    )

    private data class CachedSearch(
        val key: SearchKey,
        val calls: List<PhoneCallRecord>,
    )

    private companion object {
        const val SEARCH_RESULT_SCAN_LIMIT = 500
    }
}

internal data class HomeRenderData(
    val calls: List<PhoneCallRecord>,
    val contactNotesByNumber: Map<String, String>,
    val contactNamesByNumber: Map<String, String>,
    val callNotesByCall: Map<String, HomeCallNote> = emptyMap(),
)
