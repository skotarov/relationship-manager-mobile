package com.onlineimoti.calllog

/**
 * The two search sources stay distinct all the way to the renderer.  A matching
 * number in both sources is intentionally not collapsed: it explains whether
 * the hit was found on the device or in an authoritative server note.
 */
internal data class HomeSearchResultSections(
    val phone: List<PhoneCallRecord>,
    val server: List<PhoneCallRecord>,
) {
    val combined: List<PhoneCallRecord>
        get() = phone + server
}

internal object HomeSearchResultSectionComposer {
    fun compose(
        phoneResults: List<PhoneCallRecord>,
        serverResults: List<PhoneCallRecord>,
    ): HomeSearchResultSections = HomeSearchResultSections(
        phone = phoneResults.map { it.copy(searchResultSource = HomeSearchResultSource.PHONE) },
        server = serverResults
            .map { it.copy(searchResultSource = HomeSearchResultSource.SERVER) }
            .distinctBy { row ->
                "${HomeCallPageLoader.noteKey(row.number)}|${row.searchSnippet.trim()}"
            },
    )
}
