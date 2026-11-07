package com.onlineimoti.calllog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeSearchResultSectionsTest {
    @Test
    fun `a yellow server-note hit stays in the server section even for a phone visible locally`() {
        val phoneRow = PhoneCallRecord(
            number = "0882 496 773",
            name = "Светльо",
            direction = "incoming",
            startedAt = 1_000L,
            durationSeconds = 5L,
        )
        val yellowServerHit = phoneRow.copy(
            providerId = "server-search:882496773:0",
            searchSnippet = "[Maxim] Светльо | автободжия Манастирски Изток",
        )

        val sections = HomeSearchResultSectionComposer.compose(
            phoneResults = listOf(phoneRow),
            serverResults = listOf(yellowServerHit),
        )

        assertEquals(1, sections.phone.size)
        assertEquals(1, sections.server.size)
        assertEquals(HomeSearchResultSource.PHONE, sections.phone.single().searchResultSource)
        assertEquals(HomeSearchResultSource.SERVER, sections.server.single().searchResultSource)
        assertTrue(sections.server.single().searchSnippet.contains("Манастирски"))
    }
}
