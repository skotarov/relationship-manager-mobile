package com.onlineimoti.calllog

import android.content.Context

internal class HomeSearchDataSource(
    private val context: Context,
) {
    /**
     * The first search pass is deliberately device-only. It never uses the
     * company-label cache: a yellow company note belongs to the server result
     * section even when its label was already painted on the timeline.
     */
    fun searchLocalResultsFor(
        query: String,
        phoneFilter: String,
        crmMode: Boolean,
        crmContactsMode: Boolean,
        filterState: HomeCrmFilterState?,
    ): List<PhoneCallRecord> {
        return when {
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
    }

    /**
     * The second pass is a single global server query. Its rows are kept apart
     * from the phone rows by the caller; failures are returned explicitly so an
     * empty response cannot masquerade as a completed zero-result search.
     */
    fun searchServerResultsFor(
        query: String,
        phoneFilter: String,
        crmContactsMode: Boolean,
    ): HomeServerSearchResult {
        // Clients already have their own cache-first authoritative loader. It
        // receives the same query and filters and must not be queried through a
        // second endpoint with a different scope.
        if (crmContactsMode) return HomeServerSearchResult.Success(emptyList())
        return runCatching {
            HomeServerSearchResult.Success(searchServerCallLog(query, phoneFilter))
        }.getOrElse { HomeServerSearchResult.Failure }
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
    ): List<PhoneCallRecord> {
        val config = ConfigStore.load(context.applicationContext)
        val serverResults = ServerCallLogSearchClient.search(
            config = config,
            query = query,
            context = context.applicationContext,
        )
            .groupBy { HomeCallPageLoader.noteKey(it.number) }
            .mapNotNull { (_, rows) -> rows.firstOrNull { it.searchSnippet.isNotBlank() } ?: rows.firstOrNull() }
        val selectedKey = HomeCallPageLoader.noteKey(phoneFilter)
        return if (selectedKey.isBlank()) {
            serverResults
        } else {
            serverResults.filter { row -> HomeCallPageLoader.noteKey(row.number) == selectedKey }
        }
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
        // The durable local snapshot contains the blue notes written on this
        // phone. Yellow company labels live in a separate server cache and are
        // deliberately excluded here; they arrive in the server section.
        val cachedNotes = HomeNotesSnapshotCache.mergeCached(
            context,
            HomeRenderData(candidates, emptyMap(), emptyMap()),
        )
        return candidates.filter { call ->
            HomeSearchCachedNotesMatcher.matches(
                terms = terms,
                call = call,
                cachedNotes = cachedNotes,
                localGeneralNote = ContactNoteReader.generalNoteForPhone(context, call.number),
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

    private companion object {
        const val SEARCH_RESULT_SCAN_LIMIT = 500
    }
}

internal sealed interface HomeServerSearchResult {
    data class Success(val calls: List<PhoneCallRecord>) : HomeServerSearchResult
    object Failure : HomeServerSearchResult
}
