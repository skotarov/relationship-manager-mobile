package com.onlineimoti.calllog

import androidx.appcompat.app.AppCompatActivity
import com.onlineimoti.calllog.databinding.ActivityHomeBinding

internal object HomeScreenActionBinder {
    fun wire(
        activity: AppCompatActivity,
        binding: ActivityHomeBinding,
        openOverflow: () -> Unit,
        isCrmModeEnabled: () -> Boolean,
        setCrmMode: (Boolean) -> Unit,
        clearPhoneFilter: () -> Unit,
        dialFilteredPhone: () -> Unit,
        previousPage: () -> Unit,
        nextPage: () -> Unit,
    ) {
        binding.settingsButton.setOnClickListener { openOverflow() }
        binding.crmModeButton.setOnClickListener { setCrmMode(!isCrmModeEnabled()) }
        binding.clearFilterButton.setOnClickListener { clearPhoneFilter() }
        binding.filteredDialButton.setOnClickListener { dialFilteredPhone() }
        binding.previousCallsButton.setOnClickListener { previousPage() }
        binding.nextCallsButton.setOnClickListener { nextPage() }
    }
}
