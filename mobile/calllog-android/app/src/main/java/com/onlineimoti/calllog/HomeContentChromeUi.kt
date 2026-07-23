package com.onlineimoti.calllog

import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.onlineimoti.calllog.databinding.ActivityHomeBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Renders Call Log date separators, status text and paging controls. */
internal class HomeContentChromeUi(
    private val activity: AppCompatActivity,
    private val binding: ActivityHomeBinding,
    private val activeSearchQuery: () -> String,
    private val isCrmModeEnabled: () -> Boolean,
    private val isCrmContactsMode: () -> Boolean,
    private val dp: (Int) -> Int,
) {
    fun dateSeparator(timestamp: Long, relativeDays: Long, hasRowsBefore: Boolean): TextView {
        val locale = if (AppLocaleText.isBulgarian()) Locale("bg", "BG") else Locale.US
        val label = "${SimpleDateFormat("EEEE, d MMMM yyyy", locale).format(Date(timestamp))} " +
            "(${HomeTimelineDateUi.relativeDaysLabel(activity, relativeDays)})"
        return TextView(activity).apply {
            text = label
            textSize = 12.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(activity.getColor(R.color.callreport_icon_background))
            gravity = Gravity.CENTER_VERTICAL
            background = null
            setPadding(dp(10), dp(6), dp(10), dp(6))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = if (hasRowsBefore) dp(8) else 0
                bottomMargin = dp(4)
            }
        }
    }

    fun renderStatusAndPagination(page: Int, pageSize: Int, callsCount: Int) {
        val query = activeSearchQuery()
        val start = page * pageSize + 1
        val end = page * pageSize + callsCount
        binding.homeStatusText.text = if (query.isNotBlank()) {
            activity.getString(R.string.dynamic_home_status_search, query.trim(), start, end)
        } else {
            activity.getString(R.string.dynamic_home_status_calls, start, end)
        }
        updateStatusStyle(hidePlainTimelineRange = true)
        PaginationButtonAppearance.apply(binding.previousCallsButton, page > 0)
        PaginationButtonAppearance.apply(binding.nextCallsButton, callsCount >= pageSize)
        binding.pageText.text = activity.getString(R.string.dynamic_home_page, page + 1)
        binding.paginationContainer.visibility =
            if (PageLoadingModeStore.usesPrefetch(activity)) View.GONE else View.VISIBLE
    }

    fun updateStatusStyle(hidePlainTimelineRange: Boolean = false) {
        val crmTopLevelStatusInResults = isCrmModeEnabled() || isCrmContactsMode()
        val plainCallLogRange = hidePlainTimelineRange && activeSearchQuery().isBlank() &&
            !isCrmModeEnabled() && !isCrmContactsMode()
        binding.homeStatusRow.visibility = if (plainCallLogRange) View.GONE else View.VISIBLE
        binding.homeStatusText.visibility =
            if (plainCallLogRange || crmTopLevelStatusInResults) View.GONE else View.VISIBLE
        binding.filteredStatusContainer.background = null
        binding.filteredStatusContainer.setPadding(0, 0, 0, 0)
        binding.homeStatusText.background = null
        binding.homeStatusText.setTextColor(Color.rgb(71, 85, 105))
        binding.homeStatusText.setPadding(0, 0, 0, 0)
    }
}
