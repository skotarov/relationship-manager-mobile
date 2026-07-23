package com.onlineimoti.calllog

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.onlineimoti.calllog.databinding.ActivityHomeBinding

/** Renders the ordinary Call Log, search results and CRM call rows. */
internal class HomeContentRenderer(
    private val activity: AppCompatActivity,
    private val binding: ActivityHomeBinding,
    private val activeSearchQuery: () -> String,
    private val pageIndex: () -> Int,
    private val isCrmModeEnabled: () -> Boolean,
    private val isCrmContactsMode: () -> Boolean,
    private val hasActiveCrmFilters: () -> Boolean,
    private val dp: (Int) -> Int,
    private val rowRenderer: HomeCallRowRenderer,
    private val companyGeneralNotes: HomeCompanyGeneralNotesController,
    private val retainRowsDuringEdgePaging: () -> Boolean = { false },
) {
    var currentCalls: List<PhoneCallRecord> = emptyList()
        private set

    private var currentContactNotesByNumber: Map<String, String> = emptyMap()
    private var currentContactNamesByNumber: Map<String, String> = emptyMap()
    private var currentCallNotesByCall: Map<String, HomeCallNote> = emptyMap()
    private val rememberedContactNamesByNumber = linkedMapOf<String, String>()
    private val chromeUi by lazy {
        HomeContentChromeUi(
            activity = activity,
            binding = binding,
            activeSearchQuery = activeSearchQuery,
            isCrmModeEnabled = isCrmModeEnabled,
            isCrmContactsMode = isCrmContactsMode,
            dp = dp,
        )
    }

    fun replaceCurrentCalls(calls: List<PhoneCallRecord>) {
        currentCalls = calls
    }

    fun clearCalls() {
        currentCalls = emptyList()
        currentContactNotesByNumber = emptyMap()
        currentContactNamesByNumber = emptyMap()
        currentCallNotesByCall = emptyMap()
        HomePagedListUi.clear(binding.homeCallsContainer)
        HomeLoadingFooterUi.hide(binding.homeCallsContainer)
    }

    fun prepareForRender(pageSize: Int, keepExistingRows: Boolean) {
        binding.previousCallsButton.text = activity.getString(R.string.dynamic_home_previous_calls, pageSize)
        binding.nextCallsButton.text = activity.getString(R.string.dynamic_home_next_calls, pageSize)
        val retainRows = keepExistingRows || retainRowsDuringEdgePaging()
        HomePagedListUi.prepare(
            binding.homeCallsContainer,
            PageLoadingModeStore.usesPrefetch(activity),
            pageIndex(),
            reset = !retainRows || currentCalls.isEmpty(),
        )
        binding.fullLogProgress.visibility = View.GONE
        binding.clearFilterButton.visibility = View.GONE
        binding.filteredDialButton.visibility = View.GONE
        binding.filteredContactSummaryContainer.visibility = View.GONE
        updateCrmModeControls()
        chromeUi.updateStatusStyle(hidePlainTimelineRange = true)
    }

    fun showLoading() {
        binding.fullLogProgress.visibility = View.GONE
        HomeLoadingFooterUi.show(binding.homeCallsContainer)
        binding.paginationContainer.visibility = View.GONE
        if (currentCalls.isEmpty()) {
            binding.homeStatusText.text = activity.getString(R.string.runtime_crm_calls_loading)
        }
    }

    fun showMissingCallLogPermission() {
        val text = activity.getString(R.string.dynamic_home_missing_call_log_permission)
        if (isTopLevelCrmPage()) showResultsStatus(text) else {
            binding.homeStatusText.text = text
            chromeUi.updateStatusStyle()
        }
        binding.fullLogProgress.visibility = View.GONE
        HomeLoadingFooterUi.hide(binding.homeCallsContainer)
        binding.paginationContainer.visibility = View.GONE
    }

    fun showCrmLoading() {
        if (retainRowsDuringEdgePaging()) HomeLoadingFooterUi.show(binding.homeCallsContainer)
        else showResultsStatus(activity.getString(R.string.runtime_crm_calls_loading))
        binding.paginationContainer.visibility = View.GONE
    }

    fun applyRenderData(renderData: HomeRenderData, pageSize: Int) = applyRenderData(
        renderData,
        pageSize,
        refreshCompanyLabels = true,
        mergeMode = HomeRenderMergeMode.AUTHORITATIVE,
    )

    fun applyProvisionalRenderData(renderData: HomeRenderData, pageSize: Int) = applyRenderData(
        renderData,
        pageSize,
        refreshCompanyLabels = true,
        mergeMode = HomeRenderMergeMode.PROVISIONAL,
    )

    fun applySupplementalRenderData(renderData: HomeRenderData, pageSize: Int) = applyRenderData(
        renderData,
        pageSize,
        refreshCompanyLabels = true,
        mergeMode = HomeRenderMergeMode.SUPPLEMENTAL,
    )

    fun renderCurrentRowsAfterCompanyLabels(pageSize: Int) {
        if (currentCalls.isEmpty()) return
        applyRenderData(
            HomeRenderData(
                currentCalls,
                currentContactNotesByNumber,
                currentContactNamesByNumber,
                currentCallNotesByCall,
            ),
            pageSize,
            refreshCompanyLabels = false,
            mergeMode = HomeRenderMergeMode.AUTHORITATIVE,
            forceRender = true,
        )
    }

    fun renderEmptyState() {
        binding.fullLogProgress.visibility = View.GONE
        HomeLoadingFooterUi.hide(binding.homeCallsContainer)
        val query = activeSearchQuery()
        val page = pageIndex()
        val message = when {
            query.isNotBlank() -> activity.getString(R.string.dynamic_home_no_search_results, query.trim())
            isCrmModeEnabled() && hasActiveCrmFilters() -> activity.getString(R.string.dynamic_home_no_crm_filter_results)
            page == 0 -> activity.getString(R.string.dynamic_home_no_calls)
            else -> activity.getString(R.string.dynamic_home_no_more_calls)
        }
        if (isTopLevelCrmPage()) showResultsStatus(message) else binding.homeStatusText.text = message
        chromeUi.updateStatusStyle()
        PaginationButtonAppearance.apply(binding.previousCallsButton, page > 0)
        PaginationButtonAppearance.apply(binding.nextCallsButton, false)
        binding.pageText.text = activity.getString(R.string.dynamic_home_page, page + 1)
        binding.paginationContainer.visibility =
            if (PageLoadingModeStore.usesPrefetch(activity)) View.GONE else View.VISIBLE
    }

    private fun applyRenderData(
        data: HomeRenderData,
        pageSize: Int,
        refreshCompanyLabels: Boolean,
        mergeMode: HomeRenderMergeMode,
        forceRender: Boolean = false,
    ) {
        val calls = data.calls.sortedByDescending { it.startedAt }
        val state = HomeRenderStateMerger.merge(
            calls = calls,
            incoming = data,
            currentContactNotes = currentContactNotesByNumber,
            currentContactNames = currentContactNamesByNumber,
            currentCallNotes = currentCallNotesByCall,
            rememberedNames = rememberedContactNamesByNumber,
            mode = mergeMode,
        )
        val unchanged = calls == currentCalls &&
            state.contactNotesByNumber == currentContactNotesByNumber &&
            state.contactNamesByNumber == currentContactNamesByNumber &&
            state.callNotesByCall == currentCallNotesByCall
        currentCalls = calls
        currentContactNotesByNumber = state.contactNotesByNumber
        currentContactNamesByNumber = state.contactNamesByNumber
        currentCallNotesByCall = state.callNotesByCall
        binding.fullLogProgress.visibility = View.GONE
        chromeUi.renderStatusAndPagination(pageIndex(), pageSize, currentCalls.size)
        val automaticPaging = PageLoadingModeStore.usesPrefetch(activity)
        val hasRenderedRows = HomePagedListUi.hasRenderedRows(
            binding.homeCallsContainer,
            automaticPaging,
            pageIndex(),
        )
        if (HomeRenderReusePolicy.canReuseExistingRows(unchanged, forceRender, hasRenderedRows)) {
            HomeLoadingFooterUi.hide(binding.homeCallsContainer)
            return
        }

        val page = HomePagedListUi.page(
            binding.homeCallsContainer,
            automaticPaging,
            pageIndex(),
        )
        page.removeAllViews()
        val labels = companyGeneralNotes.labelsFor(calls)
        val serverBackedKeys = companyGeneralNotes.serverBackedPhoneKeysFor(calls)
        val today = HomeTimelineDateUi.localDaySerial(System.currentTimeMillis()) ?: 0L
        var previousDay: Long? = null
        calls.forEach { call ->
            val day = HomeTimelineDateUi.localDaySerial(call.startedAt)
            if (day != null && day != previousDay) {
                page.addView(chromeUi.dateSeparator(call.startedAt, today - day, page.childCount > 0))
                previousDay = day
            }
            val key = HomeCallPageLoader.noteKey(call.number)
            val displayName = state.contactNamesByNumber[key].orEmpty().ifBlank { call.displayName }
            val callNote = state.callNotesByCall[HomeCallNotesResolver.keyFor(call)]
            val row = rowRenderer.compactCallRow(
                call = call,
                displayName = displayName,
                contactNote = state.contactNotesByNumber[key],
                companyGeneralNoteLabels = labels[key],
                callNote = callNote,
                highlightQuery = activeSearchQuery(),
                showContactIdentity = true,
                showGeneralContactNote = true,
                serverBacked = key in serverBackedKeys,
            )
            page.addView(ListThemeUi.applyRowSpacing(row, dp))
        }
        HomeLoadingFooterUi.hide(binding.homeCallsContainer)
        if (refreshCompanyLabels) companyGeneralNotes.refresh(calls)
    }

    private fun showResultsStatus(text: String) {
        currentCalls = emptyList()
        currentContactNotesByNumber = emptyMap()
        currentContactNamesByNumber = emptyMap()
        currentCallNotesByCall = emptyMap()
        HomePagedListUi.clear(binding.homeCallsContainer)
        binding.fullLogProgress.visibility = View.GONE
        binding.homeStatusText.text = ""
        binding.homeStatusText.visibility = View.GONE
        binding.homeCallsContainer.addView(TextView(activity).apply {
            this.text = text
            gravity = Gravity.CENTER
            textSize = 14f
            setTextColor(Color.rgb(100, 116, 139))
            setPadding(dp(18), dp(28), dp(18), dp(28))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        })
        HomeLoadingFooterUi.hide(binding.homeCallsContainer)
    }

    private fun isTopLevelCrmPage(): Boolean = isCrmModeEnabled() || isCrmContactsMode()

    private fun updateCrmModeControls() {
        val showBrandShortcut = !isCrmContactsMode()
        HomeScreenActionBinder.updateBrandShortcutVisibility(binding, showBrandShortcut)
        val visible = HomeCrmModeStore.isAvailable(activity) && showBrandShortcut
        binding.crmControlsScroll.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible) return
        val fill = Color.WHITE
        val border = Color.rgb(203, 213, 225)
        binding.crmModeButton.backgroundTintList = ColorStateList.valueOf(fill)
        binding.crmModeButton.strokeColor = ColorStateList.valueOf(border)
        binding.crmModeButton.setTextColor(Color.rgb(51, 65, 85))
    }
}
