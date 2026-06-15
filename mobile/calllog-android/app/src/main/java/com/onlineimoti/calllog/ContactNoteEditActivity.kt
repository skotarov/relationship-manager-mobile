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
        titleText = intent.getStringExtra(PostCallOverlayService.EXTRA_TITLE).orEmpty().ifBlank { phone.ifBlank { "Бележка" } }
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
        Toast.makeText(this, if (saved) "Бележката е записана" else "Не успях да запиша бележката", Toast.LENGTH_SHORT).show()
        if (saved) finish()
    }

    private fun saveAndOpenCalendar(noteText: String) {
        val saved = saveCurrentNote(noteText)
        if (!saved) {
            Toast.makeText(this, "Не успях да запиша бележката", Toast.LENGTH_SHORT).show()
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
        val safeName = titleText.ifBlank { phone.ifBlank { "контакт" } }
        val eventTitle = "Среща с $safeName"
        val description = buildString {
            appendLine("Име: $safeName")
            if (phone.isNotBlank()) appendLine("Телефон: $phone")
            if (!isGeneralNote && callAt > 0L) {
                val callInfo = listOf(
                    PhoneCallReader.directionLabel(direction),
                    PhoneCallReader.formatStartedAt(callAt),
                    PhoneCallReader.formatDuration(durationSeconds),
                ).filter { it.isNotBlank() }.joinToString(" • ")
                if (callInfo.isNotBlank()) appendLine("Разговор: $callInfo")
            }
            if (noteText.isNotBlank()) {
                appendLine()
                appendLine("Бележка:")
                appendLine(noteText.trim())
            }
        }.trim()
        val begin = System.currentTimeMillis() + 60 * 60 * 1000L
        val end = begin + 60 * 60 * 1000L
        val intent = Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.Events.TITLE, eventTitle)
            putExtra(CalendarContract.Events.DESCRIPTION, description)
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end)
        }
        runCatching {
            startActivity(intent)
        }.onFailure {
            Toast.makeText(this, "Няма намерено приложение Календар", Toast.LENGTH_SHORT).show()
        }
    }
}
