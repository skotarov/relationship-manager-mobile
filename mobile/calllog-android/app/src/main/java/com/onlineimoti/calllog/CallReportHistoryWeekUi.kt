package com.onlineimoti.calllog

import android.app.Activity
import android.graphics.Typeface
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

internal class CallReportHistoryWeekUi(
    private val activity: Activity,
    private val dp: (Int) -> Int,
) {
    private val dayMonthFormatter = SimpleDateFormat("d MMMM", Locale("bg", "BG"))
    private val dayMonthYearFormatter = SimpleDateFormat("d MMMM yyyy", Locale("bg", "BG"))

    fun currentWeekSerial(): Long? = weekStartSerial(System.currentTimeMillis())

    fun weekStartSerial(timestampMs: Long): Long? =
        weekStartCalendar(timestampMs)?.let(::calendarDaySerial)

    fun separator(timestampMs: Long, relativeWeeks: Long): TextView {
        return TextView(activity).apply {
            text = "Седмица: ${weekDateRange(timestampMs)} (${relativeWeeksLabel(relativeWeeks)})"
            textSize = 12.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(activity.getColor(R.color.callreport_icon_background))
            gravity = Gravity.CENTER_VERTICAL
            background = null
            setPadding(dp(10), dp(10), dp(10), dp(6))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(6)
                bottomMargin = dp(4)
            }
        }
    }

    private fun weekDateRange(timestampMs: Long): String {
        val start = weekStartCalendar(timestampMs) ?: return ""
        val end = (start.clone() as Calendar).apply {
            add(Calendar.DAY_OF_YEAR, DAYS_PER_WEEK.toInt() - 1)
        }
        return if (start.get(Calendar.YEAR) == end.get(Calendar.YEAR)) {
            "${dayMonthFormatter.format(start.time)} – ${dayMonthYearFormatter.format(end.time)}"
        } else {
            "${dayMonthYearFormatter.format(start.time)} – ${dayMonthYearFormatter.format(end.time)}"
        }
    }

    private fun relativeWeeksLabel(weeks: Long): String = when {
        weeks == 0L -> "преди 0 седмици"
        weeks == 1L -> "преди 1 седмица"
        weeks > 1L -> "преди $weeks седмици"
        weeks == -1L -> "след 1 седмица"
        else -> "след ${-weeks} седмици"
    }

    private fun weekStartCalendar(timestampMs: Long): Calendar? {
        if (timestampMs <= 0L) return null
        return Calendar.getInstance().apply {
            timeInMillis = timestampMs
            val daysSinceMonday =
                (get(Calendar.DAY_OF_WEEK) - Calendar.MONDAY + DAYS_PER_WEEK.toInt()) %
                    DAYS_PER_WEEK.toInt()
            add(Calendar.DAY_OF_YEAR, -daysSinceMonday)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
    }

    private fun calendarDaySerial(calendar: Calendar): Long {
        val yearBefore = (calendar.get(Calendar.YEAR) - 1).toLong()
        val daysBeforeYear =
            365L * yearBefore + yearBefore / 4L - yearBefore / 100L + yearBefore / 400L
        return daysBeforeYear + calendar.get(Calendar.DAY_OF_YEAR).toLong() - 1L
    }

    companion object {
        const val DAYS_PER_WEEK = 7L
    }
}
