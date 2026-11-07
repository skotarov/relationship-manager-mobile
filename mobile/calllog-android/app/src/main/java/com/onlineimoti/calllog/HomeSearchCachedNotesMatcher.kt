package com.onlineimoti.calllog

/** Matches the note cache that is already visible in Home's yellow and blue lanes. */
internal object HomeSearchCachedNotesMatcher {
    fun matches(
        terms: SearchQueryTerms,
        call: PhoneCallRecord,
        cachedNotes: HomeRenderData,
        localGeneralNote: String = "",
    ): Boolean {
        val phoneKey = HomeCallPageLoader.noteKey(call.number)
        val blueNotes = cachedNotes.callNotesByCall[HomeCallNotesResolver.keyFor(call)]
            ?.expandedNotes()
            .orEmpty()
            .joinToString(" ") { it.text }
        return terms.matches(
            call.displayName,
            call.number,
            call.smsBody,
            localGeneralNote,
            cachedNotes.contactNotesByNumber[phoneKey].orEmpty(),
            blueNotes,
        )
    }
}
