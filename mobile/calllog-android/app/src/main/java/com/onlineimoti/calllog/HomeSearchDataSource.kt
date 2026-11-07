package com.onlineimoti.calllog

import android.content.Context

internal class HomeSearchDataSource(
    private val context: Context,
) {
    private val cachedSearchLock = Any()
    private var cachedSearch: CachedSearch? = null

    /**
     * The phone index is deliberately searched before the network. It includes
     * contact names plus the locally stored yellow general notes and blue call
     * notes, so typing stays responsive while the server lookup is in flight.
     */
    fun searchLocalResultsFor(
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

    /** Adds matches from Relationship Manager after [localResults] are already visible. */
    fun mergeServerResultsFor(
        localResults: List<PhoneCallRecord>,
        query: String,
        phoneFilter: String,
        crmMode: Boolean,
        crmContactsMode: Boolean,
    ): List<PhoneCallRecord> {
        // Clients keep their dedicated authoritative request. CRM Calls use the
        // same staged lookup as Call Log, but server matches may only reveal CRM
        // call rows that are already in the device timeline.
        if (crmContactsMode) return localResults
        val serverResults = searchServerCallLog(query, phoneFilter, crmMode)
        if (!crmMode) return mergeServerSearchResults(localResults, serverResults)

        val matchingPhoneKeys = (localResults + serverResults)
            .mapTo(linkedSetOf()) { HomeCallPageLoader.noteKey(it.number) }
            .filterTo(linkedSetOf()) { it.isNotBlank() }
        if (matchingPhoneKeys.isEmpty()) return emptyList()
        val matchingCrmCalls = HomeTimelineLoader.crmCandidates(context.applicationContext)
            .filter { call -> HomeCallPageLoader.noteKey(call.number) in matchingPhoneKeys }
        return mergeServerSearchResults(matchingCrmCalls, serverResults)
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

    /** Loads the local Android/contact/note index used for the first search render. */
    private fun searchMainCallLog(
        query: String,
        phoneFilter: String,
        crmMode: Boolean,
    ): List<PhoneCallRecord> {
        return HomeCallPageLoader.calls(
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

    private fun searchServerCallLog(
        query: String,
        phoneFilter: String,
        crmMode: Boolean,
    ): List<PhoneCallRecord> {
        val config = ConfigStore.load(context.applicationContext)
        val sharedResults = runCatching {
            ServerCallLogSearchClient.search(
                config = config,
                query = query,
                context = context.applicationContext,
            )
        }.getOrDefault(emptyList())
        // Company-main notes are yellow labels fetched through history_lookup.
        // Some server generations do not include their text in the broad Clients
        // lookup, so CRM Call Log supplements it after the local first render.
        val companyNoteResults = if (crmMode) {
            ServerCompanyMainNoteSearch.search(
                context = context.applicationContext,
                config = config,
                query = query,
                calls = HomeTimelineLoader.crmCandidates(context.applicationContext),
            )
        } else emptyList()
        val serverResults = (sharedResults + companyNoteResults)
            .groupBy { HomeCallPageLoader.noteKey(it.number) }
            .mapNotNull { (_, rows) -> rows.firstOrNull { it.searchSnippet.isNotBlank() } ?: rows.firstOrNull() }
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
        // A server match is a separate result, even for a number that also has
        // a local call. This makes the fast phone results and slower server
        // results independently visible instead of silently merging one into
        // the other.
        val visibleServerRows = serverResults
            .map { row -> row.copy(searchResultSource = HomeSearchResultSource.SERVER) }
            .distinctBy { row ->
                "${HomeCallPageLoader.noteKey(row.number)}|${row.searchSnippet.trim()}"
            }
        return localResults + visibleServerRows
    }

    fun contactNotesForRenderedSearch(
        calls: List<PhoneCallRecord>,
        crmContactsMode: Boolean,
    ): Map<String, String> {
        val notes = HomeCallPageLoader.contactNotes(context, calls).toMutableMap()
        if (crmContactsMode) {
            // Clients search results are canonical server contacts. Server blue notes
            // are rendered through callNotesByCall. Unscoped server main notes are
            // merged separately from HomeCrmClientServerNotes; matched snippets must
            // not be pushed into the yellow lane.
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
     * CRM Calls searches the same complete bounded timeline that the CRM Calls
     * page shows before company/phase buttons are applied. It does not consult the
     * broad device contact/note index, so search results never leak in from another
     * page or from non-CRM phone activity.
     */
    private fun searchCrmCalls(query: String): List<PhoneCallRecord> {
        val terms = SearchQueryTerms.from(query)
        val candidates = HomeTimelineLoader.crmCandidates(context.applicationContext)
        // This is the same durable snapshot used to paint yellow/blue notes on
        // Home before the server returns. Search it too, so a visible cached
        // company note is immediately findable in CRM Call Log.
        val cachedNotes = HomeNotesSnapshotCache.mergeCached(
            context,
            HomeRenderData(candidates, emptyMap(), emptyMap()),
        )
        val cachedCompanyNotes = HomeCompanyScopeSnapshotCache.read(context)
            .labelsByPhoneKey
        return candidates.filter { call ->
            val phoneKey = HomeCallPageLoader.noteKey(call.number)
            HomeSearchCachedNotesMatcher.matches(
                terms = terms,
                call = call,
                cachedNotes = cachedNotes,
                localGeneralNote = ContactNoteReader.generalNoteForPhone(context, call.number),
                companyGeneralNotes = cachedCompanyNotes[phoneKey].orEmpty(),
            )
        }
    }

    fun filterCrmSearchResults(
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
