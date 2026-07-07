package com.onlineimoti.calllog

import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.onlineimoti.calllog.databinding.ActivityHomeBinding

internal object HomeScreenActionBinder {
    fun wire(
        activity: AppCompatActivity,
        binding: ActivityHomeBinding,
        openOverflow: () -> Unit,
        openCrmContacts: () -> Unit,
        clearPhoneFilter: () -> Unit,
        dialFilteredPhone: () -> Unit,
        previousPage: () -> Unit,
        nextPage: () -> Unit,
        isOnLaterPage: () -> Boolean,
        goToFirstPage: () -> Unit,
    ) {
        binding.settingsButton.setOnClickListener { openOverflow() }
        binding.crmModeButton.setOnClickListener { openCrmContacts() }
        binding.clearFilterButton.setOnClickListener { clearPhoneFilter() }
        binding.filteredDialButton.setOnClickListener { dialFilteredPhone() }
        binding.previousCallsButton.setOnClickListener { previousPage() }
        binding.nextCallsButton.setOnClickListener { nextPage() }
        binding.pageText.setOnClickListener {
            if (!isOnLaterPage()) return@setOnClickListener
            AlertDialog.Builder(activity)
                .setTitle("Връщане към началото")
                .setMessage("Да отида ли на страница 1?")
                .setNegativeButton("Отказ", null)
                .setPositiveButton("Да") { _, _ -> goToFirstPage() }
                .show()
        }
    }
}
