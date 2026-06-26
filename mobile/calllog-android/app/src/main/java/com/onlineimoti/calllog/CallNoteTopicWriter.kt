package com.onlineimoti.calllog

import android.content.Context

/** Saves a note only under the user-selected company topic. */
internal object CallNoteTopicWriter {
    fun writeGeneral(
        context: Context,
        phone: String,
        text: String,
        companyId: String,
    ): CallNoteWriteResult {
        // A main note selected under a company is not the phone's ordinary local
        // note. Keeping it separately prevents one company's value overwriting
        // the value displayed for another company.
        val saved = CallReportCompanyGeneralNoteStore.saveOrDelete(context, phone, companyId, text)
        val result = CallNoteWriteResult(saved, true, CallNoteTarget("", 0L, 0L))
        if (!saved) return result

        CallReportTopicNoteOutbox.enqueueGeneral(context, phone, text, companyId)
        return result
    }

    fun writeCallOrGeneral(
        context: Context,
        phone: String,
        text: String,
        direction: String,
        callAt: Long,
        durationSeconds: Long,
        actionIssuedAt: Long,
        companyId: String,
    ): CallNoteWriteResult {
        val target = targetFor(context, phone, direction, callAt, durationSeconds, actionIssuedAt)
        if (!target.hasCall) {
            return writeGeneral(context, phone, text, companyId)
        }

        val saved = NotePersistence.saveOrDeleteCallNote(
            context = context,
            phoneNumber = phone,
            note = text,
            direction = target.direction,
            callAt = target.callAt,
            durationSeconds = target.durationSeconds,
        )
        val result = CallNoteWriteResult(saved, false, target)
        if (!saved) return result

        if (CrmContactSyncStore.isEnabled(context, phone)) {
            RmLayerContactDataSyncer.sync(context, phone)
        }
        val clientNoteId = LocalNotesFileStore.clientNoteIdForCall(phone, target.callAt, target.direction)
        CallReportTopicNoteOutbox.enqueueCall(
            context = context,
            phone = phone,
            note = text,
            direction = target.direction,
            callAt = target.callAt,
            durationSeconds = target.durationSeconds,
            companyId = companyId,
            clientNoteId = clientNoteId,
        )
        return result
    }

    private fun targetFor(
        context: Context,
        phone: String,
        direction: String,
        callAt: Long,
        durationSeconds: Long,
        actionIssuedAt: Long,
    ): CallNoteTarget {
        if (actionIssuedAt <= 0L && callAt > 0L) {
            return CallNoteTarget(direction, callAt, durationSeconds)
        }
        return CallNoteTargetResolver.resolve(context, phone, direction, callAt, durationSeconds, actionIssuedAt)
    }
}
