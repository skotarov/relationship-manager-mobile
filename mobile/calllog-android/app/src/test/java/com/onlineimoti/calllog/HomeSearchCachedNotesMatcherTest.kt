package com.onlineimoti.calllog

import org.junit.Assert.assertTrue
import org.junit.Test

class HomeSearchCachedNotesMatcherTest {
    private val call = PhoneCallRecord(
        number = "0882 496 773",
        name = "Светльо",
        direction = "out",
        startedAt = 1_000L,
        durationSeconds = 5L,
        providerId = "test-call",
    )

    @Test
    fun `finds the yellow company label cache used by the visible CRM row`() {
        val snapshot = HomeRenderData(
            calls = listOf(call),
            contactNotesByNumber = emptyMap(),
            contactNamesByNumber = emptyMap(),
        )

        assertTrue(
            HomeSearchCachedNotesMatcher.matches(
                terms = SearchQueryTerms.from("Манастирски"),
                call = call,
                cachedNotes = snapshot,
                companyGeneralNotes = listOf(
                    HomeCompanyScopeLabel("maxim", "Maxim", hasGeneralNote = true, phase = 0, generalNote = "Светльо | автободжия Манастирски Изток"),
                ),
            ),
        )
    }

    @Test
    fun `finds a cached blue server note in CRM calls`() {
        val snapshot = HomeRenderData(
            calls = listOf(call),
            contactNotesByNumber = emptyMap(),
            contactNamesByNumber = emptyMap(),
            callNotesByCall = mapOf(
                HomeCallNotesResolver.keyFor(call) to HomeCallNote("Изпрати оферта", 1_000L, fromServer = true),
            ),
        )

        assertTrue(HomeSearchCachedNotesMatcher.matches(SearchQueryTerms.from("оферта"), call, snapshot))
    }
}
