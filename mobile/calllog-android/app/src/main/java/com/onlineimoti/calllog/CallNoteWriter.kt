package com.onlineimoti.calllog

import android.content.Context

internal data class CallNoteWriteResult(
    val saved: Boolean,
    val savedAsGeneralNote: Boolean,
    val target: CallNoteTarget,
)

internal object CallNoteWriter {
    fun writeGeneral(context: Context, phone: String, text: String): CallNoteWriteResult {
        val saved = NotePersistence.saveOrDeleteGeneralNote(context, phone, text)
        val result = CallNoteWriteResult(saved, true, CallNoteTarget("", 0L, 0L))
        syncToCrmIfNeeded(context, phone, text, result)
        return result
    }

    fun writeCallOrGeneral(
        context: Context,
        phone: String,
        text: String,
        direction: String,
        callAt: Long,
        durationSeconds: Long,
        actionIssuedAt: Long = 0L,
    ): CallNoteWriteResult {
        val target = targetFor(context, phone, direction, callAt, durationSeconds, actionIssuedAt)
        if (!target.hasCall) return writeGeneral(context, phone, text)
        val saved = NotePersistence.saveOrDeleteCallNote(
            context = context,
            phoneNumber = phone,
            note = text,
            direction = target.direction,
            callAt = target.callAt,
            durationSeconds = target.durationSeconds,
        )
        val result = CallNoteWriteResult(saved, false, target)
        syncToCrmIfNeeded(context, phone, text, result)
        return result
    }

    private fun syncToCrmIfNeeded(context: Context, phone: String, text: String, result: CallNoteWriteResult) {
        if (!result.saved || text.trim().isBlank()) return
        if (result.savedAsGeneralNote) {
            CrmNoteSyncer.syncGeneralIfEnabled(context, phone, text)
        } else {
            val clientNoteId = LocalNotesFileStore.clientNoteIdForCall(phone, result.target.callAt, result.target.direction)
            CrmNoteSyncer.syncCallIfEnabled(
                context = context,
                phone = phone,
                note = text,
                direction = result.target.direction,
                callAt = result.target.callAt,
                durationSeconds = result.target.durationSeconds,
                clientNoteId = clientNoteId,
            )
        }
    }

    private fun targetFor(
        context: Context,
        phone: String,
        direction: String,
        callAt: Long,
        durationSeconds: Long,
        actionIssuedAt: Long,
    ): CallNoteTarget {
        if (actionIssuedAt <= 0L && callAt > 0L) return CallNoteTarget(direction, callAt, durationSeconds)
        return CallNoteTargetResolver.resolve(context, phone, direction, callAt, durationSeconds, actionIssuedAt)
    }
}
