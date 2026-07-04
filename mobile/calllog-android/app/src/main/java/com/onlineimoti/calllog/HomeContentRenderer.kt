package com.onlineimoti.calllog

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.onlineimoti.calllog.databinding.ActivityHomeBinding
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Owns Home's rendered call rows, status text and CRM presentation controls. */
internal class HomeContentRenderer(
    private val activity: AppCompatActivity,
    private val binding: ActivityHomeBinding,
    private val activePhoneFilter: () -> String,
    private val activeSearchQuery: () -> String,
    private val pageIndex: () -> Int,
    private val isCrmModeEnabled: () -> Boolean,
    private val hasActiveCrmFilters: () -> Boolean,
    private val dp: (Int) -> Int,
    private val roundedRect: (Int, Int, Int, Int) -> GradientDrawable,
    private val rowRenderer: HomeCallRowRenderer,
    private val companyGeneralNotes: HomeCompanyGeneralNotesController,
    private val scopeChipsUi: HomeCompanyScopeChipsUi,
) {
    var currentCalls: List<PhoneCallRecord> = emptyList()
        private set
    private var currentCallNotesByCall: Map<String, HomeCallNote> = emptyMap()
    private val dateFormatter = SimpleDateFormat("EEEE, d MMMM yyyy", Locale("bg", "BG"))

    fun replaceCurrentCalls(calls: List<PhoneCallRecord>) {
        currentCalls = calls
    }

    fun clearCalls() {
        currentCalls = emptyList()
        currentCallNotesByCall = emptyMap()
    }

    fun prepareForRender(pageSize: Int, keepExistingRows: Boolean) {
        binding.previousCallsButton.text = activity.getString(R.string.dynamic_home_previous_calls, pageSize)
        binding.nextCallsButton.text = activity.getString(R.string.dynamic_home_next_calls, pageSize)
        if (!keepExistingRows || currentCalls.isEmpty()) binding.homeCallsContainer.removeAllViews()
        binding.fullLogProgress.visibility = View.GONE
        binding.clearFilterButton.visibility = if (activePhoneFilter().isBlank()) View.GONE else View.VISIBLE
        updateCrmModeControls()
        updatePhoneFilterStatusStyle()
        renderFilteredContactSummary()
    }

    fun showMissingCallLogPermission() {
        binding.homeStatusText.text = activity.getString(R.string.dynamic_home_missing_call_log_permission)
        binding.paginationContainer.visibility = View.GONE
    }

    fun showCrmLoading() {
        binding.homeStatusText.text = "Зареждане на CRM разговори…"
        binding.paginationContainer.visibility = View.GONE
    }

    fun applyRenderData(renderData: HomeRenderData, pageSize: Int) {
        applyRenderData(renderData, pageSize, refreshCompanyLabels = true)
    }

    fun renderCurrentRowsAfterCompanyLabels(pageSize: Int) {
        if (currentCalls.isEmpty() || activePhoneFilter().isNotBlank()) return
        val calls = currentCalls
        applyRenderData(
            HomeRenderData(
                calls = calls,
                contactNotesByNumber = HomeCallPageLoader.contactNotes(activity, calls),
                contactNamesByNumber = HomeCallPageLoader.contactNames(activity, calls),
                callNotesByCall = currentCallNotesByCall,
            ),
            pageSize = pageSize,
            refreshCompanyLabels = false,
        )
    }

    fun renderEmptyState() {
        binding.fullLogProgress.visibility = View.GONE
        val phoneFilter = activePhoneFilter()
        val searchQuery = activeSearchQuery()
        val currentPage = pageIndex()
        binding.homeStatusText.text = when {
            searchQuery.isNotBlank() -> activity.getString(R.string.dynamic_home_no_search_results, searchQuery.trim())
            phoneFilter.isNotBlank() && currentPage == 0 -> activity.getString(R.string.dynamic_home_filter_no_calls_or_sms, phoneFilter)
            isCrmModeEnabled() && hasActiveCrmFilters() -> activity.getString(R.string.dynamic_home_no_crm_filter_results)
            currentPage == 0 -> activity.getString(R.string.dynamic_home_no_calls)
            else -> activity.getString(R.string.dynamic_home_no_more_calls)
        }
        updatePhoneFilterStatusStyle()
        binding.previousCallsButton.isEnabled = currentPage > 0
        binding.nextCallsButton.isEnabled = false
        binding.pageText.text = activity.getString(R.string.dynamic_home_page, currentPage + 1)
        binding.paginationContainer.visibility = View.VISIBLE
    }

    private fun applyRenderData(
        renderData: HomeRenderData,
        pageSize: Int,
        refreshCompanyLabels: Boolean,
    ) {
        // Home is always a chronological call log. Keep the same newest-first
        // ordering for normal view, CRM, a phone filter and search results.
        val chronologicalCalls = renderData.calls.sortedByDescending { call -> call.startedAt }
        currentCalls = chronologicalCalls
        currentCallNotesByCall = renderData.callNotesByCall
        binding.homeCallsContainer.removeAllViews()
        binding.fullLogProgress.visibility = View.GONE
        renderStatusAndPagination(pageSize)
        val phoneFiltered = activePhoneFilter().isNotBlank()
        val companyLabels = if (phoneFiltered) emptyMap() else companyGeneralNotes.labelsFor(chronologicalCalls)
        val todaySerial = localDaySerial(System.currentTimeMillis()) ?: 0L
        var previousDaySerial: Long? = null
        chronologicalCalls.forEach { call ->
            val daySerial = localDaySerial(call.startedAt)
            if (daySerial != null && daySerial != previousDaySerial) {
                binding.homeCallsContainer.addView(dateSeparator(call.startedAt, todaySerial - daySerial))
                previousDaySerial = daySerial
            }
            val key = HomeCallPageLoader.noteKey(call.number)
            val callNote = renderData.callNotesByCall[HomeCallNotesResolver.keyFor(call)]
            binding.homeCallsContainer.addView(
                rowRenderer.compactCallRow(
                    call = call,
                    displayName = renderData.contactNamesByNumber[key].orEmpty().ifBlank { call.displayName },
                    contactNote = if (phoneFiltered) null else renderData.contactNotesByNumber[key],
                    companyGeneralNoteLabels = if (phoneFiltered) null else companyLabels[key],
                    callNote = callNote,
                    highlightQuery = activeSearchQuery(),
                    showContactIdentity = !phoneFiltered,
                    showGeneralContactNote = !phoneFiltered,
                    showQuickActions = !phoneFiltered,
                ),
            )
        }
        if (!phoneFiltered && refreshCompanyLabels) companyGeneralNotes.refresh(chronologicalCalls)
    }

    /** A date line is shown before the first call of every local calendar day. */
    private fun dateSeparator(timestampMs: Long, relativeDays: Long): TextView {
        val label = "${dateFormatter.format(Date(timestampMs))} (${relativeDaysLabel(relativeDays)})"
        return TextView(activity).apply {
            text = label
            textSize = 12.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(71, 85, 105))
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = roundedRect(
                Color.rgb(241, 245, 249),
                dp(10),
                Color.rgb(203, 213, 225),
                dp(1),
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = if (binding.homeCallsContainer.childCount == 0) 0 else dp(8)
                bottomMargin = dp(4)
            }
        }
    }

    private fun relativeDaysLabel(days: Long): String = when {
        days == 0L -> "преди 0 дни"
        days == 1L -> "преди 1 ден"
        days > 1L -> "преди $days дни"
        days == -1L -> "след 1 ден"
        else -> "след ${-days} дни"
    }

    /** Calendar-day serial avoids daylight-saving-time errors around midnight. */
    private fun localDaySerial(timestampMs: Long): Long? {
        if (timestampMs <= 0L) return null
        val calendar = Calendar.getInstance().apply { timeInMillis = timestampMs }
        val yearBefore = (calendar.get(Calendar.YEAR) - 1).toLong()
        val daysBeforeYear = 365L * yearBefore + yearBefore / 4L - yearBefore / 100L + yearBefore / 400L
        return daysBeforeYear + calendar.get(Calendar.DAY_OF_YEAR).toLong() - 1L
    }

    private fun renderFilteredContactSummary() {
        val container = binding.filteredContactSummaryContainer
        container.removeAllViews()
        val phone = activePhoneFilter()
        if (phone.isBlank()) {
            container.visibility = View.GONE
            return
        }
        val name = ContactGroupFilter.resolveDisplayName(activity, phone).orEmpty()
            .takeIf { HomeCallPageLoader.noteKey(it) != HomeCallPageLoader.noteKey(phone) }
            .orEmpty()
        val summary = PhoneCallRecord(phone, "", "", 0L, 0L)
        val labels = companyGeneralNotes.labelsFor(listOf(summary))[HomeCallPageLoader.noteKey(phone)]
        val crm = CallReportRemoteAccess.isReady(ConfigStore.load(activity.applicationContext)) &&
            CrmContactSyncStore.isEnabled(activity.applicationContext, phone)
        val note = ContactNoteReader.generalNoteForPhone(activity, phone).orEmpty()
        container.visibility = View.VISIBLE
        container.addView(summaryText(name.ifBlank { phone }, 18f, bold = true, bottom = dp(2)))
        if (name.isNotBlank()) container.addView(summaryText(phone, 14f, bold = false, bottom = dp(2)))
        if (crm || !labels.isNullOrEmpty()) container.addView(scopeChipsUi.create(labels, crm))
        if (note.isNotBlank()) {
            val colors = NoteUiStyle.General
            container.addView(TextView(activity).apply {
                text = note
                setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_note_lines, 0, 0, 0)
                compoundDrawablePadding = dp(5)
                setTextColor(colors.text)
                textSize = 13f
                maxLines = 4
                setPadding(dp(10), dp(7), dp(10), dp(7))
                background = roundedRect(colors.background, dp(10), colors.border, dp(1))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(5) }
            })
        }
        companyGeneralNotes.refresh(listOf(summary))
    }

    private fun summaryText(value: String, size: Float, bold: Boolean, bottom: Int): TextView = TextView(activity).apply {
        text = value
        setTextColor(if (size >= 18f) activity.getColor(R.color.calllog_text) else activity.getColor(R.color.calllog_muted_text))
        textSize = size
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(4), 0, dp(4), bottom)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
    }

    private fun renderStatusAndPagination(pageSize: Int) {
        val currentPage = pageIndex()
        val phoneFilter = activePhoneFilter()
        val searchQuery = activeSearchQuery()
        val start = currentPage * pageSize + 1
        val end = currentPage * pageSize + currentCalls.size
        binding.homeStatusText.text = when {
            searchQuery.isNotBlank() && phoneFilter.isNotBlank() -> activity.getString(
                R.string.dynamic_home_status_filter_search,
                phoneFilter,
                searchQuery.trim(),
                start,
                end,
            )
            searchQuery.isNotBlank() -> activity.getString(R.string.dynamic_home_status_search, searchQuery.trim(), start, end)
            phoneFilter.isNotBlank() -> activity.getString(R.string.dynamic_home_status_filter, phoneFilter, start, end)
            else -> activity.getString(R.string.dynamic_home_status_calls, start, end)
        }
        updatePhoneFilterStatusStyle()
        binding.previousCallsButton.isEnabled = currentPage > 0
        binding.nextCallsButton.isEnabled = currentCalls.size >= pageSize
        binding.pageText.text = activity.getString(R.string.dynamic_home_page, currentPage + 1)
        binding.paginationContainer.visibility = View.VISIBLE
    }

    private fun updatePhoneFilterStatusStyle() {
        val filtered = activePhoneFilter().isNotBlank()
        binding.filteredDialButton.visibility = if (filtered) View.VISIBLE else View.GONE
        if (filtered) {
            binding.filteredStatusContainer.background = roundedRect(
                Color.rgb(255, 237, 213),
                dp(12),
                Color.rgb(251, 146, 60),
                dp(1),
            )
            binding.filteredStatusContainer.setPadding(dp(10), dp(2), dp(4), dp(2))
            binding.homeStatusText.background = null
            binding.homeStatusText.setTextColor(Color.rgb(154, 52, 18))
            binding.homeStatusText.setPadding(0, dp(4), 0, dp(4))
        } else {
            binding.filteredStatusContainer.background = null
            binding.filteredStatusContainer.setPadding(0, 0, 0, 0)
            binding.homeStatusText.background = null
            binding.homeStatusText.setTextColor(Color.rgb(71, 85, 105))
            binding.homeStatusText.setPadding(0, 0, 0, 0)
        }
    }

    private fun updateCrmModeControls() {
        val serverEnabled = HomeCrmModeStore.isAvailable(activity)
        binding.crmControlsScroll.visibility = if (serverEnabled) View.VISIBLE else View.GONE
        if (!serverEnabled) return
        val active = isCrmModeEnabled()
        val fill = if (active) activity.getColor(R.color.callreport_icon_background) else Color.WHITE
        val border = if (active) activity.getColor(R.color.callreport_icon_background) else Color.rgb(203, 213, 225)
        binding.crmModeButton.backgroundTintList = ColorStateList.valueOf(fill)
        binding.crmModeButton.strokeColor = ColorStateList.valueOf(border)
        binding.crmModeButton.setTextColor(if (active) Color.WHITE else Color.rgb(51, 65, 85))
    }
}
