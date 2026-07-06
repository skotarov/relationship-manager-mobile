package com.onlineimoti.calllog

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.onlineimoti.calllog.databinding.ActivityHomeBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal class HomeContentRenderer(
    private val activity: AppCompatActivity, private val binding: ActivityHomeBinding,
    private val activePhoneFilter: () -> String, private val activeSearchQuery: () -> String,
    private val pageIndex: () -> Int, private val isCrmModeEnabled: () -> Boolean,
    private val hasActiveCrmFilters: () -> Boolean, private val dp: (Int) -> Int,
    private val roundedRect: (Int, Int, Int, Int) -> GradientDrawable,
    private val rowRenderer: HomeCallRowRenderer, private val dialFilteredPhone: (String) -> Unit,
    private val companyGeneralNotes: HomeCompanyGeneralNotesController,
    private val scopeChipsUi: HomeCompanyScopeChipsUi,
) {
    var currentCalls: List<PhoneCallRecord> = emptyList(); private set
    private var currentCallNotesByCall: Map<String, HomeCallNote> = emptyMap()
    private val notesUi by lazy { TimelineNotesUi(activity, dp, roundedRect) }

    fun replaceCurrentCalls(calls: List<PhoneCallRecord>) { currentCalls = calls }
    fun clearCalls() { currentCalls = emptyList(); currentCallNotesByCall = emptyMap() }
    fun prepareForRender(pageSize: Int, keepExistingRows: Boolean) {
        binding.previousCallsButton.text = activity.getString(R.string.dynamic_home_previous_calls, pageSize)
        binding.nextCallsButton.text = activity.getString(R.string.dynamic_home_next_calls, pageSize)
        if (!keepExistingRows || currentCalls.isEmpty()) binding.homeCallsContainer.removeAllViews()
        binding.fullLogProgress.visibility = View.GONE
        binding.clearFilterButton.visibility = if (isFilteredFullLogMode() || activePhoneFilter().isBlank()) View.GONE else View.VISIBLE
        updateCrmModeControls(); updatePhoneFilterStatusStyle(); renderFilteredContactSummary()
    }
    fun showMissingCallLogPermission() {
        binding.homeStatusText.text = activity.getString(R.string.dynamic_home_missing_call_log_permission)
        binding.paginationContainer.visibility = View.GONE
    }
    fun showCrmLoading() {
        binding.homeStatusText.text = activity.getString(R.string.runtime_crm_calls_loading)
        binding.paginationContainer.visibility = View.GONE
    }
    fun applyRenderData(renderData: HomeRenderData, pageSize: Int) = applyRenderData(renderData, pageSize, true)
    fun renderCurrentRowsAfterCompanyLabels(pageSize: Int) {
        if (activePhoneFilter().isNotBlank()) { renderFilteredContactSummary(); return }
        if (currentCalls.isEmpty()) return
        applyRenderData(HomeRenderData(currentCalls, HomeCallPageLoader.contactNotes(activity, currentCalls), HomeCallPageLoader.contactNames(activity, currentCalls), currentCallNotesByCall), pageSize, false)
    }
    fun renderEmptyState() {
        binding.fullLogProgress.visibility = View.GONE
        val phone = activePhoneFilter(); val query = activeSearchQuery(); val page = pageIndex()
        binding.homeStatusText.text = when {
            query.isNotBlank() -> activity.getString(R.string.dynamic_home_no_search_results, query.trim())
            phone.isNotBlank() && page == 0 -> activity.getString(R.string.dynamic_home_filter_no_calls_or_sms, phone)
            isCrmModeEnabled() && hasActiveCrmFilters() -> activity.getString(R.string.dynamic_home_no_crm_filter_results)
            page == 0 -> activity.getString(R.string.dynamic_home_no_calls)
            else -> activity.getString(R.string.dynamic_home_no_more_calls)
        }
        updatePhoneFilterStatusStyle(); PaginationButtonAppearance.apply(binding.previousCallsButton, page > 0)
        PaginationButtonAppearance.apply(binding.nextCallsButton, false)
        binding.pageText.text = activity.getString(R.string.dynamic_home_page, page + 1)
        binding.paginationContainer.visibility = View.VISIBLE
    }
    private fun applyRenderData(data: HomeRenderData, pageSize: Int, refreshCompanyLabels: Boolean) {
        val calls = data.calls.sortedByDescending { it.startedAt }; currentCalls = calls; currentCallNotesByCall = data.callNotesByCall
        binding.homeCallsContainer.removeAllViews(); binding.fullLogProgress.visibility = View.GONE; renderStatusAndPagination(pageSize)
        val filtered = activePhoneFilter().isNotBlank(); val labels = if (filtered) emptyMap() else companyGeneralNotes.labelsFor(calls)
        val today = HomeTimelineDateUi.localDaySerial(System.currentTimeMillis()) ?: 0L; var previous: Long? = null
        calls.forEach { call ->
            val day = HomeTimelineDateUi.localDaySerial(call.startedAt)
            if (day != null && day != previous) { binding.homeCallsContainer.addView(dateSeparator(call.startedAt, today - day)); previous = day }
            val key = HomeCallPageLoader.noteKey(call.number)
            binding.homeCallsContainer.addView(rowRenderer.compactCallRow(
                call, data.contactNamesByNumber[key].orEmpty().ifBlank { call.displayName },
                if (filtered) null else data.contactNotesByNumber[key], if (filtered) null else labels[key],
                data.callNotesByCall[HomeCallNotesResolver.keyFor(call)], activeSearchQuery(), !filtered, !filtered, !filtered,
            ))
        }
        if (!filtered && refreshCompanyLabels) companyGeneralNotes.refresh(calls)
    }
    private fun dateSeparator(timestamp: Long, relativeDays: Long): TextView {
        val locale = if (AppLocaleText.isBulgarian()) Locale("bg", "BG") else Locale.US
        val label = "${SimpleDateFormat("EEEE, d MMMM yyyy", locale).format(Date(timestamp))} (${HomeTimelineDateUi.relativeDaysLabel(activity, relativeDays)})"
        return TextView(activity).apply {
            text = label; textSize = 12.5f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(activity.getColor(R.color.callreport_icon_background)); gravity = Gravity.CENTER_VERTICAL; background = null
            setPadding(dp(10), dp(6), dp(10), dp(6)); layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = if (binding.homeCallsContainer.childCount == 0) 0 else dp(8); bottomMargin = dp(4)
            }
        }
    }
    private fun renderFilteredContactSummary() {
        val container = binding.filteredContactSummaryContainer; container.removeAllViews(); val phone = activePhoneFilter()
        if (phone.isBlank()) { container.visibility = View.GONE; return }
        val name = ContactGroupFilter.resolveDisplayName(activity, phone).orEmpty().takeIf { HomeCallPageLoader.noteKey(it) != HomeCallPageLoader.noteKey(phone) }.orEmpty()
        val summary = PhoneCallRecord(phone, "", "", 0L, 0L)
        val labels = companyGeneralNotes.labelsFor(listOf(summary))[HomeCallPageLoader.noteKey(phone)]
        val crm = CallReportRemoteAccess.isReady(ConfigStore.load(activity.applicationContext)) && CrmContactSyncStore.isEnabled(activity.applicationContext, phone)
        val identity = scopeChipsUi.inlineCrmIdentity(name.ifBlank { phone }, labels, crm); val note = ContactNoteReader.generalNoteForPhone(activity, phone).orEmpty()
        container.visibility = View.VISIBLE
        if (isFilteredFullLogMode()) container.addView(fullLogContactHeader(phone, identity)) else container.addView(summaryText(identity, 18f, true, dp(2)))
        if (name.isNotBlank()) container.addView(summaryText(phone, 14f, false, dp(2)))
        if (note.isNotBlank()) {
            val colors = NoteUiStyle.General
            container.addView(TextView(activity).apply {
                text = note; setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_note_lines, 0, 0, 0); compoundDrawablePadding = dp(5)
                setTextColor(colors.text); textSize = 13f; maxLines = 4; setPadding(dp(10), dp(7), dp(10), dp(7)); background = roundedRect(colors.background, dp(10), colors.border, dp(1))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(5) }
            })
        }
        notesUi.addCompanyGeneralNotes(container, labels, "", true); companyGeneralNotes.refresh(listOf(summary))
    }
    private fun fullLogContactHeader(phone: String, identity: CharSequence): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        addView(ImageButton(activity).apply {
            setImageResource(R.drawable.ic_phone_call); contentDescription = activity.getString(R.string.runtime_dial_number, phone); background = null; setBackgroundColor(Color.TRANSPARENT)
            scaleType = ImageView.ScaleType.CENTER; setPadding(dp(6), dp(6), dp(6), dp(6)); layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(4) }
            setOnClickListener { dialFilteredPhone(phone) }
        })
        addView(summaryText(identity, 18f, true, dp(2)).apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })
    }
    private fun summaryText(value: CharSequence, size: Float, bold: Boolean, bottom: Int): TextView = TextView(activity).apply {
        text = value; setTextColor(if (size >= 18f) activity.getColor(R.color.calllog_text) else activity.getColor(R.color.calllog_muted_text)); textSize = size
        if (bold) setTypeface(typeface, Typeface.BOLD); setPadding(dp(4), 0, dp(4), bottom)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    }
    private fun renderStatusAndPagination(pageSize: Int) {
        val page = pageIndex(); val phone = activePhoneFilter(); val query = activeSearchQuery(); val start = page * pageSize + 1; val end = page * pageSize + currentCalls.size
        binding.homeStatusText.text = when {
            query.isNotBlank() && phone.isNotBlank() -> activity.getString(R.string.dynamic_home_status_filter_search, phone, query.trim(), start, end)
            query.isNotBlank() -> activity.getString(R.string.dynamic_home_status_search, query.trim(), start, end)
            phone.isNotBlank() -> activity.getString(R.string.dynamic_home_status_filter, phone, start, end)
            else -> activity.getString(R.string.dynamic_home_status_calls, start, end)
        }
        updatePhoneFilterStatusStyle(); PaginationButtonAppearance.apply(binding.previousCallsButton, page > 0); PaginationButtonAppearance.apply(binding.nextCallsButton, currentCalls.size >= pageSize)
        binding.pageText.text = activity.getString(R.string.dynamic_home_page, page + 1); binding.paginationContainer.visibility = View.VISIBLE
    }
    private fun updatePhoneFilterStatusStyle() {
        val filtered = activePhoneFilter().isNotBlank(); val fullLog = isFilteredFullLogMode(); binding.homeStatusRow.visibility = if (fullLog) View.GONE else View.VISIBLE
        binding.filteredDialButton.visibility = if (filtered && !fullLog) View.VISIBLE else View.GONE
        if (filtered && !fullLog) {
            binding.filteredStatusContainer.background = roundedRect(Color.rgb(255, 237, 213), dp(12), Color.rgb(251, 146, 60), dp(1)); binding.filteredStatusContainer.setPadding(dp(10), dp(2), dp(4), dp(2))
            binding.homeStatusText.background = null; binding.homeStatusText.setTextColor(Color.rgb(154, 52, 18)); binding.homeStatusText.setPadding(0, dp(4), 0, dp(4))
        } else {
            binding.filteredStatusContainer.background = null; binding.filteredStatusContainer.setPadding(0, 0, 0, 0); binding.homeStatusText.background = null
            binding.homeStatusText.setTextColor(Color.rgb(71, 85, 105)); binding.homeStatusText.setPadding(0, 0, 0, 0)
        }
    }
    private fun isFilteredFullLogMode() = activePhoneFilter().isNotBlank() && activeSearchQuery().isBlank()
    private fun updateCrmModeControls() {
        val visible = HomeCrmModeStore.isAvailable(activity) && activePhoneFilter().isBlank(); binding.crmControlsScroll.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible) return
        val active = isCrmModeEnabled(); val fill = if (active) activity.getColor(R.color.callreport_icon_background) else Color.WHITE
        val border = if (active) activity.getColor(R.color.callreport_icon_background) else Color.rgb(203, 213, 225)
        binding.crmModeButton.backgroundTintList = ColorStateList.valueOf(fill); binding.crmModeButton.strokeColor = ColorStateList.valueOf(border)
        binding.crmModeButton.setTextColor(if (active) Color.WHITE else Color.rgb(51, 65, 85))
    }
}
