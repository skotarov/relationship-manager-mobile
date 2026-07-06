package com.onlineimoti.calllog

import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.onlineimoti.calllog.databinding.ActivityHomeBinding

/** Draws the customers planning list while retaining Home's existing paging controls. */
internal class HomeCrmContactsContentView(
    private val activity: AppCompatActivity,
    private val binding: ActivityHomeBinding,
    private val pageIndex: () -> Int,
    private val contentRenderer: HomeContentRenderer,
    private val companyGeneralNotes: HomeCompanyGeneralNotesController,
    private val rowRenderer: HomeCrmContactRowRenderer,
    private val timelineToggle: HomeCrmTimelineModeToggle,
    private val hasActiveCrmFilters: () -> Boolean,
) {
    private var currentData: HomeRenderData? = null

    fun invalidate() {
        currentData = null
    }

    fun showLoading() {
        prepareCustomersHeader()
        timelineToggle.prepare(visible = true, contactsMode = true)
        binding.homeStatusText.text = if (AppLocaleText.isBulgarian()) {
            "Зареждане на клиенти…"
        } else {
            "Loading customers…"
        }
        binding.fullLogProgress.visibility = View.VISIBLE
        binding.paginationContainer.visibility = View.GONE
    }

    fun render(data: HomeRenderData, pageSize: Int, refreshCompanyLabels: Boolean = true) {
        prepareCustomersHeader()
        currentData = data
        contentRenderer.replaceCurrentCalls(data.calls)
        binding.homeCallsContainer.removeAllViews()
        binding.fullLogProgress.visibility = View.GONE
        renderPagination(pageSize, data.calls.size)
        val companyLabels = companyGeneralNotes.labelsFor(data.calls)
        data.calls.forEach { contact ->
            val key = HomeCallPageLoader.noteKey(contact.number)
            binding.homeCallsContainer.addView(
                rowRenderer.compactRow(
                    contact = contact,
                    displayName = data.contactNamesByNumber[key].orEmpty().ifBlank { contact.displayName },
                    contactNote = data.contactNotesByNumber[key],
                    companyLabels = companyLabels[key],
                    highlightQuery = "",
                ),
            )
        }
        if (refreshCompanyLabels) companyGeneralNotes.refresh(data.calls)
    }

    fun renderCurrentRowsAfterCompanyLabels(pageSize: Int) {
        val data = currentData ?: return
        render(data, pageSize, refreshCompanyLabels = false)
    }

    fun renderEmpty(pageSize: Int) {
        prepareCustomersHeader()
        currentData = null
        contentRenderer.clearCalls()
        binding.homeCallsContainer.removeAllViews()
        binding.fullLogProgress.visibility = View.GONE
        binding.homeStatusText.text = when {
            hasActiveCrmFilters() && AppLocaleText.isBulgarian() -> "Няма клиенти за избраните филтри."
            hasActiveCrmFilters() -> "No customers match the selected filters."
            AppLocaleText.isBulgarian() -> "Няма клиенти в RM."
            else -> "No customers in RM."
        }
        timelineToggle.showEmpty(contactsMode = true)
        PaginationButtonAppearance.apply(binding.previousCallsButton, pageIndex() > 0)
        PaginationButtonAppearance.apply(binding.nextCallsButton, enabled = false)
        binding.pageText.text = activity.getString(R.string.dynamic_home_page, pageIndex() + 1)
        binding.paginationContainer.visibility = View.VISIBLE
        binding.previousCallsButton.text = activity.getString(R.string.dynamic_home_previous_calls, pageSize)
        binding.nextCallsButton.text = activity.getString(R.string.dynamic_home_next_calls, pageSize)
    }

    private fun renderPagination(pageSize: Int, itemCount: Int) {
        timelineToggle.showRange(
            contactsMode = true,
            pageIndex = pageIndex(),
            pageSize = pageSize,
            itemCount = itemCount,
        )
        binding.previousCallsButton.text = activity.getString(R.string.dynamic_home_previous_calls, pageSize)
        binding.nextCallsButton.text = activity.getString(R.string.dynamic_home_next_calls, pageSize)
        PaginationButtonAppearance.apply(binding.previousCallsButton, pageIndex() > 0)
        PaginationButtonAppearance.apply(binding.nextCallsButton, itemCount >= pageSize)
        binding.pageText.text = activity.getString(R.string.dynamic_home_page, pageIndex() + 1)
        binding.paginationContainer.visibility = View.VISIBLE
    }

    /** This list is independent from the local CRM-mode switch. */
    private fun prepareCustomersHeader() {
        binding.crmControlsScroll.visibility = View.GONE
        binding.crmContactsTitleText.text = "Клиенти"
    }
}
