package com.onlineimoti.calllog

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.widget.Toast

/** Opens editors and external screens from one History contact. */
internal class ContactNotesEditorActions(
    private val activity: ContactNotesActivity,
    private val phone: () -> String,
    private val title: () -> String,
    private val refreshHistory: (Boolean) -> Unit,
    private val dp: (Int) -> Int,
    private val roundedRect: (Int, Int, Int, Int) -> GradientDrawable,
) {
    fun openGeneralNoteEditor(companyId: String = "") {
        CompanyMainNoteEditorLauncher.start(activity, phone(), title(), companyId)
    }

    fun openUnscopedServerMainNoteEditor(event: CallReportHistoryEvent) {
        val clientEventId = event.clientEventId.trim()
        if (clientEventId.isBlank()) {
            Toast.makeText(activity, "Сървърната бележка няма ID за редакция.", Toast.LENGTH_SHORT).show()
            return
        }
        CallNoteEditorLauncher.startEditor(
            context = activity,
            mode = PostCallOverlayService.MODE_NOTE,
            phone = phone(),
            title = title(),
            direction = event.direction,
            callAt = event.occurredAtMs.takeIf { it > 0L } ?: event.updatedAtMs,
            durationSeconds = event.durationSeconds,
            companyId = event.companyId,
            initialNoteText = event.note,
            serverClientEventId = clientEventId,
        )
    }

    fun openCallNoteEditor(note: ContactCallNote) {
        CallNoteEditorLauncher.startEditor(
            context = activity,
            mode = PostCallOverlayService.MODE_NOTE,
            phone = phone(),
            title = title(),
            direction = note.direction,
            callAt = note.callAt,
            durationSeconds = note.durationSeconds,
            companyId = note.companyId,
            initialNoteText = note.note,
            serverClientEventId = note.serverClientEventId,
        )
    }

    fun openFullLogCallNoteEditor(
        call: PhoneCallRecord,
        displayName: String,
        note: HomeCallNote?,
    ) {
        CallNoteEditorLauncher.startEditor(
            context = activity,
            mode = PostCallOverlayService.MODE_NOTE,
            phone = call.number.ifBlank { phone() },
            title = displayName.ifBlank { title() },
            direction = call.direction,
            callAt = call.startedAt,
            durationSeconds = call.durationSeconds,
            companyId = note?.companyId.orEmpty(),
            initialNoteText = note?.text.orEmpty(),
            serverClientEventId = note?.serverClientEventId.orEmpty(),
        )
    }

    fun openSmsCompanyEditor(sms: SmsMessageRecord, companyId: String) {
        if (!CallReportRemoteAccess.isReady(ConfigStore.load(activity))) {
            Toast.makeText(activity, "За SMS фирма включи и настрой Server", Toast.LENGTH_SHORT).show()
            return
        }
        SmsCompanyAssignmentDialog(activity, dp, roundedRect).show(
            phone = phone(),
            title = title(),
            sms = sms,
            initialCompanyId = companyId,
            onSaved = { refreshHistory(true) },
        )
    }

    fun openRmContactForm() {
        RmContactFormDialog(activity).show(
            phone = phone(),
            fallbackTitle = title(),
            onSaved = { refreshHistory(true) },
        )
    }

    fun openRmCallLog() {
        activity.startActivity(Intent(activity, HomeActivity::class.java))
    }
}
