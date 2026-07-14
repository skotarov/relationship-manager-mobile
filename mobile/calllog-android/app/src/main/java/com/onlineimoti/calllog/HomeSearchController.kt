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
            val rawResults = searchResultsFor(
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
                filterState != null -> filterCrmSearchResults(rawResults, filterState)
                else -> rawResults
            }
            val calls = filteredResults
                .drop(page * currentPageSize)
                .take(currentPageSize)
            val localCallNotes = HomeCallNotesResolver.localNotes(context, calls)
            val clientSearchCallNotes = if (crmContactsMode && query.trim().isNotBlank()) {
                serverSearchCallNotes(calls, query)
            } else {
                emptyMap()
            }
            val renderData = HomeRenderData(
                calls = calls,
                contactNotesByNumber = contactNotesForRenderedSearch(calls, crmContactsMode),
                contactNamesByNumber = HomeCallPageLoader.contactNames(context, calls),
                callNotesByCall = localCallNotes + clientSearchCallNotes,
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

    /**
     * Search is the first operation. The cache retains that complete result set
     * while the user changes CRM buttons; filters are reapplied later to this list.
     */
    private fun searchResultsFor(
        query: String,
        phoneFilter: String,
        crmMode: Boolean,
        crmContactsMode: Boolean,
        filterState: HomeCrmFilterState?,
    ): List<PhoneCallRecord> {
        val key = SearchKey(
            query = query.trim(),
            phoneFilter = phoneFilter,
            crmMode = crmMode,
            crmContactsMode = crmContactsMode,
            serverFilterState = if (crmContactsMode) filterState else null,
        )
        synchronized(cachedSearchLock) {
            cachedSearch?.takeIf { it.key == key }?.let { return it.calls }
        }
        val loaded = when {
            crmContactsMode && phoneFilter.isBlank() -> searchCrmContacts(
                query = query,
                filterState = filterState ?: HomeCrmFilterState(),
            )
            crmMode && phoneFilter.isBlank() -> searchCrmCalls(query)
            else -> searchMainCallLog(
                query = query,
                phoneFilter = phoneFilter,
                crmMode = crmMode,
            )
        }
        synchronized(cachedSearchLock) {
            cachedSearch = CachedSearch(key, loaded)
        }
        return loaded
    }

    /** CRM Clients searches the authenticated server list, not Android's call log. */
    private fun searchCrmContacts(
        query: String,
        filterState: HomeCrmFilterState,
    ): List<PhoneCallRecord> = HomeCrmContactCandidates.load(
        context = context.applicationContext,
        filterState = filterState,
        searchQuery = query,
    )

    /**
     * The normal Call Log search must include both local Android/local-note data and
     * server Relationship Manager data. Server-only notes/clients are returned as
     * synthetic rows with the matched note as [PhoneCallRecord.searchSnippet].
     */
    private fun searchMainCallLog(
        query: String,
        phoneFilter: String,
        crmMode: Boolean,
    ): List<PhoneCallRecord> {
        val localResults = HomeCallPageLoader.calls(
            context = context,
            activePhoneFilter = phoneFilter,
            searchQuery = query,
            pageIndex = 0,
            // Search sources are already bounded internally. Load the complete
            // bounded set once, then page after CRM filters have been applied.
            pageSize = SEARCH_RESULT_SCAN_LIMIT,
            crmMode = crmMode,
        )
        if (crmMode) return localResults
        val serverResults = searchServerCallLog(query, phoneFilter)
        return mergeServerSearchResults(localResults, serverResults)
    }

    private fun searchServerCallLog(query: String, phoneFilter: String): List<PhoneCallRecord> {
        val config = ConfigStore.load(context.applicationContext)
        val serverResults = runCatching {
            ServerCallLogSearchClient.search(
                config = config,
                query = query,
                context = context.applicationContext,
            )
        }.getOrDefault(emptyList())
        val selectedKey = HomeCallPageLoader.noteKey(phoneFilter)
        return if (selectedKey.isBlank()) {
            serverResults
        } else {
            serverResults.filter { row -> HomeCallPageLoader.noteKey(row.number) == selectedKey }
        }
    }

    private fun mergeServerSearchResults(
        localResults: List<PhoneCallRecord>,
        serverResults: List<PhoneCallRecord>,
    ): List<PhoneCallRecord> {
        if (serverResults.isEmpty()) return localResults
        val merged = localResults.toMutableList()
        val firstIndexByPhoneKey = linkedMapOf<String, Int>()
        merged.forEachIndexed { index, row ->
            HomeCallPageLoader.noteKey(row.number)
                .takeIf { it.isNotBlank() && it !in firstIndexByPhoneKey }
                ?.let { key -> firstIndexByPhoneKey[key] = index }
        }

        serverResults.forEach { serverRow ->
            val key = HomeCallPageLoader.noteKey(serverRow.number)
            if (key.isBlank()) return@forEach
            val existingIndex = firstIndexByPhoneKey[key]
            if (existingIndex != null) {
                val current = merged[existingIndex]
                merged[existingIndex] = current.copy(
                    name = current.name.ifBlank { serverRow.name },
                    searchSnippet = current.searchSnippet.ifBlank { serverRow.searchSnippet },
                )
                return@forEach
            }
            firstIndexByPhoneKey[key] = merged.size
            merged += serverRow
        }
        return merged.sortedByDescending { row -> row.startedAt }
    }

    private fun contactNotesForRenderedSearch(
        calls: List<PhoneCallRecord>,
        crmContactsMode: Boolean,
    ): Map<String, String> {
        val notes = HomeCallPageLoader.contactNotes(context, calls).toMutableMap()
        if (crmContactsMode) {
            // Clients search results are canonical server contacts. A matching server
            // NOTE belongs to the blue/call-note lane, not the yellow local/general
            // lane. The row identity/company label already carries non-note matches.
            return notes
        }
        calls.forEach { call ->
            val key = HomeCallPageLoader.noteKey(call.number)
            val snippet = call.searchSnippet.trim()
            if (key.isNotBlank() && snippet.isNotBlank()) notes[key] = snippet
        }
        return notes
    }

    /**
     * contacts_lookup.php returns contacts matched by the server. For the visible
     * Clients rows, load server History and draw the matching NOTE as a blue note,
     * because it is conversation/server history data, not a local yellow note.
     */
    private fun serverSearchCallNotes(calls: List<PhoneCallRecord>, query: String): Map<String, HomeCallNote> {
        if (calls.isEmpty()) return emptyMap()
        val terms = SearchQueryTerms.from(query)
        if (terms.isEmpty) return emptyMap()
        val config = ConfigStore.load(context.applicationContext)
        if (!CallReportRemoteAccess.isReady(config)) return emptyMap()
        val requestedKeys = calls.mapTo(linkedSetOf()) { HomeCallPageLoader.noteKey(it.number) }
            .filterTo(linkedSetOf()) { it.isNotBlank() }
        if (requestedKeys.isEmpty()) return emptyMap()
        val phones = calls.map { it.number }.distinctBy(HomeCallPageLoader::noteKey)
        val history = runCatching {
            CallReportHistoryLookupClient.lookupMany(config, phones, context.applicationContext)
        }.getOrDefault(CallReportHistoryLookupResult())
        val rowByPhoneKey = calls.associateBy { HomeCallPageLoader.noteKey(it.number) }
        val latest = linkedMapOf<String, Pair<Long, HomeCallNote>>()
        history.events.forEach { event ->
            val phoneKey = HomeCallPageLoader.noteKey(event.phone)
            if (phoneKey.isBlank() || phoneKey !in requestedKeys) return@forEach
            if (!event.communicationType.equals("note", ignoreCase = true)) return@forEach
            if (event.note.trim().isBlank()) return@forEach
            if (CallReportServerNoteClassifier.isExplicitGeneralNote(event)) return@forEach
            val snippet = searchSnippetFromEvent(event, terms)
            if (snippet.isBlank()) return@forEach
            val row = rowByPhoneKey[phoneKey] ?: return@forEach
            val changedAt = maxOf(event.updatedAtMs, event.createdAtMs, event.occurredAtMs)
            val key = HomeCallNotesResolver.keyFor(row)
            val note = HomeCallNote(
                text = event.note.trim(),
                updatedAtMs = changedAt,
                fromServer = true,
                authorName = event.authorBrokerName.trim(),
                companyId = event.companyId.trim(),
                serverClientEventId = event.clientEventId.trim(),
                editable = !isOtherBrokerAuthor(event, history.principal),
            )
            val current = latest[key]
            if (current == null || changedAt >= current.first) latest[key] = changedAt to note
        }
        return latest.mapValues { it.value.second }
    }

    private fun searchSnippetFromEvent(event: CallReportHistoryEvent, terms: SearchQueryTerms): String {
        val note = event.note.trim()
        if (note.isNotBlank() && containsAnyTextTerm(note, terms)) return note
        if (note.isNotBlank() && terms.matches(note, event.phone, event.contactName, event.companyId)) return note
        return listOf(event.contactName, event.phone, event.companyId)
            .firstOrNull { value -> value.isNotBlank() && terms.matches(value) }
            .orEmpty()
    }

    private fun containsAnyTextTerm(value: String, terms: SearchQueryTerms): Boolean {
        val lower = value.lowercase()
        return terms.textTerms().any { term -> lower.contains(term) }
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

    private fun isFilterStateCurrent(
        expected: HomeCrmFilterState?,
        scope: HomeCrmFilterStore.Scope?,
    ): Boolean {
        if (expected == null) return true
        return scope != null && expected == HomeCrmFilterStore.load(context, scope)
    }

    private fun isOtherBrokerAuthor(
        event: CallReportHistoryEvent,
        principal: CallReportHistoryPrincipal,
    ): Boolean {
        val authorId = event.authorBrokerId.trim()
        val authorName = event.authorBrokerName.trim()
        val currentId = principal.brokerId.trim()
        val currentName = principal.brokerName.trim()
        if (authorId.isNotBlank() && currentId.isNotBlank()) return authorId != currentId
        if (authorName.isNotBlank() && currentName.isNotBlank()) return !authorName.equals(currentName, ignoreCase = true)
        return false
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

    private data class SearchKey(
        val query: String,
        val phoneFilter: String,
        val crmMode: Boolean,
        val crmContactsMode: Boolean,
        val serverFilterState: HomeCrmFilterState?,
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