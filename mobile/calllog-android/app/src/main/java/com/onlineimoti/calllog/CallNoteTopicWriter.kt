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
        // Guard the writer too, not only the form: unmarked contacts must never
        // create a company-scoped outbox event through an alternate caller.
        if (!CrmContactSyncStore.isEnabled(context, phone)) {
            return CallNoteWriter.writeGeneral(
                context = context,
                phone = phone,
                text = text,
                syncToCrm = false,
            )
        }

        // A main note selected under a company is not the phone's ordinary local
        // note. Keeping it separately prevents one company's value overwriting
        // the value displayed for another company.
        val saved = CallReportCompanyGeneralNoteStore.saveOrDelete(context, phone, companyId, text)
        val result = CallNoteWriteResult(saved, true, CallNoteTarget("", 0L, 0L))
        if (!saved) return result

        HomeCrmCompanyMembershipStore.invalidate(context, phone)
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
            return saveAsPendingCallNote(
                context = context,
                phone = phone,
                text = text,
                direction = direction,
                actionIssuedAt = actionIssuedAt,
                companyId = companyId,
            )
        }

        // Keep any direct or stale invocation local unless the contact carries
        // the explicit CRM marker. The resolved call target is passed through so
        // a call note can never silently fall back into the general-note bucket.
        if (!CrmContactSyncStore.isEnabled(context, phone)) {
            return CallNoteWriter.writeCallOrGeneral(
                context = context,
                phone = phone,
                text = text,
                direction = target.direction,
                callAt = target.callAt,
                durationSeconds = target.durationSeconds,
                actionIssuedAt = 0L,
                syncToCrm = false,
            )
        }

        val saved = NotePersistence.saveOrDeleteCallNote(
            context = context,
            phoneNumber = phone,
            note = text,
            direction = target.direction,
            callAt = target.callAt,
            durationSeconds = target.durationSeconds,
            companyId = companyId,
        )
        val result = CallNoteWriteResult(saved, false, target)
        if (!saved) return result

        PendingCallNoteStore.clearResolvedForCall(context, phone, target.direction, target.callAt)
        HomeCrmCompanyMembershipStore.invalidate(context, phone)
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

    /**
     * A note opened as a call note must never turn into a company main note merely
     * because Android has not exposed the matching call-log row yet.
     */
    private fun saveAsPendingCallNote(
        context: Context,
        phone: String,
        text: String,
        direction: String,
        actionIssuedAt: Long,
        companyId: String,
    ): CallNoteWriteResult {
        val activeSession = PendingCallNoteStore.activeSessionForPhone(context, phone)
        val shouldSavePending = activeSession != null || actionIssuedAt > 0L
        if (!shouldSavePending) {
            return CallNoteWriteResult(
                saved = false,
                savedAsGeneralNote = false,
                target = CallNoteTarget(direction, 0L, 0L),
            )
        }

        val pendingDirection = activeSession?.direction?.ifBlank { direction } ?: direction
        val pendingStartedAt = activeSession?.sessionStartedAt ?: actionIssuedAt
        val saved = PendingCallNoteStore.saveOrDelete(
            context = context,
            phone = phone,
            direction = pendingDirection,
            sessionStartedAt = pendingStartedAt,
            text = text,
            companyId = companyId,
        )
        if (saved) HomeCrmCompanyMembershipStore.invalidate(context, phone)
        if (saved && text.trim().isNotBlank() && activeSession == null) {
            PendingCallNoteStore.reconcileSoon(context, phone)
        }
        return CallNoteWriteResult(
            saved = saved,
            savedAsGeneralNote = false,
            target = CallNoteTarget(pendingDirection, 0L, 0L),
            savedAsPending = saved,
        )
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
