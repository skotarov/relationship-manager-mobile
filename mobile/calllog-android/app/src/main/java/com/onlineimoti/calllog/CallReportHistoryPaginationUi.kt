package com.onlineimoti.calllog

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/** Keeps Notes and SMS history compact by showing the configured number of rows per page. */
internal class CallReportHistoryPaginationUi(
    private val activity: Activity,
    private val dp: (Int) -> Int,
    private val roundedRect: (color: Int, radius: Int, strokeColor: Int, strokeWidth: Int) -> GradientDrawable,
) {
    private var pageIndex = 0

    fun currentPage(rows: List<CallReportHistoryRow>): HistoryPage {
        val pageSize = ConfigStore.load(activity).homeCallPageSize
        val totalPages = maxOf(1, (rows.size + pageSize - 1) / pageSize)
        pageIndex = pageIndex.coerceIn(0, totalPages - 1)
        val firstIndex = pageIndex * pageSize
        val lastExclusive = minOf(rows.size, firstIndex + pageSize)
        return HistoryPage(
            rows = if (firstIndex < rows.size) rows.subList(firstIndex, lastExclusive) else emptyList(),
            pageIndex = pageIndex,
            pageSize = pageSize,
            totalPages = totalPages,
        )
    }

    fun addNavigation(container: LinearLayout, page: HistoryPage, onPageChanged: () -> Unit) {
        if (page.totalPages <= 1) return
        container.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, 0)
            addView(pageButton(
                label = activity.getString(R.string.history_page_previous, page.pageSize),
                enabled = page.pageIndex > 0,
            ) {
                pageIndex -= 1
                onPageChanged()
            }, LinearLayout.LayoutParams(0, dp(38), 1f))
            addView(TextView(activity).apply {
                text = activity.getString(R.string.history_page_status, page.pageIndex + 1, page.totalPages)
                textSize = 12.5f
                gravity = Gravity.CENTER
                setTextColor(Color.rgb(100, 116, 139))
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.9f))
            addView(pageButton(
                label = activity.getString(R.string.history_page_next, page.pageSize),
                enabled = page.pageIndex < page.totalPages - 1,
            ) {
                pageIndex += 1
                onPageChanged()
            }, LinearLayout.LayoutParams(0, dp(38), 1f))
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(4) })
    }

    private fun pageButton(label: String, enabled: Boolean, action: () -> Unit): TextView {
        val textColor = if (enabled) Color.rgb(55, 65, 81) else Color.rgb(156, 163, 175)
        val background = if (enabled) Color.rgb(248, 250, 252) else Color.rgb(249, 250, 251)
        return TextView(activity).apply {
            text = label
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(textColor)
            this.isEnabled = enabled
            isClickable = enabled
            isFocusable = enabled
            setPadding(dp(6), 0, dp(6), 0)
            this.background = roundedRect(background, dp(10), Color.rgb(226, 232, 240), dp(1))
            if (enabled) setOnClickListener { action() }
        }
    }

    internal data class HistoryPage(
        val rows: List<CallReportHistoryRow>,
        val pageIndex: Int,
        val pageSize: Int,
        val totalPages: Int,
    )
}
