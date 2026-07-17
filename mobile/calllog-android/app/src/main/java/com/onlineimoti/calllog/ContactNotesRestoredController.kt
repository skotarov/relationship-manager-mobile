package com.onlineimoti.calllog

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

/**
 * Presentation controller for [ContactNotesActivity]. Server data is read from the
 * already loaded history response, so it remains visible when local note storage
 * switches between shared and private folders.
 */
internal class ContactNotesRestoredController(
    private val activity: ContactNotesActivity,
) {
    private var phone: String = ""
    private var titleText: String = ""
    private var crmSyncBusy = false
    private var pullRefreshRequested = false
    private var confirmedNoteVersion = Long.MIN_VALUE
    private var confirmedLocalServerNote = false
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
        phone = intent?.getStringExtra(ContactNotesActivity.EXTRA_PHONE).orEmpty()
        titleText = intent?.getStringExtra(ContactNotesActivity.EXTRA_TITLE).orEmpty().ifBlank {
            phone.ifBlank { activity.getString(R.string.dynamic_notes_default_title) }
        }
        confirmedNoteVersion = Long.MIN_VALUE
        confirmedLocalServerNote = false
        render()
        historyController.loadOnce(phone)
    }

    fun onResume() {
        historyController.refreshLocal(phone)
        historyController.refreshServer(phone)
        handler.removeCallbacks(delayedServerRefresh)
        handler.postDelayed(delayedServerRefresh, SERVER_CONFIRMATION_REFRESH_DELAY_MS)
        render()
    }

    fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        crmSyncExecutor.shutdownNow()
        historyController.release()
    }

    private fun refreshFromPull() {
        if (phone.isBlank()) {
            pullRefreshRequested = false
            render()
            return
        }
        pullRefreshRequested = true
        historyController.refreshLocal(phone)
        historyController.refreshServer(phone)
        render()
    }

    private fun render() {
        val showPullRefresh = pullRefreshRequested && historyController.isLoading()
        if (pullRefreshRequested && !showPullRefresh) pullRefreshRequested = false
        val config = ConfigStore.load(activity)
        val crmSyncEnabled = CrmContactSyncStore.isEnabled(activity, phone)
        val crmSyncServerBacked = !crmSyncEnabled && (
            historyController.hasServerRecordsFor(phone) || hasConfirmedLocalServerNote()
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
            contactExists = externalActions.hasDefaultContact(phone),
            showRmCallLogButton = true,
            showCrmSyncButton = config.remoteEnabled,
            crmSyncEnabled = crmSyncEnabled,
            crmSyncBusy = crmSyncBusy,
            crmSyncServerBacked = crmSyncServerBacked,
            goBack = { activity.finish() },
            openDialer = { externalActions.openDialer(phone) },
            openCalendarEvent = { externalActions.openCalendarEvent(phone, titleText) },
            openDefaultContact = { externalActions.openDefaultContact(phone, titleText) },
            openRmContact = { openRmContactForm() },
            toggleCrmSync = {
                setCrmSyncEnabled(!CrmContactSyncStore.isEnabled(activity, phone))
            },
            openRmCallLog = { openRmCallLog(false) },
            openRmCallLogFiltered = { openRmCallLog(true) },
        ))
        root.addView(
            ContactNotesServerStatusUi.create(
                activity = activity,
                dp = ::dp,
                textValue = historyController.serverLoadingStatusText(),
            ),
        )
        generalNoteSectionUi.add(
            root = root,
            phone = phone,
            companyNotes = historyController.companyMainNotes(phone),
            unscopedServerMainNote = historyController.unscopedServerMainNote(phone),
            showCompanyNotes = historyController.hasCompanyMainNoteScope(),
            onEditCompany = ::openGeneralNoteEditor,
            onEditUnscopedServerMainNote = ::openUnscopedServerMainNoteEditor,
            phaseBarForCompany = if (phaseControlsVisible) {
                { companyId ->
                    phaseUi.phaseBar(phone, companyId, true) {
                        render()
                    }
                }
            } else {
                null
            },
        )
        PendingCallNoteStore.reconcilePendingForPhone(activity, phone)
        historyController.addSection(
            root = root,
            phone = phone,
            openFilteredLog = { openRmCallLog(true) },
            onEditCallNote = ::openCallNoteEditor,
            onEditSms = ::openSmsCompanyEditor,
        )
        CrmHistoryTextLocalizer.apply(activity, root)
        val scrollView = ScrollView(activity).apply {
            setBackgroundColor(ContextCompat.getColor(activity, R.color.calllog_bg))
            addView(root)
        }
        activity.setContentView(PullToRefreshLayout(activity).apply {
            addView(scrollView)
            setOnRefreshListener(::refreshFromPull)
            if (showPullRefresh) setRefreshing(true)
        })
    }

    private fun hasConfirmedLocalServerNote(): Boolean {
        val currentVersion = ServerRecordIndex.confirmationVersion(activity)
        if (currentVersion == confirmedNoteVersion) return confirmedLocalServerNote
        confirmedNoteVersion = currentVersion
        confirmedLocalServerNote = ServerRecordIndex.hasConfirmedNoteForPhone(
            context = activity,
            phone = phone,
            callNotes = ContactNoteReader.callNotesForPhone(activity, phone),
        )
        return confirmedLocalServerNote
    }

    private fun setCrmSyncEnabled(enabled: Boolean) {
        if (crmSyncBusy || phone.isBlank() || !ConfigStore.load(activity).remoteEnabled) return
        val requestedPhone = phone
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
                if (activity.isFinishing || activity.isDestroyed) return@post
                crmSyncBusy = false
                val message = when {
                    updated && enabled -> activity.getString(R.string.dynamic_crm_sync_turned_on)
                    updated -> activity.getString(R.string.dynamic_crm_sync_turned_off)
                    enabled -> activity.getString(R.string.dynamic_crm_sync_create_failed)
                    else -> activity.getString(R.string.dynamic_crm_sync_clear_failed)
                }
                Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
                historyController.refreshServer(phone)
                render()
            }
        }
    }

    private fun openGeneralNoteEditor(companyId: String = "") {
        CompanyMainNoteEditorLauncher.start(
            context = activity,
            phone = phone,
            title = titleText,
            companyId = companyId,
        )
    }

    private fun openUnscopedServerMainNoteEditor(event: CallReportHistoryEvent) {
        val clientEventId = event.clientEventId.trim()
        if (clientEventId.isBlank()) {
            Toast.makeText(activity, "Сървърната бележка няма ID за редакция.", Toast.LENGTH_SHORT).show()
            return
        }
        activity.startActivity(Intent(activity, ServerNoteEditActivity::class.java).apply {
            putExtra(ServerNoteEditActivity.EXTRA_PHONE, phone)
            putExtra(ServerNoteEditActivity.EXTRA_TITLE, titleText)
            putExtra(ServerNoteEditActivity.EXTRA_DIRECTION, "")
            putExtra(ServerNoteEditActivity.EXTRA_CALL_AT, event.occurredAtMs)
            putExtra(ServerNoteEditActivity.EXTRA_DURATION, 0L)
            putExtra(ServerNoteEditActivity.EXTRA_SERVER_CLIENT_EVENT_ID, clientEventId)
            putExtra(ServerNoteEditActivity.EXTRA_INITIAL_NOTE_TEXT, event.note)
        })
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
        val config = ConfigStore.load(activity)
        if (!CallReportRemoteAccess.isReady(config)) {
            Toast.makeText(activity, "За SMS фирма включи и настрой Server", Toast.LENGTH_SHORT).show()
            return
        }
        SmsCompanyAssignmentDialog(activity, ::dp, ::roundedRect).show(
            phone = phone,
            title = titleText,
            sms = sms,
            initialCompanyId = companyId,
            onSaved = {
                historyController.refreshLocal(phone)
                historyController.refreshServer(phone)
                render()
            },
        )
    }

    private fun openRmContactForm() {
        RmContactFormDialog(activity).show(
            phone = phone,
            fallbackTitle = titleText,
            onSaved = {
                historyController.refreshLocal(phone)
                historyController.refreshServer(phone)
                render()
            },
        )
    }

    private fun openRmCallLog(filtered: Boolean) {
        activity.startActivity(Intent(activity, HomeActivity::class.java).apply {
            if (filtered && phone.isNotBlank()) putExtra(HomeActivity.EXTRA_PHONE_FILTER, phone)
        })
    }

    private fun roundedRect(color: Int, radius: Int, strokeColor: Int, strokeWidth: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius.toFloat()
            setColor(color)
            if (strokeWidth > 0) setStroke(strokeWidth, strokeColor)
        }
    }

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()

    private companion object {
        const val SERVER_CONFIRMATION_REFRESH_DELAY_MS = 1_500L
    }
}
