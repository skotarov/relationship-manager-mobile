package com.onlineimoti.calllog
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.EditText
import android.widget.Spinner
import android.widget.Toast
import java.util.concurrent.Executors
class ContactNoteEditActivity : Activity() {
    private var phone = ""
    private var titleText = ""
    private var direction = ""
    private var callAt = 0L
    private var durationSeconds = 0L
    private var actionIssuedAt = 0L
    private var isGeneralNote = false
    private var preferredCompanyId = ""
    private var initialNoteText = ""
    private var serverClientEventId = ""
    private var topicState = ContactNoteTopicState(visible = false)
    private var topicSpinner: Spinner? = null
    private var noteInput: EditText? = null
    /** Value last read from storage or deliberately saved by this editor. */
    private var persistedEditorText = ""
    private val topicExecutor = Executors.newSingleThreadExecutor()
    private val scopeTextController by lazy {
        ContactNoteScopeTextController(
            activity = this,
            executor = topicExecutor,
            draft = ::draft,
            selectedCompanyId = { topicState.selectedCompanyId },
            noteInput = { noteInput },
            isActive = { !isFinishing && !isDestroyed },
            onTextApplied = { _, value -> persistedEditorText = value },
        )
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        AppLanguageManager.applyFromConfig(this)
        super.onCreate(savedInstanceState)
        if (intent.getBooleanExtra(EXTRA_SHOW_NUMBER_KEYPAD, false)) {
            setContentView(
                NumberEntryUi(
                    activity = this,
                    onNumberConfirmed = { number ->
                        setResult(RESULT_OK, Intent().putExtra(EXTRA_NUMBER, number))
                        finish()
                    },
                    close = { finish() },
                ).buildContent(),
            )
            return
        }
        window.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE,
        )
        readDraftFromIntent()
        val initialTopicState = ContactNoteFormWorkflow.initialTopicState(this, draft())
        topicState = when {
            preferredCompanyId.isNotBlank() -> initialTopicState.copy(
                selectedCompanyId = preferredCompanyId,
                localOnly = false,
                loading = initialTopicState.visible,
            )
            !isGeneralNote && initialNoteText.isNotBlank() && initialTopicState.visible && !initialTopicState.localOnly -> {
                initialTopicState.copy(selectedCompanyId = ContactNoteTopicState.LOCAL_COMPANY_ID)
            }
            else -> initialTopicState
        }
        setContentView(
            ContactNoteEditUi(
                activity = this,
                state = ::uiState,
                onTopicSelected = { selectedCompanyId, input ->
                    topicState = topicState.copy(selectedCompanyId = selectedCompanyId)
                    if (isGeneralNote) scopeTextController.refresh(selectedCompanyId, input)
                },
                onNoteInputReady = { input ->
                    noteInput = input
                    persistedEditorText = input.text?.toString().orEmpty()
                    if (isGeneralNote) scopeTextController.refresh(topicState.selectedCompanyId, input)
                },
                onTopicSpinnerReady = { spinner -> topicSpinner = spinner },
                saveAndClose = ::saveAndClose,
                saveAndOpenCalendar = ::saveAndOpenCalendar,
                close = ::saveAndCloseIfChanged,
            ).buildContent(),
        )
        if (topicState.visible) loadTopicCompanies()
    }
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (intent.getBooleanExtra(EXTRA_SHOW_NUMBER_KEYPAD, false)) {
            super.onBackPressed()
            return
        }
        saveAndCloseIfChanged(noteInput?.text?.toString().orEmpty())
    }
    override fun onDestroy() {
        topicExecutor.shutdownNow()
        super.onDestroy()
    }
    private fun readDraftFromIntent() {
        phone = intent.getStringExtra(PostCallOverlayService.EXTRA_PHONE).orEmpty()
        titleText = intent.getStringExtra(PostCallOverlayService.EXTRA_TITLE).orEmpty().ifBlank {
            phone.ifBlank { getString(R.string.dynamic_note_default_title) }
        }
        direction = intent.getStringExtra(PostCallOverlayService.EXTRA_DIRECTION).orEmpty()
        callAt = intent.getLongExtra(PostCallOverlayService.EXTRA_CALL_AT, 0L)
        durationSeconds = intent.getLongExtra(PostCallOverlayService.EXTRA_DURATION, 0L)
        actionIssuedAt = intent.getLongExtra(CallNoteTargetResolver.EXTRA_ACTION_ISSUED_AT, 0L)
        isGeneralNote = intent.getStringExtra(PostCallOverlayService.EXTRA_MODE) == PostCallOverlayService.MODE_GENERAL_NOTE
        preferredCompanyId = intent.getStringExtra(CompanyMainNoteEditorLauncher.EXTRA_COMPANY_ID).orEmpty().trim()
        initialNoteText = if (isGeneralNote) "" else intent.getStringExtra(CallNoteEditorLauncher.EXTRA_INITIAL_NOTE_TEXT).orEmpty()
        serverClientEventId = intent.getStringExtra(CallNoteEditorLauncher.EXTRA_SERVER_CLIENT_EVENT_ID).orEmpty().trim()
    }
    private fun draft() = ContactNoteFormDraft(
        phone = phone,
        title = titleText,
        direction = direction,
        callAt = callAt,
        durationSeconds = durationSeconds,
        actionIssuedAt = actionIssuedAt,
        isGeneralNote = isGeneralNote,
        serverClientEventId = serverClientEventId,
    )
    private fun uiState() = ContactNoteEditUiState(
        phone = phone,
        titleText = titleText,
        direction = direction,
        callAt = callAt,
        durationSeconds = durationSeconds,
        isGeneralNote = isGeneralNote,
        topic = topicState,
        willEnableServerSync = ContactNoteFormWorkflow.willEnableServerSync(this, draft(), topicState),
        initialNoteText = initialNoteText,
    )
    private fun loadTopicCompanies() {
        val initialState = topicState
        topicExecutor.execute {
            val loadedState = ContactNoteFormWorkflow.loadTopics(applicationContext, initialState)
            runOnUiThread {
                if (isFinishing || isDestroyed || !topicState.visible) return@runOnUiThread
                topicState = when {
                    preferredCompanyId == ContactNoteTopicState.LOCAL_COMPANY_ID -> {
                        loadedState.copy(selectedCompanyId = ContactNoteTopicState.LOCAL_COMPANY_ID)
                    }
                    preferredCompanyId.isNotBlank() && loadedState.companies.any { it.id == preferredCompanyId } -> {
                        loadedState.copy(selectedCompanyId = preferredCompanyId)
                    }
                    preferredCompanyId.isNotBlank() && loadedState.loadError.isNotBlank() -> {
                        loadedState.copy(selectedCompanyId = preferredCompanyId)
                    }
                    else -> loadedState
                }
                topicSpinner?.let(::bindTopicSpinner)
                if (isGeneralNote) noteInput?.let { scopeTextController.refresh(topicState.selectedCompanyId, it) }
            }
        }
    }
    private fun bindTopicSpinner(spinner: Spinner) {
        ContactNoteTopicSelector.bind(this, spinner, topicState) { selected ->
            topicState = topicState.copy(selectedCompanyId = selected)
            if (isGeneralNote) noteInput?.let { scopeTextController.refresh(selected, it) }
        }
    }
    private fun saveAndClose(noteText: String) {
        val topicCompanyId = selectedTopicCompanyIdOrNull() ?: return
        val outcome = saveCurrentNote(noteText, topicCompanyId)
        showSaveOutcome(outcome)
        if (outcome.saved) {
            persistedEditorText = noteText
            finish()
        }
    }
    /** Saves only changed content before an X, Cancel or system-Back exit. */
    private fun saveAndCloseIfChanged(noteText: String) {
        if (noteText == persistedEditorText) {
            finish()
            return
        }
        val strictDestination = if (serverClientEventId.isNotBlank()) {
            topicState.selectedCompanyId.ifBlank { preferredCompanyId }.ifBlank { ContactNoteTopicState.LOCAL_COMPANY_ID }
        } else {
            ContactNoteFormWorkflow.selectedTopicOrLocalFallback(topicState)
        }
        val fallbackLocally = strictDestination == null
        val destination = strictDestination
            ?: topicState.selectedCompanyId.ifBlank { preferredCompanyId }
                .ifBlank { ContactNoteTopicState.LOCAL_COMPANY_ID }
        val outcome = saveCurrentNote(
            noteText = noteText,
            topicCompanyId = destination,
            localOnlyFallback = topicState.loadError.isNotBlank() || fallbackLocally,
        )
        if (!outcome.saved) {
            Toast.makeText(this, getString(R.string.dynamic_note_save_failed), Toast.LENGTH_SHORT).show()
            return
        }
        persistedEditorText = noteText
        finish()
    }
    private fun saveAndOpenCalendar(noteText: String) {
        val topicCompanyId = selectedTopicCompanyIdOrNull() ?: return
        val outcome = saveCurrentNote(noteText, topicCompanyId)
        showSaveOutcome(outcome)
        if (outcome.saved) {
            persistedEditorText = noteText
            openCalendarEvent(noteText)
        }
    }
    private fun selectedTopicCompanyIdOrNull(): String? {
        if (serverClientEventId.isNotBlank()) {
            return topicState.selectedCompanyId
                .ifBlank { preferredCompanyId }
                .ifBlank { ContactNoteTopicState.LOCAL_COMPANY_ID }
        }
        return ContactNoteFormWorkflow.selectedTopicOrLocalFallback(topicState) ?: run {
            Toast.makeText(this, getString(R.string.note_company_required), Toast.LENGTH_SHORT).show()
            null
        }
    }
    private fun saveCurrentNote(
        noteText: String,
        topicCompanyId: String,
        localOnlyFallback: Boolean = topicState.loadError.isNotBlank(),
    ): NoteSaveOutcome {
        val result = ContactNoteFormWorkflow.save(
            context = this,
            draft = draft(),
            noteText = noteText,
            topicCompanyId = topicCompanyId,
            localOnlyFallback = localOnlyFallback,
        )
        if (!result.saved) return NoteSaveOutcome(saved = false)
        if (!result.writeResult.savedAsGeneralNote) {
            direction = result.writeResult.target.direction
            callAt = result.writeResult.target.callAt
            durationSeconds = result.writeResult.target.durationSeconds
        }
        sendBroadcast(Intent(PostCallOverlayService.ACTION_NOTES_CHANGED).setPackage(packageName))
        return NoteSaveOutcome(
            saved = true,
            serverSyncActivationAttempted = result.serverSyncActivationAttempted,
            serverSyncEnabled = result.serverSyncEnabled,
            pendingServerSync = result.pendingServerSync,
            pendingCompanyChoice = result.pendingCompanyChoice,
            companyName = companyNameFor(topicCompanyId),
        )
    }
    private fun companyNameFor(companyId: String): String {
        if (companyId == ContactNoteTopicState.LOCAL_COMPANY_ID) return ""
        return topicState.companies.firstOrNull { it.id == companyId }?.name.orEmpty().ifBlank { companyId }
    }
    private fun showSaveOutcome(outcome: NoteSaveOutcome) {
        val message = when {
            !outcome.saved -> getString(R.string.dynamic_note_save_failed)
            outcome.pendingCompanyChoice -> getString(R.string.dynamic_note_saved_choose_company_later)
            outcome.pendingServerSync -> getString(R.string.dynamic_note_saved_pending_company_sync, outcome.companyName)
            outcome.serverSyncActivationAttempted && outcome.serverSyncEnabled -> getString(R.string.note_server_sync_enabled)
            outcome.serverSyncActivationAttempted -> getString(R.string.note_server_sync_activation_failed)
            else -> getString(R.string.dynamic_note_saved)
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
    private fun openCalendarEvent(noteText: String) = ContactNoteCalendarActions.open(
        activity = this,
        titleText = titleText,
        phone = phone,
        isGeneralNote = isGeneralNote,
        direction = direction,
        callAt = callAt,
        durationSeconds = durationSeconds,
        noteText = noteText,
    )
    private data class NoteSaveOutcome(
        val saved: Boolean,
        val serverSyncActivationAttempted: Boolean = false,
        val serverSyncEnabled: Boolean = false,
        val pendingServerSync: Boolean = false,
        val pendingCompanyChoice: Boolean = false,
        val companyName: String = "",
    )
    private companion object {
        const val EXTRA_SHOW_NUMBER_KEYPAD = "show_number_keypad"
        const val EXTRA_NUMBER = "number"
    }
}
