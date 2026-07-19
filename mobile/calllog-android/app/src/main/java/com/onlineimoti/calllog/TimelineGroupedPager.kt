package com.onlineimoti.calllog

import java.util.Calendar

/** Splits a sorted timeline without cutting a visible day or week between pages. */
internal object TimelineGroupedPager {
    fun <T> page(
        items: List<T>,
        pageIndex: Int,
        minimumPageSize: Int,
        groupKey: (T) -> Long?,
    ): List<T> = pages(items, minimumPageSize, groupKey)
        .getOrNull(pageIndex.coerceAtLeast(0))
        .orEmpty()

    fun <T> pages(
        items: List<T>,
        minimumPageSize: Int,
        groupKey: (T) -> Long?,
    ): List<List<T>> {
        if (items.isEmpty()) return emptyList()
        val safeMinimum = minimumPageSize.coerceAtLeast(1)
        val result = mutableListOf<List<T>>()
        var pageStart = 0

        while (pageStart < items.size) {
            var pageEnd = pageStart
            while (pageEnd < items.size && pageEnd - pageStart < safeMinimum) {
                pageEnd = endOfGroup(items, pageEnd, groupKey)
            }
            result += items.subList(pageStart, pageEnd)
            pageStart = pageEnd
        }
        return result
    }

    private fun <T> endOfGroup(
        items: List<T>,
        groupStart: Int,
        groupKey: (T) -> Long?,
    ): Int {
        val key = groupKey(items[groupStart]) ?: return groupStart + 1
        var end = groupStart + 1
        while (end < items.size && groupKey(items[end]) == key) end++
        return end
    }
}

/** Calendar keys shared by paging and the existing day/week separators. */
internal object TimelineGroupKeys {
    fun day(timestampMs: Long): Long? {
        if (timestampMs <= 0L) return null
        val calendar = Calendar.getInstance().apply { timeInMillis = timestampMs }
        return calendarDaySerial(calendar)
    }

    fun week(timestampMs: Long): Long? {
        if (timestampMs <= 0L) return null
        val calendar = Calendar.getInstance().apply {
            timeInMillis = timestampMs
            val daysSinceMonday =
                (get(Calendar.DAY_OF_WEEK) - Calendar.MONDAY + DAYS_PER_WEEK) % DAYS_PER_WEEK
            add(Calendar.DAY_OF_YEAR, -daysSinceMonday)
        }
        return calendarDaySerial(calendar)
    }

    private fun calendarDaySerial(calendar: Calendar): Long {
        val yearBefore = (calendar.get(Calendar.YEAR) - 1).toLong()
        val daysBeforeYear =
            365L * yearBefore + yearBefore / 4L - yearBefore / 100L + yearBefore / 400L
        return daysBeforeYear + calendar.get(Calendar.DAY_OF_YEAR).toLong() - 1L
    }

    private const val DAYS_PER_WEEK = 7
}
