package com.onlineimoti.calllog

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/** Renders the shared filtered full-log entries inside the existing History screen. */
internal class ContactNotesFullLogUi(
    private val activity: Activity,
    private val dp: (Int) -> Int,
    private val roundedRect: (color: Int, radius: Int, strokeColor: Int, strokeWidth: Int) -> GradientDrawable,
) {
    private val weekUi by lazy { CallReportHistoryWeekUi(activity, dp) }
    private var entries: List<FilteredFullLogEntry> = emptyList()
    private var pageIndex = 0

    fun addSection(
        root: LinearLayout,
        phone: String,
        incomingEntries: List<FilteredFullLogEntry>,
        remoteEnabled: Boolean,
        loading: Boolean,
        errorText: String,
        openCallNoteEditor: (PhoneCallRecord, String, HomeCallNote?) -> Unit,
        onPageChanged: () -> Unit,
    ) {
        entries = incomingEntries
        val pages = pages()
        val totalPages = maxOf(1, pages.size)
        pageIndex = pageIndex.coerceIn(0, totalPages - 1)
        val visibleEntries = pages.take(pageIndex + 1).flatten()
        when {
            loading && entries.isEmpty() -> root.addView(status("Зареждам пълния лог…"))
            errorText.isNotBlank() && entries.isEmpty() -> root.addView(status(errorText, error = true))
            entries.isEmpty() -> root.addView(status(
                if (remoteEnabled) "Няма локални или сървърни записи за този номер"
                else "Няма локални записи за този номер",
            ))
            else -> {
                renderEntries(root, phone, visibleEntries, remoteEnabled, openCallNoteEditor)
                addNavigation(root, totalPages, onPageChanged)
            }
        }
    }

    fun canPreviousPage(): Boolean = pageIndex > 0

    fun canNextPage(): Boolean = pageIndex < maxOf(0, pages().size - 1)

    fun previousPage(rerender: () -> Unit): Boolean {
        if (!canPreviousPage()) return false
        pageIndex -= 1
        rerender()
        return true
    }

    fun nextPage(rerender: () -> Unit): Boolean {
        if (!canNextPage()) return false
        pageIndex += 1
        rerender()
        return true
    }

    fun resetPage() {
        pageIndex = 0
    }

    private fun renderEntries(
        root: LinearLayout,
        phone: String,
        pageEntries: List<FilteredFullLogEntry>,
        remoteEnabled: Boolean,
        openCallNoteEditor: (PhoneCallRecord, String, HomeCallNote?) -> Unit,
    ) {
        val rowRenderer = FilteredFullLogRowRenderer(
            activity = activity,
            dp = dp,
            roundedRect = roundedRect,
            openContactNotes = null,
            openCallNoteEditor = openCallNoteEditor,
        )
        val currentWeekSerial = weekUi.currentWeekSerial()
        var previousWeekSerial: Long? = null
        pageEntries.forEach { entry ->
            val weekSerial = weekUi.weekStartSerial(entry.row.timeMs)
            if (weekSerial != null && weekSerial != previousWeekSerial) {
                val relativeWeeks = currentWeekSerial
                    ?.let { (it - weekSerial) / CallReportHistoryWeekUi.DAYS_PER_WEEK }
                    ?: 0L
                root.addView(weekUi.separator(entry.row.timeMs, relativeWeeks))
                previousWeekSerial = weekSerial
            }
            root.addView(ListThemeUi.applyRowSpacing(
                rowRenderer.rowView(phone, entry, remoteEnabled),
                dp,
            ))
        }
    }

    private fun addNavigation(
        root: LinearLayout,
        totalPages: Int,
        onPageChanged: () -> Unit,
    ) {
        if (totalPages <= 1 || PageLoadingModeStore.usesPrefetch(activity)) return
        val pageSize = ConfigStore.load(activity).homeCallPageSize.coerceIn(5, 100)
        root.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, 0)
            addView(pageButton(
                label = activity.getString(R.string.history_page_previous, pageSize),
                enabled = canPreviousPage(),
            ) { previousPage(onPageChanged) }, LinearLayout.LayoutParams(0, dp(38), 1f))
            addView(TextView(activity).apply {
                text = activity.getString(R.string.history_page_status, pageIndex + 1, totalPages)
                textSize = 12.5f
                gravity = Gravity.CENTER
                setTextColor(Color.rgb(100, 116, 139))
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.9f))
            addView(pageButton(
                label = activity.getString(R.string.history_page_next, pageSize),
                enabled = canNextPage(),
            ) { nextPage(onPageChanged) }, LinearLayout.LayoutParams(0, dp(38), 1f))
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(4) })
    }

    private fun pageButton(label: String, enabled: Boolean, action: () -> Unit): TextView {
        val textColor = if (enabled) Color.rgb(55, 65, 81) else Color.rgb(156, 163, 175)
        val backgroundColor = if (enabled) Color.rgb(248, 250, 252) else Color.rgb(249, 250, 251)
        return TextView(activity).apply {
            text = label
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(textColor)
            isEnabled = enabled
            isClickable = enabled
            isFocusable = enabled
            setPadding(dp(6), 0, dp(6), 0)
            background = roundedRect(backgroundColor, dp(10), Color.rgb(226, 232, 240), dp(1))
            if (enabled) setOnClickListener { action() }
        }
    }

    private fun pages(): List<List<FilteredFullLogEntry>> = TimelinePageMode.pages(
        context = activity,
        items = entries,
        pageSize = ConfigStore.load(activity).homeCallPageSize.coerceIn(5, 100),
        groupKey = { entry -> TimelineGroupKeys.week(entry.row.timeMs) },
    )

    private fun status(textValue: String, error: Boolean = false): TextView = TextView(activity).apply {
        text = textValue
        textSize = 13.5f
        if (error) setTypeface(typeface, Typeface.BOLD)
        setTextColor(if (error) Color.rgb(185, 28, 28) else Color.rgb(100, 116, 139))
        gravity = Gravity.CENTER
        setPadding(dp(12), dp(24), dp(12), dp(24))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
    }
}
