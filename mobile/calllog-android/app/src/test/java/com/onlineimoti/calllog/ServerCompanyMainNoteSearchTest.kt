package com.onlineimoti.calllog

import org.junit.Assert.assertEquals
import org.junit.Test

class ServerCompanyMainNoteSearchTest {
    @Test
    fun `finds a yellow company main note returned only by the server`() {
        val matches = ServerCompanyMainNoteSearch.matchingNotesByPhoneKey(
            terms = SearchQueryTerms.from("Манастирски"),
            notes = listOf(
                CallReportHistoryCompanyMainNote(
                    phone = "0882 496 773",
                    companyId = "maxim",
                    companyName = "Maxim",
                    note = "Светльо | автободжия Манастирски Изток",
                ),
            ),
        )

        assertEquals("Светльо | автободжия Манастирски Изток", matches["882496773"])
    }
}
