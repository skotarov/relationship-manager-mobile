package com.onlineimoti.calllog

import android.content.Context

/** Slow server fallback for CRM searches over yellow company-main notes. */
internal object ServerCompanyMainNoteSearch {
    private const val MAX_SERVER_SEARCH_PHONES = 20

    fun search(
        context: Context,
        config: AppConfig,
        query: String,
        calls: List<PhoneCallRecord>,
    ): List<PhoneCallRecord> {
        val terms = SearchQueryTerms.from(query)
        if (terms.isEmpty || calls.isEmpty() || !CallReportRemoteAccess.isReady(config)) return emptyList()
        val phones = calls.asSequence()
            .filterNot { it.isSms }
            .map { it.number }
            .filter { HomeCallPageLoader.noteKey(it).isNotBlank() }
            .distinctBy(HomeCallPageLoader::noteKey)
            .take(MAX_SERVER_SEARCH_PHONES)
            .toList()
        if (phones.isEmpty()) return emptyList()
        val history = runCatching {
            CallReportHistoryLookupClient.lookupMany(config, phones, context)
        }.getOrDefault(CallReportHistoryLookupResult())
        val matches = matchingNotesByPhoneKey(terms, history.companyMainNotes)
        if (matches.isEmpty()) return emptyList()
        return calls.asSequence()
            .filterNot { it.isSms }
            .filter { HomeCallPageLoader.noteKey(it.number) in matches }
            .groupBy { HomeCallPageLoader.noteKey(it.number) }
            .mapNotNull { (phoneKey, phoneCalls) ->
                phoneCalls.maxByOrNull { it.startedAt }?.copy(
                    providerId = "server-company-search:$phoneKey",
                    searchSnippet = ServerNoteVisuals.prefixed(matches[phoneKey].orEmpty()),
                    searchResultSource = HomeSearchResultSource.SERVER,
                )
            }
    }

    internal fun matchingNotesByPhoneKey(
        terms: SearchQueryTerms,
        notes: List<CallReportHistoryCompanyMainNote>,
    ): Map<String, String> = buildMap {
        notes.forEach { note ->
            val phoneKey = HomeCallPageLoader.noteKey(note.phone)
            val text = note.note.trim()
            if (phoneKey.isNotBlank() && text.isNotBlank() && terms.matches(text)) {
                putIfAbsent(phoneKey, text)
            }
        }
    }
}
