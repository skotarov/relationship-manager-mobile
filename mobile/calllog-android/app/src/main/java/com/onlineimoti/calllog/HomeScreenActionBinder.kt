package com.onlineimoti.calllog

import android.content.Intent
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.onlineimoti.calllog.databinding.ActivityHomeBinding

internal object HomeScreenActionBinder {
    fun wire(
        activity: AppCompatActivity,
        binding: ActivityHomeBinding,
        openOverflow: () -> Unit,
        openCrmContacts: () -> Unit,
        previousPage: () -> Unit,
        nextPage: () -> Unit,
        isOnLaterPage: () -> Boolean,
        goToFirstPage: () -> Unit,
    ) {
        HomeResumeRefreshController.install(activity, binding)
        binding.settingsButton.setOnClickListener { openOverflow() }
        binding.crmModeButton.setOnClickListener { openCrmContacts() }
        binding.smsHistoryButton.setOnClickListener {
            activity.startActivity(Intent(activity, SmsHistoryActivity::class.java))
        }
        binding.relationshipManagerWordmark.apply {
            contentDescription = activity.getString(R.string.runtime_crm_clients)
            isClickable = true
            isFocusable = true
            setOnClickListener { openCrmContacts() }
        }
        binding.clearFilterButton.visibility = View.GONE
        binding.filteredDialButton.visibility = View.GONE
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

    fun updateBrandShortcutVisibility(binding: ActivityHomeBinding, visible: Boolean) {
        binding.relationshipManagerWordmark.visibility = if (visible) View.VISIBLE else View.GONE
    }
}
