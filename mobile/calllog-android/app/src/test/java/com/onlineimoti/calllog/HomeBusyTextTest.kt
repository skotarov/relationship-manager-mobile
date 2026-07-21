package com.onlineimoti.calllog

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeBusyTextTest {
    @Test
    fun explainsBulgarianBackgroundTasks() {
        assertEquals("Зареждам разговорите…", HomeBusyText.value(HomeBusyWork.CALLS, bulgarian = true))
        assertEquals("Търся в разговори и бележки…", HomeBusyText.value(HomeBusyWork.SEARCH, bulgarian = true))
        assertEquals("Зареждам пълната история…", HomeBusyText.value(HomeBusyWork.FULL_LOG, bulgarian = true))
        assertEquals("Зареждам още разговори…", HomeBusyText.value(HomeBusyWork.MORE_CALLS, bulgarian = true))
    }

    @Test
    fun explainsEnglishBackgroundTasks() {
        assertEquals("Loading clients…", HomeBusyText.value(HomeBusyWork.CLIENTS, bulgarian = false))
        assertEquals("Adding server notes…", HomeBusyText.value(HomeBusyWork.SERVER_NOTES, bulgarian = false))
        assertEquals("Updating company data…", HomeBusyText.value(HomeBusyWork.COMPANY_DATA, bulgarian = false))
    }
}
