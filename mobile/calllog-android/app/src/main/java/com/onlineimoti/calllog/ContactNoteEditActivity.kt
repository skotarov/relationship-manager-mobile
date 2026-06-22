package com.onlineimoti.calllog

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.CalendarContract
import android.view.WindowManager
import android.widget.Toast

class ContactNoteEditActivity : Activity() {
    private var phone: String = ""
    private var titleText: String = ""
    private var direction: String = ""
    private var callAt: Long = 0L
    private var durationSeconds: Long = 0L
    private var actionIssuedAt: Long = 0L
    private var isGeneralNote = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

        setContentView(
            ContactNoteEditUi(
                activity = this,
                state = ::uiState,
                saveAndClose = ::saveAndClose,
                saveAndOpenCalendar = ::saveAndOpenCalendar,
                close = { finish() },
            ).buildContent()
        )
    }

    private fun uiState(): ContactNoteEditUiState {
        return ContactNoteEditUiState(
            phone = phone,
            titleText = titleText,
            direction = direction,
            callAt = callAt,
            durationSeconds = durationSeconds,
            isGeneralNote = isGeneralNote,
        )
    }

    private fun saveAndClose(noteText: String) {
        val saved = saveCurrentNote(noteText)
        Toast.makeText(
            this,
            getString(if (saved) R.string.dynamic_note_saved else R.string.dynamic_note_save_failed),
            Toast.LENGTH_SHORT,
        ).show()
        if (saved) finish()
    }

    private fun saveAndOpenCalendar(noteText: String) {
        val saved = saveCurrentNote(noteText)
        if (!saved) {
            Toast.makeText(this, getString(R.string.dynamic_note_save_failed), Toast.LENGTH_SHORT).show()
            return
        }
        openCalendarEvent(noteText)
    }

    private fun saveCurrentNote(noteText: String): Boolean {
        val result = if (isGeneralNote) {
            CallNoteWriter.writeGeneral(this, phone, noteText)
        } else {
            CallNoteWriter.writeCallOrGeneral(this, phone, noteText, direction, callAt, durationSeconds, actionIssuedAt)
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
}
