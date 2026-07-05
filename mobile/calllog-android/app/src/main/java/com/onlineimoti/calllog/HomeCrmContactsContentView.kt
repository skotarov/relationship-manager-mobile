package com.onlineimoti.calllog

import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.onlineimoti.calllog.databinding.ActivityHomeBinding

/** Draws the CRM contacts planning list while retaining Home's existing paging controls. */
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

    fun render(data: HomeRenderData, pageSize: Int, refreshCompanyLabels: Boolean = true) {
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
        currentData = null
        contentRenderer.clearCalls()
        binding.homeCallsContainer.removeAllViews()
        binding.fullLogProgress.visibility = View.GONE
        binding.homeStatusText.text = when {
            hasActiveCrmFilters() && AppLocaleText.isBulgarian() -> "Няма CRM контакти за избраните филтри."
            hasActiveCrmFilters() -> "No CRM contacts match the selected filters."
            AppLocaleText.isBulgarian() -> "Няма контакти, отбелязани като CRM."
            else -> "No contacts are marked as CRM."
        }
        timelineToggle.showEmpty(contactsMode = true)
        binding.previousCallsButton.isEnabled = pageIndex() > 0
        binding.nextCallsButton.isEnabled = false
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
        binding.previousCallsButton.isEnabled = pageIndex() > 0
        binding.nextCallsButton.isEnabled = itemCount >= pageSize
        binding.pageText.text = activity.getString(R.string.dynamic_home_page, pageIndex() + 1)
        binding.paginationContainer.visibility = View.VISIBLE
    }
}
