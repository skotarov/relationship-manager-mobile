package com.onlineimoti.calllog

import android.graphics.Color
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.onlineimoti.calllog.databinding.ActivityHomeBinding

internal class HomeStatusRenderer(
    private val activity: AppCompatActivity,
    private val binding: ActivityHomeBinding,
    private val dp: (Int) -> Int,
) {
    fun renderEmptyState(
        searchQuery: String,
        phoneFilter: String,
        pageIndex: Int,
    ) {
        binding.fullLogProgress.visibility = View.GONE
        binding.homeStatusText.text = when {
            searchQuery.isNotBlank() -> activity.getString(R.string.dynamic_home_no_search_results, searchQuery.trim())
            phoneFilter.isNotBlank() && pageIndex == 0 -> activity.getString(
                R.string.dynamic_home_filter_no_calls_or_sms,
                phoneFilter,
            )
            pageIndex == 0 -> activity.getString(R.string.dynamic_home_no_calls)
            else -> activity.getString(R.string.dynamic_home_no_more_calls)
        }
        updatePhoneFilterStyle(phoneFilter)
        binding.previousCallsButton.isEnabled = pageIndex > 0
        binding.nextCallsButton.isEnabled = false
        binding.pageText.text = activity.getString(R.string.dynamic_home_page, pageIndex + 1)
        binding.paginationContainer.visibility = View.VISIBLE
    }

    fun renderStatusAndPagination(
        pageSize: Int,
        callCount: Int,
        searchQuery: String,
        phoneFilter: String,
        pageIndex: Int,
    ) {
        val startNumber = pageIndex * pageSize + 1
        val endNumber = pageIndex * pageSize + callCount
        binding.homeStatusText.text = when {
            searchQuery.isNotBlank() && phoneFilter.isNotBlank() -> activity.getString(
                R.string.dynamic_home_status_filter_search,
                phoneFilter,
                searchQuery.trim(),
                startNumber,
                endNumber,
            )
            searchQuery.isNotBlank() -> activity.getString(
                R.string.dynamic_home_status_search,
                searchQuery.trim(),
                startNumber,
                endNumber,
            )
            phoneFilter.isNotBlank() -> activity.getString(
                R.string.dynamic_home_status_filter,
                phoneFilter,
                startNumber,
                endNumber,
            )
            else -> activity.getString(R.string.dynamic_home_status_calls, startNumber, endNumber)
        }
        updatePhoneFilterStyle(phoneFilter)
        binding.previousCallsButton.isEnabled = pageIndex > 0
        binding.nextCallsButton.isEnabled = callCount >= pageSize
        binding.pageText.text = activity.getString(R.string.dynamic_home_page, pageIndex + 1)
        binding.paginationContainer.visibility = View.VISIBLE
    }

    fun updatePhoneFilterStyle(phoneFilter: String) {
        val isPhoneFiltered = phoneFilter.isNotBlank()
        binding.filteredDialButton.visibility = if (isPhoneFiltered) View.VISIBLE else View.GONE
        if (isPhoneFiltered) {
            binding.filteredStatusContainer.background = homeRoundedRect(
                color = Color.rgb(255, 237, 213),
                radius = dp(12),
                strokeColor = Color.rgb(251, 146, 60),
                strokeWidth = dp(1),
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
}
