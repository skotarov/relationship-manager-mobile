package com.onlineimoti.calllog

import android.content.Context
import java.util.Calendar

/** Calendar-safe labels used by the Home call timeline. */
internal object HomeTimelineDateUi {
    fun relativeDaysLabel(context: Context, days: Long): String = when {
        days == 0L -> context.getString(R.string.runtime_timeline_today)
        days == 1L -> context.getString(R.string.runtime_timeline_yesterday)
        days > 1L -> context.getString(R.string.runtime_timeline_days_ago, days)
        days == -1L -> context.getString(R.string.runtime_timeline_tomorrow)
        else -> context.getString(R.string.runtime_timeline_in_days, -days)
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
