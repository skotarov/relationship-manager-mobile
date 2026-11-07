package com.onlineimoti.calllog

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeSearchResultSourceTest {
    @Test
    fun `server search rows retain a distinct server source`() {
        val row = PhoneCallRecord(
            number = "0882 496 773",
            name = "Светльо",
            direction = "",
            startedAt = 1_000L,
            durationSeconds = 0L,
            searchResultSource = HomeSearchResultSource.SERVER,
        )

        assertEquals(HomeSearchResultSource.SERVER, row.searchResultSource)
    }
}
