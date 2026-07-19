package com.onlineimoti.calllog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.util.Calendar

class TimelineGroupedPagerTest {
    @Test
    fun pageCompletesTheLastDayInsteadOfSplittingIt() {
        val rows = buildList {
            repeat(12) { add(Row(day = 3L, id = "today-$it")) }
            repeat(15) { add(Row(day = 2L, id = "yesterday-$it")) }
            repeat(5) { add(Row(day = 1L, id = "older-$it")) }
        }

        val pages = TimelineGroupedPager.pages(rows, minimumPageSize = 20) { it.day }

        assertEquals(listOf(27, 5), pages.map { it.size })
        assertEquals(setOf(3L, 2L), pages[0].map { it.day }.toSet())
        assertEquals(setOf(1L), pages[1].map { it.day }.toSet())
    }

    @Test
    fun aLargeSingleGroupRemainsOneCompletePage() {
        val rows = List(25) { Row(day = 3L, id = it.toString()) }

        val pages = TimelineGroupedPager.pages(rows, minimumPageSize = 20) { it.day }

        assertEquals(1, pages.size)
        assertEquals(25, pages.single().size)
    }

    @Test
    fun rowsWithoutAVisibleGroupKeepExactPaging() {
        val rows = List(22) { Row(day = null, id = it.toString()) }

        val pages = TimelineGroupedPager.pages(rows, minimumPageSize = 20) { it.day }

        assertEquals(listOf(20, 2), pages.map { it.size })
    }

    @Test
    fun weekKeyMatchesMondayThroughSunday() {
        val monday = Calendar.getInstance().apply {
            set(2026, Calendar.JULY, 13, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val sunday = (monday.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 6) }
        val nextMonday = (monday.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 7) }

        assertEquals(TimelineGroupKeys.week(monday.timeInMillis), TimelineGroupKeys.week(sunday.timeInMillis))
        assertNotEquals(TimelineGroupKeys.week(monday.timeInMillis), TimelineGroupKeys.week(nextMonday.timeInMillis))
    }

    private data class Row(
        val day: Long?,
        val id: String,
    )
}
