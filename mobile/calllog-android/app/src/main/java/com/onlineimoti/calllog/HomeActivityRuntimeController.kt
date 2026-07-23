package com.onlineimoti.calllog

import android.view.View
import com.onlineimoti.calllog.databinding.ActivityHomeBinding
import java.util.concurrent.ExecutorService

internal class HomeActivityRuntimeController(
    private val activity: HomeActivity,
    private val binding: () -> ActivityHomeBinding,
    private val refreshExecutor: ExecutorService,
    private val isCrmContactsMode: () -> Boolean,
    private val isServerReady: () -> Boolean,
    private val clearSearchCache: () -> Unit,
    private val invalidateCompanyNotes: () -> Unit,
    private val invalidateCrmContacts: () -> Unit,
    private val refreshCompanies: (Boolean) -> Unit,
    private val resetTimelineForRefresh: () -> Unit,
    private val scheduleSettledCallLogRefresh: () -> Unit,
    private val renderCalls: () -> Unit,
) {
    /** The compact header is reserved for CRM Contacts; Full Log now lives only in History. */
    fun updateHeader() {
        val views = binding()
        val contactsVisible = isCrmContactsMode() && isServerReady()
        views.relationshipManagerWordmark.visibility = if (contactsVisible) View.GONE else View.VISIBLE
        views.crmContactsHeader.visibility = if (contactsVisible) View.VISIBLE else View.GONE
        views.crmContactsTitleText.text = if (contactsVisible) {
            activity.getString(R.string.runtime_crm_clients)
        } else {
            ""
        }
    }

    fun refreshFromPull() {
        val appContext = activity.applicationContext
        resetTimelineForRefresh()
        clearSearchCache()
        HomeTimelineLoader.invalidateCache()
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
        // Android may publish a just-ended call after the user's first pull query.
        scheduleSettledCallLogRefresh()
    }
}
