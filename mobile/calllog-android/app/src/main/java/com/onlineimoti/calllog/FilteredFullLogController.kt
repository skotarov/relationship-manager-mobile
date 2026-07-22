package com.onlineimoti.calllog

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.View
import com.onlineimoti.calllog.databinding.ActivityHomeBinding
import java.util.concurrent.Executors

/** Temporary Home adapter around the shared filtered full-log loader and renderer. */
internal class FilteredFullLogController(
    private val activity: Activity,
    private val binding: ActivityHomeBinding,
    private val dp: (Int) -> Int,
    private val roundedRect: (Int, Int, Int, Int) -> GradientDrawable,
    private val openContactNotes: (PhoneCallRecord, String) -> Unit,
    private val openCallNoteEditor: (PhoneCallRecord, String, HomeCallNote?) -> Unit,
    private val pageSize: () -> Int,
    private val onStateChanged: () -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val rowRenderer by lazy {
        FilteredFullLogRowRenderer(activity, dp, roundedRect, openContactNotes, openCallNoteEditor)
    }
    private val weekUi by lazy { CallReportHistoryWeekUi(activity, dp) }
    private var selectedPhone = ""
    private var loadedPhone = ""
    private var loadedRemoteEnabled: Boolean? = null
    private var loading = false
    private var pageIndex = 0
    private var loadGeneration = 0
    private var busyToken = 0L
    private var entries: List<FilteredFullLogEntry> = emptyList()
    private var errorText = ""

    fun invalidate() {
        loadGeneration += 1
        finishBusy()
        loadedPhone = ""
        loadedRemoteEnabled = null
        loading = false
        pageIndex = 0
        entries = emptyList()
        errorText = ""
    }

    fun previousPage() {
        if (loading || pageIndex <= 0) return
        pageIndex -= 1
        onStateChanged()
    }

    fun nextPage() {
        if (loading || pageIndex >= lastPageIndex()) return
        pageIndex += 1
        onStateChanged()
    }

    fun isOnLaterPage(): Boolean = pageIndex > 0

    fun goToFirstPage() {
        if (loading || pageIndex <= 0) return
        pageIndex = 0
        onStateChanged()
    }

    fun render(phone: String) {
        if (phone.isBlank()) return
        val remoteEnabled = CallReportRemoteAccess.isEnabled(activity)
        val modeChanged = loadedRemoteEnabled != null && loadedRemoteEnabled != remoteEnabled
        if (selectedPhone != phone || modeChanged) {
            selectedPhone = phone
            invalidate()
        }
        if (loadedPhone != phone && !loading) startLoad(phone, remoteEnabled)
        val size = safePageSize()
        val pages = timelinePages(size)
        val pageCount = maxOf(1, pages.size)
        pageIndex = pageIndex.coerceIn(0, pageCount - 1)
        val pageEntries = if (loading) emptyList() else pages.getOrNull(pageIndex).orEmpty()
        binding.homeCallsContainer.removeAllViews()
        binding.fullLogProgress.visibility = if (loading) View.VISIBLE else View.GONE
        binding.paginationContainer.visibility = View.VISIBLE
        binding.previousCallsButton.text = activity.getString(R.string.dynamic_home_previous_calls, size)
        binding.nextCallsButton.text = activity.getString(R.string.dynamic_home_next_calls, size)
        PaginationButtonAppearance.apply(binding.previousCallsButton, !loading && pageIndex > 0)
        PaginationButtonAppearance.apply(binding.nextCallsButton, !loading && pageIndex < pageCount - 1)
        binding.pageText.text = activity.getString(R.string.dynamic_home_page, pageIndex + 1)
        binding.homeStatusText.text = statusText(remoteEnabled, pageEntries, pages)
        renderPageEntries(phone, pageEntries, remoteEnabled)
    }

    fun release() {
        finishBusy()
        executor.shutdownNow()
        handler.removeCallbacksAndMessages(null)
    }

    private fun renderPageEntries(
        phone: String,
        pageEntries: List<FilteredFullLogEntry>,
        remoteEnabled: Boolean,
    ) {
        val currentWeekSerial = weekUi.currentWeekSerial()
        var previousWeekSerial: Long? = null
        pageEntries.forEach { entry ->
            val weekSerial = weekUi.weekStartSerial(entry.row.timeMs)
            if (weekSerial != null && weekSerial != previousWeekSerial) {
                val relativeWeeks = currentWeekSerial
                    ?.let { (it - weekSerial) / CallReportHistoryWeekUi.DAYS_PER_WEEK }
                    ?: 0L
                binding.homeCallsContainer.addView(weekUi.separator(entry.row.timeMs, relativeWeeks))
                previousWeekSerial = weekSerial
            }
            val row = rowRenderer.rowView(phone, entry, remoteEnabled)
            binding.homeCallsContainer.addView(ListThemeUi.applyRowSpacing(row, dp))
        }
    }

    private fun statusText(
        remoteEnabled: Boolean,
        pageEntries: List<FilteredFullLogEntry>,
        pages: List<List<FilteredFullLogEntry>>,
    ): String = when {
        loading -> "Зареждам пълния лог…"
        errorText.isNotBlank() -> errorText
        entries.isEmpty() -> if (remoteEnabled) {
            "Няма локални или сървърни записи за този номер"
        } else {
            "Няма локални записи за този номер"
        }
        else -> {
            val first = pages.take(pageIndex).sumOf { it.size } + 1
            val last = first + pageEntries.size - 1
            "Пълен лог: $first–$last от ${entries.size}"
        }
    }

    private fun startLoad(phone: String, remoteEnabled: Boolean) {
        loading = true
        finishBusy()
        busyToken = HomeBusyTooltipUi.begin(activity, HomeBusyWork.FULL_LOG)
        val requestedPhone = phone
        val generation = ++loadGeneration
        val requestBusyToken = busyToken
        executor.execute {
            val result = runCatching {
                FilteredFullLogLoader.load(activity.applicationContext, requestedPhone, remoteEnabled)
            }
            handler.post {
                finishBusy(requestBusyToken)
                if (
                    activity.isFinishing || activity.isDestroyed ||
                    generation != loadGeneration || requestedPhone != selectedPhone
                ) return@post
                if (remoteEnabled != CallReportRemoteAccess.isEnabled(activity)) {
                    loading = false
                    loadedPhone = ""
                    loadedRemoteEnabled = null
                    onStateChanged()
                    return@post
                }
                loading = false
                result.onSuccess {
                    entries = it
                    loadedPhone = requestedPhone
                    loadedRemoteEnabled = remoteEnabled
                    errorText = ""
                }.onFailure {
                    entries = emptyList()
                    loadedPhone = requestedPhone
                    loadedRemoteEnabled = remoteEnabled
                    errorText = "Пълният лог не е зареден"
                }
                onStateChanged()
            }
        }
    }

    private fun finishBusy(token: Long = busyToken) {
        if (token <= 0L) return
        if (busyToken == token) busyToken = 0L
        HomeBusyTooltipUi.end(activity, token)
    }

    private fun timelinePages(size: Int): List<List<FilteredFullLogEntry>> = TimelinePageMode.pages(
        context = activity,
        items = entries,
        pageSize = size,
        groupKey = { entry -> TimelineGroupKeys.week(entry.row.timeMs) },
    )

    private fun safePageSize() = pageSize().coerceIn(5, 100)
    private fun lastPageIndex() = maxOf(0, timelinePages(safePageSize()).size - 1)
}
