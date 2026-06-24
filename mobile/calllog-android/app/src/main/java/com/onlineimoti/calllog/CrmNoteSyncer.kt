package com.onlineimoti.calllog

import android.content.Context

/**
 * Compatibility facade retained for existing note editors.
 * Notes now enter the durable Call Report outbox instead of making a one-shot CRM request.
 */
internal object CrmNoteSyncer {
    fun syncGeneralIfEnabled(context: Context, phone: String, note: String) {
        CallReportNoteOutbox.enqueueGeneral(context, phone, note)
    }

    fun syncCallIfEnabled(
        context: Context,
        phone: String,
        note: String,
        direction: String,
        callAt: Long,
        durationSeconds: Long,
        clientNoteId: String = "",
    ) {
        CallReportNoteOutbox.enqueueCall(
            context = context,
            phone = phone,
            note = note,
            direction = direction,
            callAt = callAt,
            durationSeconds = durationSeconds,
            clientNoteId = clientNoteId,
        )
    }
}
