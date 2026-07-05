package com.onlineimoti.calllog

import java.util.Calendar

/** Calendar-safe labels used by the Home call timeline. */
internal object HomeTimelineDateUi {
    fun relativeDaysLabel(days: Long): String = when {
        days == 0L -> "преди 0 дни"
        days == 1L -> "преди 1 ден"
        days > 1L -> "преди $days дни"
        days == -1L -> "след 1 ден"
        else -> "след ${-days} дни"
    }

    /** Calendar-day serial avoids daylight-saving-time errors around midnight. */
    fun localDaySerial(timestampMs: Long): Long? {
        if (timestampMs <= 0L) return null
        val calendar = Calendar.getInstance().apply { timeInMillis = timestampMs }
        val yearBefore = (calendar.get(Calendar.YEAR) - 1).toLong()
        val daysBeforeYear = 365L * yearBefore + yearBefore / 4L - yearBefore / 100L + yearBefore / 400L
        return daysBeforeYear + calendar.get(Calendar.DAY_OF_YEAR).toLong() - 1L
    }
}
