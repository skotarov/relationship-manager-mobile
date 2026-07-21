package com.onlineimoti.calllog

import org.junit.Assert.assertEquals
import org.junit.Test

class HomePageRangeTooltipTextTest {
    @Test
    fun formatsBulgarianLoadedPageRange() {
        assertEquals("Страници 1–29", HomePageRangeTooltipText.format(29, bulgarian = true))
    }

    @Test
    fun formatsEnglishLoadedPageRange() {
        assertEquals("Pages 1–29", HomePageRangeTooltipText.format(29, bulgarian = false))
    }
}
