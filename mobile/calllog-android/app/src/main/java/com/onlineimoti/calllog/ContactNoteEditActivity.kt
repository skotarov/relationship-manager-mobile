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

        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)

        phone = intent.getStringExtra(PostCallOverlayService.EXTRA_PHONE).orEmpty()
        titleText = intent.getStringExtra(PostCallOverlayService.EXTRA_TITLE).orEmpty().ifBlank {
            phone.ifBlank { getString(R.string.dynamic_note_default_title) }
        }
        direction = intent.getStringExtra(PostCallOverlayService.EXTRA_DIRECTION).orEmpty()
        callAt = intent.getLongExtra(PostCallOverlayService.EXTRA_CALL_AT, 0L)
        durationSeconds = intent.getLongExtra(PostCallOverlayService.EXTRA_DURATION, 0L)
        actionIssuedAt = intent.getLongExtra(CallNoteTargetResolver.EXTRA_ACTION_ISSUED_AT, 0L)
        isGeneralNote = intent.getStringExtra(PostCallOverlayService.EXTRA_MODE) == PostCallOverlayService.MODE_GENERAL_NOTE
        topicState = initialTopicState()

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

    private fun uiState(): ContactNoteEditUiState {
        return ContactNoteEditUiState(
            phone = phone,
            titleText = titleText,
            direction = direction,
            callAt = callAt,
            durationSeconds = durationSeconds,
            isGeneralNote = isGeneralNote,
            topic = topicState,
        )
    }

    private fun initialTopicState(): ContactNoteTopicState {
        val visible = shouldShowTopicSelector()
        return ContactNoteTopicState(visible = visible, loading = visible)
    }

    private fun shouldShowTopicSelector(): Boolean {
        if (!CallReportRemoteAccess.isReady(ConfigStore.load(this))) return false
        if (CrmContactSyncStore.isEnabled(this, phone)) return true
        // A number without a real Android contact is an unknown lead and may be
        // classified directly from its first note.
        return !ContactNotesExternalActions(this).hasDefaultContact(phone)
    }

    private fun loadTopicCompanies() {
        topicExecutor.execute {
            val companies = runCatching {
                CallReportTopicCompaniesClient.fetch(ConfigStore.load(applicationContext))
            }.getOrDefault(emptyList())

            runOnUiThread {
                if (isFinishing || isDestroyed || !topicState.visible) return@runOnUiThread
                val selectedCompanyId = topicState.selectedCompanyId.takeIf { selected ->
                    companies.any { it.id == selected }
                } ?: companies.singleOrNull()?.id.orEmpty()
                topicState = topicState.copy(
                    loading = false,
                    companies = companies,
                    selectedCompanyId = selectedCompanyId,
                )
                topicSpinner?.let { spinner ->
                    ContactNoteTopicSelector.bind(this, spinner, topicState) { selected ->
                        topicState = topicState.copy(selectedCompanyId = selected)
                    }
                }
            }
        }
    }

    private fun saveAndClose(noteText: String) {
        val topicCompanyId = selectedTopicCompanyIdOrNull() ?: return
        val saved = saveCurrentNote(noteText, topicCompanyId)
        Toast.makeText(
            this,
            getString(if (saved) R.string.dynamic_note_saved else R.string.dynamic_note_save_failed),
            Toast.LENGTH_SHORT,
        ).show()
        if (saved) finish()
    }

    private fun saveAndOpenCalendar(noteText: String) {
        val topicCompanyId = selectedTopicCompanyIdOrNull() ?: return
        val saved = saveCurrentNote(noteText, topicCompanyId)
        if (!saved) {
            Toast.makeText(this, getString(R.string.dynamic_note_save_failed), Toast.LENGTH_SHORT).show()
            return
        }
        openCalendarEvent(noteText)
    }

    private fun selectedTopicCompanyIdOrNull(): String? {
        if (!topicState.visible) return ""
        if (topicState.loading || topicState.selectedCompanyId.isBlank()) {
            Toast.makeText(this, "Избери тема / фирма за бележката.", Toast.LENGTH_SHORT).show()
            return null
        }
        return topicState.selectedCompanyId
    }

    private fun saveCurrentNote(noteText: String, topicCompanyId: String): Boolean {
        val result = when {
            topicCompanyId.isNotBlank() && isGeneralNote -> {
                CallNoteTopicWriter.writeGeneral(this, phone, noteText, topicCompanyId)
            }
            topicCompanyId.isNotBlank() -> {
                CallNoteTopicWriter.writeCallOrGeneral(
                    context = this,
                    phone = phone,
                    text = noteText,
                    direction = direction,
                    callAt = callAt,
                    durationSeconds = durationSeconds,
                    actionIssuedAt = actionIssuedAt,
                    companyId = topicCompanyId,
                )
            }
            isGeneralNote -> CallNoteWriter.writeGeneral(this, phone, noteText)
            else -> CallNoteWriter.writeCallOrGeneral(
                this,
                phone,
                noteText,
                direction,
                callAt,
                durationSeconds,
                actionIssuedAt,
            )
        }
        if (result.saved) {
            if (!result.savedAsGeneralNote) {
                direction = result.target.direction
                callAt = result.target.callAt
                durationSeconds = result.target.durationSeconds
            }
            sendBroadcast(Intent(PostCallOverlayService.ACTION_NOTES_CHANGED).setPackage(packageName))
        }
        return result.saved
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
        runCatching {
            startActivity(intent)
        }.onFailure {
            Toast.makeText(this, getString(R.string.dynamic_calendar_app_not_found), Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        const val EXTRA_SHOW_NUMBER_KEYPAD = "show_number_keypad"
        const val EXTRA_NUMBER = "number"
    }
}
