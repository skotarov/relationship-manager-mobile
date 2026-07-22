package com.onlineimoti.calllog

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

/** Presentation controller for [ContactNotesActivity]. */
internal class ContactNotesRestoredController(
    private val activity: ContactNotesActivity,
) {
    private var phone = ""
    private var titleText = ""
    private var crmSyncBusy = false
    private var pullRefreshRequested = false
    private var skipNextResumeRefresh = true
    private val handler = Handler(Looper.getMainLooper())
    private val crmSyncExecutor = Executors.newSingleThreadExecutor()
    private val delayedServerRefresh = Runnable {
        if (!activity.isFinishing && !activity.isDestroyed) historyController.refreshServer(phone)
    }
    private val externalActions by lazy { ContactNotesExternalActions(activity) }
    private val headerUi by lazy { ContactNotesHeaderUi(activity, ::dp) }
    private val phaseUi by lazy { ContactNegotiationPhaseUi(activity, ::dp) }
    private val historyController by lazy {
        CallReportMergedHistoryController(
            activity = activity,
            headerUi = headerUi,
            dp = ::dp,
            roundedRect = ::roundedRect,
            rerender = ::render,
        )
    }
    private val edgePaging by lazy { HistoryEdgePagingController(historyController) }
    private val stickyHistoryUi by lazy { ContactNotesStickyHistoryUi(activity) }
    private val generalNoteSectionUi by lazy {
        CompanyScopedGeneralNoteSectionUi(
            activity = activity,
            headerUi = headerUi,
            cards = ContactNotesCards(activity, ::dp, ::roundedRect, headerUi::directionArrowLabel),
            dp = ::dp,
            roundedRect = ::roundedRect,
        )
    }

    fun onCreate(intent: Intent?) {
        edgePaging.reset()
        stickyHistoryUi.resetScrollPosition()
        skipNextResumeRefresh = true
        phone = intent?.getStringExtra(ContactNotesActivity.EXTRA_PHONE).orEmpty()
        titleText = intent?.getStringExtra(ContactNotesActivity.EXTRA_TITLE).orEmpty().ifBlank {
            phone.ifBlank { activity.getString(R.string.dynamic_notes_default_title) }
        }
        historyController.loadOnce(phone)
        render()
    }

    fun onResume() {
        if (skipNextResumeRefresh) {
            skipNextResumeRefresh = false
            return
        }
        refreshHistoryInBackground(scheduleConfirmationRefresh = true)
    }

    fun onDataChanged() {
        refreshHistoryInBackground(scheduleConfirmationRefresh = true)
    }

    fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        stickyHistoryUi.release(); edgePaging.release()
        crmSyncExecutor.shutdownNow()
        historyController.release()
    }

    private fun refreshHistoryInBackground(scheduleConfirmationRefresh: Boolean) {
        if (phone.isBlank()) return
        historyController.refreshLocal(phone)
        historyController.refreshServer(phone)
        if (scheduleConfirmationRefresh) {
            handler.removeCallbacks(delayedServerRefresh)
            handler.postDelayed(delayedServerRefresh, SERVER_CONFIRMATION_REFRESH_DELAY_MS)
        }
    }

    private fun refreshFromPull() {
        if (phone.isBlank()) {
            pullRefreshRequested = false
            render()
            return
        }
        pullRefreshRequested = true
        refreshHistoryInBackground(scheduleConfirmationRefresh = false)
        render()
    }

    private fun render() {
        val showPullRefresh = pullRefreshRequested && historyController.isLoading()
        if (pullRefreshRequested && !showPullRefresh) pullRefreshRequested = false
        val config = ConfigStore.load(activity)
        val crmSyncEnabled = CrmContactSyncStore.isEnabled(activity, phone)
        val crmSyncServerBacked = !crmSyncEnabled && (
            historyController.hasServerRecordsFor(phone) || historyController.hasConfirmedLocalServerNote()
        )
        val phaseControlsVisible = config.remoteEnabled && RmContactSyncLayerStore.isEnabled(activity, phone)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(24))
            setBackgroundColor(ContextCompat.getColor(activity, R.color.calllog_bg))
        }
        root.addView(headerUi.headerRow(
            title = titleText,
            phone = phone,
            contactExists = historyController.contactExists(),
            showRmCallLogButton = true,
            showCrmSyncButton = config.remoteEnabled,
            crmSyncEnabled = crmSyncEnabled,
            crmSyncBusy = crmSyncBusy,
            crmSyncServerBacked = crmSyncServerBacked,
            goBack = { activity.finish() },
            openDialer = { externalActions.openDialer(phone) },
            openCalendarEvent = { externalActions.openCalendarEvent(phone, titleText) },
            openDefaultContact = { externalActions.openDefaultContact(phone, titleText) },
            openRmContact = ::openRmContactForm,
            toggleCrmSync = { setCrmSyncEnabled(!CrmContactSyncStore.isEnabled(activity, phone)) },
            openRmCallLog = { openRmCallLog(false) },
            openRmCallLogFiltered = { openRmCallLog(true) },
        ))
        root.addView(ContactNotesServerStatusUi.create(
            activity = activity,
            dp = ::dp,
            textValue = historyController.serverLoadingStatusText(),
        ))
        generalNoteSectionUi.add(
            root = root,
            localNote = historyController.localGeneralNote(),
            localNotePending = historyController.localGeneralNotePending(),
            companyScopeAvailable = historyController.companyScopeAvailable(),
            companyNotes = historyController.companyMainNotes(phone),
            unscopedServerMainNote = historyController.unscopedServerMainNote(phone),
            showCompanyNotes = historyController.hasCompanyMainNoteScope(),
            onEditCompany = ::openGeneralNoteEditor,
            onEditUnscopedServerMainNote = ::openUnscopedServerMainNoteEditor,
            phaseBarForCompany = if (phaseControlsVisible) {
                { companyId -> phaseUi.phaseBar(phone, companyId, true, ::render) }
            } else null,
        )
        historyController.addSection(
            root = root,
            phone = phone,
            openFilteredLog = { openRmCallLog(true) },
            onEditCallNote = ::openCallNoteEditor,
            onEditSms = ::openSmsCompanyEditor,
        )
        CrmHistoryTextLocalizer.apply(activity, root)
        stickyHistoryUi.show(root, showPullRefresh, ::refreshFromPull, edgePaging::bind)
        historyController.markRendered()
    }

    private fun setCrmSyncEnabled(enabled: Boolean) {
        if (crmSyncBusy || phone.isBlank() || !ConfigStore.load(activity).remoteEnabled) return
        val requestedPhone = phone
        val busyToken = HomeBusyTooltipUi.begin(activity, HomeBusyWork.COMPANY_DATA)
        crmSyncBusy = true
        render()
        crmSyncExecutor.execute {
            val updated = runCatching {
                RmContactSyncLayerStore.setCloudSyncWithoutRmLayer(
                    context = activity.applicationContext,
                    phone = requestedPhone,
                    enabled = enabled,
                )
            }.getOrDefault(false)
            handler.post {
                HomeBusyTooltipUi.end(activity, busyToken)
                if (activity.isFinishing || activity.isDestroyed) return@post
                if (requestedPhone != phone) {
                    crmSyncBusy = false
                    return@post
                }
                crmSyncBusy = false
                val message = when {
                    updated && enabled -> activity.getString(R.string.dynamic_crm_sync_turned_on)
                    updated -> activity.getString(R.string.dynamic_crm_sync_turned_off)
                    enabled -> activity.getString(R.string.dynamic_crm_sync_create_failed)
                    else -> activity.getString(R.string.dynamic_crm_sync_clear_failed)
                }
                Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
                historyController.forceNextRenderAfterDataReady()
                refreshHistoryInBackground(scheduleConfirmationRefresh = false)
            }
        }
    }

    private fun openGeneralNoteEditor(companyId: String = "") {
        CompanyMainNoteEditorLauncher.start(activity, phone, titleText, companyId)
    }

    private fun openUnscopedServerMainNoteEditor(event: CallReportHistoryEvent) {
        val clientEventId = event.clientEventId.trim()
        if (clientEventId.isBlank()) {
            Toast.makeText(activity, "Сървърната бележка няма ID за редакция.", Toast.LENGTH_SHORT).show()
            return
        }
        CallNoteEditorLauncher.startEditor(
            context = activity,
            mode = PostCallOverlayService.MODE_NOTE,
            phone = phone,
            title = titleText,
            direction = event.direction,
            callAt = event.occurredAtMs.takeIf { it > 0L } ?: event.updatedAtMs,
            durationSeconds = event.durationSeconds,
            companyId = event.companyId,
            initialNoteText = event.note,
            serverClientEventId = clientEventId,
        )
    }

    private fun openCallNoteEditor(note: ContactCallNote) {
        CallNoteEditorLauncher.startEditor(
            context = activity,
            mode = PostCallOverlayService.MODE_NOTE,
            phone = phone,
            title = titleText,
            direction = note.direction,
            callAt = note.callAt,
            durationSeconds = note.durationSeconds,
            companyId = note.companyId,
            initialNoteText = note.note,
            serverClientEventId = note.serverClientEventId,
        )
    }

    private fun openSmsCompanyEditor(sms: SmsMessageRecord, companyId: String) {
        if (!CallReportRemoteAccess.isReady(ConfigStore.load(activity))) {
            Toast.makeText(activity, "За SMS фирма включи и настрой Server", Toast.LENGTH_SHORT).show()
            return
        }
        SmsCompanyAssignmentDialog(activity, ::dp, ::roundedRect).show(
            phone = phone,
            title = titleText,
            sms = sms,
            initialCompanyId = companyId,
            onSaved = {
                refreshHistoryInBackground(scheduleConfirmationRefresh = true)
            },
        )
    }

    private fun openRmContactForm() {
        RmContactFormDialog(activity).show(
            phone = phone,
            fallbackTitle = titleText,
            onSaved = {
                refreshHistoryInBackground(scheduleConfirmationRefresh = true)
            },
        )
    }

    private fun openRmCallLog(filtered: Boolean) {
        activity.startActivity(Intent(activity, HomeActivity::class.java).apply {
            if (filtered && phone.isNotBlank()) putExtra(HomeActivity.EXTRA_PHONE_FILTER, phone)
        })
    }

    private fun roundedRect(color: Int, radius: Int, strokeColor: Int, strokeWidth: Int) =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius.toFloat()
            setColor(color)
            if (strokeWidth > 0) setStroke(strokeWidth, strokeColor)
        }
    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()

    private companion object {
        const val SERVER_CONFIRMATION_REFRESH_DELAY_MS = 1_500L
    }
}
