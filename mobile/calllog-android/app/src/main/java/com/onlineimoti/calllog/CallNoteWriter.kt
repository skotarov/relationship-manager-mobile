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
        return CallNoteWriteResult(saved, true, CallNoteTarget("", 0L, 0L))
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
        return CallNoteWriteResult(saved, false, target)
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
