package com.onlineimoti.calllog

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

class ContactNotesActivity : Activity() {
    private var phone: String = ""
    private var titleText: String = ""
    private var backTargetsUnfilteredHome = false
    private var contactUpdateBusy = false
    private var contactAutoCheckStarted = false
    private var notesChangedReceiverRegistered = false
    private var crmSyncBusy = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val crmSyncExecutor = Executors.newSingleThreadExecutor()
    private val contactNameRefreshRunnable = Runnable {
        if (!isFinishing && !isDestroyed && refreshTitleFromRealContact()) render()
    }
    private val delayedServerRefreshRunnable = Runnable {
        if (!isFinishing && !isDestroyed && CallReportRemoteAccess.isEnabled(this)) {
            historyController.refreshServer(phone)
        }
    }

    private val notesChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != PostCallOverlayService.ACTION_NOTES_CHANGED) return
            historyController.refreshLocal(phone)
            if (CallReportRemoteAccess.isEnabled(this@ContactNotesActivity)) {
                retryPendingNoteSyncIfEnabled()
                historyController.refreshServer(phone)
                mainHandler.removeCallbacks(delayedServerRefreshRunnable)
                mainHandler.postDelayed(delayedServerRefreshRunnable, SERVER_CONFIRMATION_REFRESH_DELAY_MS)
            } else {
                mainHandler.removeCallbacks(delayedServerRefreshRunnable)
            }
            render()
        }
    }

    private val externalActions by lazy { ContactNotesExternalActions(this) }
    private val headerUi by lazy { ContactNotesHeaderUi(this, ::dp) }
    private val phaseUi by lazy { ContactNegotiationPhaseUi(this, ::dp) }
    private val phaseSyncController by lazy {
        ContactNegotiationPhaseSyncController(
            context = applicationContext,
            mainHandler = mainHandler,
            onStateChanged = {
                if (!isFinishing && !isDestroyed) render()
            },
        )
    }
    private val historyController by lazy {
        CallReportMergedHistoryController(
            activity = this,
            headerUi = headerUi,
            dp = ::dp,
            roundedRect = ::roundedRect,
            rerender = ::render,
        )
    }
    private val sectionsUi by lazy {
        ContactNotesSectionsUi(
            activity = this,
            headerUi = headerUi,
            cards = contactNotesCards(),
            dp = ::dp,
            roundedRect = ::roundedRect,
        )
    }
    private val crmController by lazy {
        ContactNotesCrmController(
            activity = this,
            getPhone = { phone },
            getTitle = { titleText },
            setBusy = { contactUpdateBusy = it },
            rerender = ::render,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppLanguageManager.applyFromConfig(this)
        super.onCreate(savedInstanceState)
        phone = intent.getStringExtra(EXTRA_PHONE).orEmpty()
        titleText = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank {
            phone.ifBlank { getString(R.string.dynamic_notes_default_title) }
        }
        backTargetsUnfilteredHome = intent.getBooleanExtra(EXTRA_BACK_TARGETS_UNFILTERED_HOME, false)
        render()
        autoUpdateContactLinkOnce()
        if (CallReportRemoteAccess.isEnabled(this)) {
            retryPendingNoteSyncIfEnabled()
        }
        historyController.loadOnce(phone)
    }

    override fun onStart() {
        super.onStart()
        registerNotesChangedReceiver()
    }

    override fun onResume() {
        super.onResume()
        refreshTitleFromRealContact()
        if (CallReportRemoteAccess.isEnabled(this)) {
            retryPendingNoteSyncIfEnabled()
        }
        historyController.refreshLocal(phone)
        // In local mode this only clears previous server state; it does not start a request.
        historyController.refreshServer(phone)
        phaseSyncController.refresh(phone)
        render()
        scheduleContactNameRefresh()
    }

    override fun onPause() {
        mainHandler.removeCallbacks(contactNameRefreshRunnable)
        mainHandler.removeCallbacks(delayedServerRefreshRunnable)
        super.onPause()
    }

    override fun onStop() {
        unregisterNotesChangedReceiver()
        super.onStop()
    }

    override fun onDestroy() {
        historyController.release()
        phaseSyncController.release()
        crmSyncExecutor.shutdownNow()
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun registerNotesChangedReceiver() {
        if (notesChangedReceiverRegistered) return
        ContextCompat.registerReceiver(
            this,
            notesChangedReceiver,
            IntentFilter(PostCallOverlayService.ACTION_NOTES_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        notesChangedReceiverRegistered = true
    }

    private fun unregisterNotesChangedReceiver() {
        if (!notesChangedReceiverRegistered) return
        runCatching { unregisterReceiver(notesChangedReceiver) }
        notesChangedReceiverRegistered = false
    }

    private fun render() {
        setContentView(buildContent())
    }

    private fun buildContent(): ScrollView {
        val config = ConfigStore.load(this)
        val phaseControlsVisible = RmContactSyncLayerStore.isEnabled(this, phone)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(24))
            setBackgroundColor(ContextCompat.getColor(this@ContactNotesActivity, R.color.calllog_bg))
        }

        root.addView(headerRow())
        root.addView(phaseUi.phaseBar(phone, phaseControlsVisible) {
            phaseSyncController.syncCurrent(phone)
            render()
        })
        if (config.showRmDebugBox) root.addView(rmDebugBlock())
        sectionsUi.addGeneralNote(root, phone) { externalActions.openGeneralNotePopup(phone, titleText) }
        PendingCallNoteStore.reconcilePendingForPhone(this, phone)
        historyController.addSection(
            root = root,
            phone = phone,
            openFilteredLog = { openRmCallLog(filtered = true) },
            onEditCallNote = ::openCallNoteEditor,
        )
        CrmHistoryTextLocalizer.apply(this, root)

        return ScrollView(this).apply {
            setBackgroundColor(ContextCompat.getColor(this@ContactNotesActivity, R.color.calllog_bg))
            addView(root)
        }
    }

    /** Wakes the durable outbox and also adopts older main notes that predate the outbox. */
    private fun retryPendingNoteSyncIfEnabled() {
        if (phone.isBlank() || !CallReportRemoteAccess.isEnabled(this)) return
        val generalNote = ContactNoteReader.generalNoteForPhone(this, phone)
        val isGeneralPending = CallReportNoteOutbox.isGeneralPending(this, phone)
        val hasGeneralServerCopy = ServerRecordIndex.isGeneralNoteConfirmed(this, phone) ||
            CallReportHistoryLookupClient.hasGeneralNoteOnServer(phone)
        if (generalNote.isNotBlank() && !isGeneralPending && !hasGeneralServerCopy) {
            CallReportNoteOutbox.enqueueGeneral(applicationContext, phone, generalNote)
        }
        if (CallReportNoteOutbox.hasPending(applicationContext)) {
            CallReportNoteOutboxScheduler.enqueue(applicationContext, reason = "contact_notes_visible")
        }
    }

    private fun setCrmSyncEnabled(enabled: Boolean) {
        if (crmSyncBusy || phone.isBlank() || !CallReportRemoteAccess.isEnabled(this)) return
        val requestedPhone = phone
        val requestedTitle = titleText
        crmSyncBusy = true
        render()

        crmSyncExecutor.execute {
            val updated = runCatching {
                RmContactSyncLayerStore.setEnabled(applicationContext, requestedPhone, requestedTitle, enabled)
            }.getOrDefault(false)

            mainHandler.post {
                if (isFinishing || isDestroyed) return@post
                crmSyncBusy = false
                Toast.makeText(
                    this,
                    when {
                        updated && enabled -> getString(R.string.dynamic_crm_sync_turned_on)
                        updated -> getString(R.string.dynamic_crm_sync_turned_off)
                        enabled -> getString(R.string.dynamic_crm_sync_create_failed)
                        else -> getString(R.string.dynamic_crm_sync_clear_failed)
                    },
                    Toast.LENGTH_SHORT,
                ).show()
                if (updated && enabled) retryPendingNoteSyncIfEnabled()
                historyController.refreshServer(phone)
                render()
            }
        }
    }

    private fun refreshTitleFromRealContact(): Boolean {
        if (phone.isBlank()) return false
        val contactName = RmRealContactLookup.resolveDisplayName(this, phone).orEmpty()
        if (contactName.isBlank() || contactName == titleText) return false
        titleText = contactName
        return true
    }

    private fun scheduleContactNameRefresh() {
        mainHandler.removeCallbacks(contactNameRefreshRunnable)
        mainHandler.postDelayed(contactNameRefreshRunnable, CONTACT_NAME_REFRESH_DELAY_MS)
    }

    private fun rmDebugBlock(): TextView {
        return TextView(this).apply {
            text = buildDebugText()
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.rgb(71, 85, 105))
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = roundedRect(Color.rgb(248, 250, 252), dp(10), Color.rgb(203, 213, 225), dp(1))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(10) }
        }
    }

    private fun buildDebugText(): String {
        val debugText = RmContactDebugReader.debugText(this, phone, titleText)
        return if (contactUpdateBusy) "${getString(R.string.dynamic_rm_progress_updating)}\n$debugText" else debugText
    }

    private fun headerRow(): LinearLayout {
        val config = ConfigStore.load(this)
        return headerUi.headerRow(
            title = titleText,
            phone = phone,
            contactExists = externalActions.hasDefaultContact(phone),
            showRmCallLogButton = !backTargetsUnfilteredHome,
            showCrmSyncButton = config.remoteEnabled,
            crmSyncEnabled = CrmContactSyncStore.isEnabled(this, phone),
            crmSyncBusy = crmSyncBusy,
            goBack = ::finish,
            openDialer = { externalActions.openDialer(phone) },
            openCalendarEvent = { externalActions.openCalendarEvent(phone, titleText) },
            openDefaultContact = { externalActions.openDefaultContact(phone, titleText) },
            toggleCrmSync = { setCrmSyncEnabled(!CrmContactSyncStore.isEnabled(this, phone)) },
            openRmCallLog = { openRmCallLog(filtered = false) },
            openRmCallLogFiltered = { openRmCallLog(filtered = true) },
        )
    }

    private fun openRmCallLog(filtered: Boolean) {
        startActivity(
            Intent(this, HomeActivity::class.java).apply {
                if (filtered && phone.isNotBlank()) putExtra(HomeActivity.EXTRA_PHONE_FILTER, phone)
            }
        )
    }

    private fun autoUpdateContactLinkOnce() {
        if (contactAutoCheckStarted || phone.isBlank()) return
        contactAutoCheckStarted = true
        crmController.reconcileCurrentPhone()
    }

    private fun openCallNoteEditor(note: ContactCallNote) {
        externalActions.openEditPopup(phone, titleText, note)
    }

    private fun contactNotesCards(): ContactNotesCards {
        return ContactNotesCards(
            activity = this,
            dp = ::dp,
            roundedRect = ::roundedRect,
            directionArrowLabel = headerUi::directionArrowLabel,
        )
    }

    private fun roundedRect(color: Int, radius: Int, strokeColor: Int, strokeWidth: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius.toFloat()
            setColor(color)
            if (strokeWidth > 0) setStroke(strokeWidth, strokeColor)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_PHONE = "phone"
        const val EXTRA_TITLE = "title"
        const val EXTRA_BACK_TARGETS_UNFILTERED_HOME = "back_targets_unfiltered_home"
        private const val CONTACT_NAME_REFRESH_DELAY_MS = 450L
        private const val SERVER_CONFIRMATION_REFRESH_DELAY_MS = 1_500L
    }
}
