package com.onlineimoti.calllog

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.CalendarContract
import android.view.WindowManager
import android.widget.Spinner
import android.widget.Toast
import java.util.concurrent.Executors

class ContactNoteEditActivity : Activity() {
    private var phone: String = ""
    private var titleText: String = ""
    private var direction: String = ""
    private var callAt: Long = 0L
    private var durationSeconds: Long = 0L
    private var actionIssuedAt: Long = 0L
    private var isGeneralNote = false
    private var preferredCompanyId = ""
    private var topicState = ContactNoteTopicState(visible = false)
    private var topicSpinner: Spinner? = null
    private val topicExecutor = Executors.newSingleThreadExecutor()

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
        topicState = ContactNoteFormWorkflow.initialTopicState(this, draft()).copy(
            selectedCompanyId = preferredCompanyId,
        )
        setContentView(
            ContactNoteEditUi(
                activity = this,
                state = ::uiState,
                onTopicSelected = { selectedCompanyId ->
                    topicState = topicState.copy(selectedCompanyId = selectedCompanyId)
                },
                onTopicSpinnerReady = { spinner -> topicSpinner = spinner },
                saveAndClose = ::saveAndClose,
                saveAndOpenCalendar = ::saveAndOpenCalendar,
                close = { finish() },
            ).buildContent(),
        )
        if (topicState.visible) loadTopicCompanies()
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
        preferredCompanyId = if (isGeneralNote) {
            intent.getStringExtra(CompanyMainNoteEditorLauncher.EXTRA_COMPANY_ID).orEmpty().trim()
        } else {
            ""
        }
    }

    private fun draft(): ContactNoteFormDraft = ContactNoteFormDraft(
        phone = phone,
        title = titleText,
        direction = direction,
        callAt = callAt,
        durationSeconds = durationSeconds,
        actionIssuedAt = actionIssuedAt,
        isGeneralNote = isGeneralNote,
    )

    private fun uiState(): ContactNoteEditUiState = ContactNoteEditUiState(
        phone = phone,
        titleText = titleText,
        direction = direction,
        callAt = callAt,
        durationSeconds = durationSeconds,
        isGeneralNote = isGeneralNote,
        topic = topicState,
        willEnableServerSync = ContactNoteFormWorkflow.willEnableServerSync(this, draft(), topicState),
    )

    private fun loadTopicCompanies() {
        val initialState = topicState
        topicExecutor.execute {
            val loadedState = ContactNoteFormWorkflow.loadTopics(applicationContext, initialState)
            runOnUiThread {
                if (isFinishing || isDestroyed || !topicState.visible) return@runOnUiThread
                topicState = loadedState
                topicSpinner?.let(::bindTopicSpinner)
            }
        }
    }

    private fun bindTopicSpinner(spinner: Spinner) {
        ContactNoteTopicSelector.bind(this, spinner, topicState) { selected ->
            topicState = topicState.copy(selectedCompanyId = selected)
        }
    }

    private fun saveAndClose(noteText: String) {
        val topicCompanyId = selectedTopicCompanyIdOrNull() ?: return
        val outcome = saveCurrentNote(noteText, topicCompanyId)
        showSaveOutcome(outcome)
        if (outcome.saved) finish()
    }

    private fun saveAndOpenCalendar(noteText: String) {
        val topicCompanyId = selectedTopicCompanyIdOrNull() ?: return
        val outcome = saveCurrentNote(noteText, topicCompanyId)
        showSaveOutcome(outcome)
        if (outcome.saved) openCalendarEvent(noteText)
    }

    private fun selectedTopicCompanyIdOrNull(): String? {
        return ContactNoteFormWorkflow.selectedTopicOrLocalFallback(topicState) ?: run {
            Toast.makeText(this, getString(R.string.note_company_required), Toast.LENGTH_SHORT).show()
            null
        }
    }

    private fun saveCurrentNote(noteText: String, topicCompanyId: String): NoteSaveOutcome {
        val result = ContactNoteFormWorkflow.save(
            context = this,
            draft = draft(),
            noteText = noteText,
            topicCompanyId = topicCompanyId,
            localOnlyFallback = topicState.loadError.isNotBlank(),
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
        )
    }

    private fun showSaveOutcome(outcome: NoteSaveOutcome) {
        val messageRes = when {
            !outcome.saved -> R.string.dynamic_note_save_failed
            outcome.serverSyncActivationAttempted && outcome.serverSyncEnabled -> R.string.note_server_sync_enabled
            outcome.serverSyncActivationAttempted -> R.string.note_server_sync_activation_failed
            else -> R.string.dynamic_note_saved
        }
        Toast.makeText(this, getString(messageRes), Toast.LENGTH_SHORT).show()
    }

    private fun openCalendarEvent(noteText: String) {
        val safeName = titleText.ifBlank { phone.ifBlank { getString(R.string.dynamic_calendar_default_contact) } }
        val description = buildString {
            appendLine(getString(R.string.dynamic_calendar_name_line, safeName))
            if (phone.isNotBlank()) appendLine(getString(R.string.dynamic_calendar_phone_line, phone))
            if (!isGeneralNote && callAt > 0L) {
                val callInfo = listOf(
                    PhoneCallReader.directionLabel(direction),
                    PhoneCallReader.formatStartedAt(callAt),
                    PhoneCallReader.formatDuration(durationSeconds),
                ).filter { it.isNotBlank() }.joinToString(" • ")
                if (callInfo.isNotBlank()) appendLine(getString(R.string.dynamic_calendar_call_line, callInfo))
            }
            if (noteText.isNotBlank()) {
                appendLine()
                appendLine(getString(R.string.dynamic_calendar_note_heading))
                appendLine(noteText.trim())
            }
        }.trim()
        val begin = System.currentTimeMillis() + 60 * 60 * 1000L
        val end = begin + 60 * 60 * 1000L
        val intent = Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.Events.TITLE, getString(R.string.dynamic_calendar_event_title, safeName))
            putExtra(CalendarContract.Events.DESCRIPTION, description)
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end)
        }
        runCatching { startActivity(intent) }.onFailure {
            Toast.makeText(this, getString(R.string.dynamic_calendar_app_not_found), Toast.LENGTH_SHORT).show()
        }
    }

    private data class NoteSaveOutcome(
        val saved: Boolean,
        val serverSyncActivationAttempted: Boolean = false,
        val serverSyncEnabled: Boolean = false,
    )

    companion object {
        const val EXTRA_SHOW_NUMBER_KEYPAD = "show_number_keypad"
        const val EXTRA_NUMBER = "number"
    }
}
