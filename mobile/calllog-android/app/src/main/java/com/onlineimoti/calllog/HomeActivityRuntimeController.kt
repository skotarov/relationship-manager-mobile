package com.onlineimoti.calllog

import android.Manifest
import android.view.View
import androidx.activity.result.ActivityResultLauncher
import com.onlineimoti.calllog.databinding.ActivityHomeBinding
import java.util.concurrent.ExecutorService

internal class HomeActivityRuntimeController(
    private val activity: HomeActivity,
    private val binding: () -> ActivityHomeBinding,
    private val refreshExecutor: ExecutorService,
    private val smsPermissionLauncher: ActivityResultLauncher<String>,
    private val activePhoneFilter: () -> String,
    private val activeSearchQuery: () -> String,
    private val isCrmContactsMode: () -> Boolean,
    private val isServerReady: () -> Boolean,
    private val isFilteredFullLogMode: () -> Boolean,
    private val clearSearchCache: () -> Unit,
    private val invalidateFilteredLog: () -> Unit,
    private val invalidateCompanyNotes: () -> Unit,
    private val invalidateCrmContacts: () -> Unit,
    private val refreshCompanies: (Boolean) -> Unit,
    private val renderCalls: () -> Unit,
) {
    private var smsPermissionPromptShownThisSession = false
    private var smsPermissionRequestInFlight = false

    fun onSmsPermissionResult() {
        smsPermissionRequestInFlight = false
        clearSearchCache()
        invalidateFilteredLog()
        if (!activity.isFinishing && !activity.isDestroyed) renderCalls()
    }

    /** Reuses the compact back-and-title header for both Contacts and a filtered full log. */
    fun updateHeader() {
        val views = binding()
        val contactsVisible = isCrmContactsMode() && isServerReady()
        val fullLogVisible = !contactsVisible && isFilteredFullLogMode()
        val customHeaderVisible = contactsVisible || fullLogVisible
        views.relationshipManagerWordmark.visibility = if (customHeaderVisible) View.GONE else View.VISIBLE
        views.crmContactsHeader.visibility = if (customHeaderVisible) View.VISIBLE else View.GONE
        views.crmContactsTitleText.text = when {
            fullLogVisible -> activity.getString(R.string.open_full_log)
            contactsVisible -> activity.getString(R.string.runtime_crm_clients)
            else -> ""
        }
    }

    fun requestSmsPermissionForFilteredHistoryIfNeeded() {
        if (
            !DistributionCapabilities.supportsLocalDeviceData ||
            SmsMessageReader.hasReadSmsPermission(activity) ||
            smsPermissionRequestInFlight ||
            smsPermissionPromptShownThisSession
        ) {
            return
        }
        smsPermissionPromptShownThisSession = true
        smsPermissionRequestInFlight = true
        smsPermissionLauncher.launch(Manifest.permission.READ_SMS)
    }

    fun refreshFromPull() {
        val appContext = activity.applicationContext
        clearSearchCache()
        invalidateFilteredLog()
        invalidateCompanyNotes()
        invalidateCrmContacts()
        HomeCrmPhaseLookup.invalidate()
        refreshCompanies(true)
        runCatching {
            refreshExecutor.execute {
                runCatching {
                    CallReportNoteOutboxScheduler.enqueue(
                        appContext,
                        reason = "home_pull_refresh",
                    )
                }
                runCatching { CallReportTopicNoteOutbox.requestSyncNow(appContext) }
                runCatching {
                    CallReportSyncScheduler.enqueueCatchUp(
                        appContext,
                        reason = "home_pull_refresh",
                    )
                }
            }
        }
        renderCalls()
    }
}
