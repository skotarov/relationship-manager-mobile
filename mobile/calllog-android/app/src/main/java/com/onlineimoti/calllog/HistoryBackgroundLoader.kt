package com.onlineimoti.calllog

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/** Data read from Android providers and local stores away from the UI thread. */
internal data class HistoryLocalSnapshot(
    val latestCall: PhoneCallRecord? = null,
    val sms: List<SmsMessageRecord> = emptyList(),
    val callNotes: List<ContactCallNote> = emptyList(),
    val generalNote: String = "",
    val generalNotePending: Boolean = false,
    val contactExists: Boolean = false,
    val companyScopeAvailable: Boolean = false,
)

/** Fully prepared History content; the main thread only turns this state into Views. */
internal data class HistoryPreparedSnapshot(
    val rows: List<CallReportHistoryRow> = emptyList(),
    val companyMainNotes: List<CallReportCompanyMainNote> = emptyList(),
    val unscopedServerMainNote: CallReportHistoryEvent? = null,
    val hasCompanyMainNoteScope: Boolean = false,
    val confirmedLocalServerNote: Boolean = false,
)

internal object HistoryBackgroundLoader {
    fun loadLocal(context: Context, phone: String): HistoryLocalSnapshot {
        if (phone.isBlank()) return HistoryLocalSnapshot()

        // This may inspect the Android call log and write a resolved pending note,
        // so it must happen before the snapshot and never from render().
        PendingCallNoteStore.reconcilePendingForPhone(context, phone)

        val contactExists = hasRealContact(context, phone)
        val crmEnabled = CrmContactSyncStore.isEnabled(context, phone)
        val unknownNumber = !crmEnabled && !contactExists
        return HistoryLocalSnapshot(
            latestCall = PhoneCallReader.callsForPhone(context, phone, limit = 1).firstOrNull(),
            sms = SmsMessageReader.messagesForPhone(context, phone, limit = 150),
            callNotes = ContactNoteReader.callNotesForPhone(context, phone),
            generalNote = ContactNoteReader.generalNoteForPhone(context, phone),
            generalNotePending = CallReportDeferredCompanyAssignmentStore.isGeneralPending(context, phone),
            contactExists = contactExists,
            companyScopeAvailable = ContactServerCompanyScopePolicy.isAvailable(crmEnabled, unknownNumber),
        )
    }

    fun prepare(
        context: Context,
        phone: String,
        remoteEnabled: Boolean,
        serverLoaded: Boolean,
        history: CallReportHistoryLookupResult,
        localSms: List<SmsMessageRecord>,
        localNotes: List<ContactCallNote>,
    ): HistoryPreparedSnapshot {
        if (phone.isBlank()) return HistoryPreparedSnapshot()
        if (remoteEnabled) reconcileServerConfirmation(context, phone, history)
        val scopedServerLoaded = remoteEnabled && serverLoaded
        val principal = if (remoteEnabled) history.principal else CallReportHistoryPrincipal()
        val timelineEvents = if (remoteEnabled) notesAndSms(history.events) else emptyList()
        return HistoryPreparedSnapshot(
            rows = CallReportHistoryMerge.merge(
                context = context,
                phone = phone,
                principal = principal,
                localCalls = emptyList(),
                localSms = localSms,
                localNotes = localNotes,
                serverEvents = timelineEvents,
            ),
            companyMainNotes = companyMainNotes(context, phone, scopedServerLoaded, history),
            unscopedServerMainNote = unscopedServerMainNote(phone, scopedServerLoaded, history),
            hasCompanyMainNoteScope = scopedServerLoaded && history.principal.companies.isNotEmpty(),
            confirmedLocalServerNote = ServerRecordIndex.hasConfirmedNoteForPhone(context, phone, localNotes),
        )
    }

    private fun reconcileServerConfirmation(
        context: Context,
        phone: String,
        history: CallReportHistoryLookupResult,
    ) {
        val phoneKey = HomeCallPageLoader.noteKey(phone)
        val confirmedNoteIds = history.events.asSequence()
            .filter { event ->
                event.communicationType.equals("note", ignoreCase = true) &&
                    event.note.trim().isNotBlank() &&
                    HomeCallPageLoader.noteKey(event.phone) == phoneKey
            }
            .map { event -> event.clientEventId.trim() }
            .filter { id -> id.isNotBlank() }
            .toList()
        ServerRecordIndex.markConfirmed(context, history.events.map { it.clientEventId })
        ServerRecordIndex.reconcileConfirmedNotesForPhone(context, phone, confirmedNoteIds)
    }

    private fun companyMainNotes(
        context: Context,
        phone: String,
        serverLoaded: Boolean,
        history: CallReportHistoryLookupResult,
    ): List<CallReportCompanyMainNote> {
        if (!serverLoaded || history.principal.companies.isEmpty()) return emptyList()
        val phoneKey = HomeCallPageLoader.noteKey(phone)
        val latestByCompany = mutableMapOf<String, CallReportHistoryEvent>()
        history.events.forEach { event ->
            if (!event.communicationType.equals("note", ignoreCase = true)) return@forEach
            if (event.companyId.isBlank() || HomeCallPageLoader.noteKey(event.phone) != phoneKey) return@forEach
            if (!CallReportServerNoteClassifier.isExplicitGeneralNote(event)) return@forEach
            val current = latestByCompany[event.companyId]
            if (current == null || event.updatedAtMs >= current.updatedAtMs) latestByCompany[event.companyId] = event
        }
        return history.principal.companies.map { company ->
            val remote = latestByCompany[company.id]
            val pending = CallReportCompanyGeneralNotePending.isPending(context, phone, company.id)
            val cached = CallReportCompanyGeneralNoteStore.noteFor(context, phone, company.id)
            if (!pending && remote == null && cached.isNotBlank()) {
                CallReportCompanyGeneralNoteStore.saveOrDelete(context, phone, company.id, "")
            }
            val note = when {
                pending && cached.isNotBlank() -> cached
                remote != null -> remote.note
                else -> ""
            }
            CallReportCompanyMainNote(
                companyId = company.id,
                companyName = company.name,
                note = note,
                updatedAtMs = remote?.updatedAtMs ?: 0L,
                confirmedByServer = remote != null && !pending && remote.note.isNotBlank(),
                pending = pending,
            )
        }
    }

    private fun unscopedServerMainNote(
        phone: String,
        serverLoaded: Boolean,
        history: CallReportHistoryLookupResult,
    ): CallReportHistoryEvent? {
        if (!serverLoaded || phone.isBlank()) return null
        val phoneKey = HomeCallPageLoader.noteKey(phone)
        if (phoneKey.isBlank()) return null
        return history.events
            .asSequence()
            .filter { event ->
                event.companyId.isBlank() &&
                    event.note.trim().isNotBlank() &&
                    HomeCallPageLoader.noteKey(event.phone) == phoneKey &&
                    CallReportServerNoteClassifier.isExplicitGeneralNote(event)
            }
            .maxByOrNull { event -> maxOf(event.updatedAtMs, event.createdAtMs, event.occurredAtMs) }
    }

    private fun notesAndSms(events: List<CallReportHistoryEvent>): List<CallReportHistoryEvent> =
        events.filter { event ->
            event.communicationType.equals("sms", ignoreCase = true) ||
                (event.communicationType.equals("note", ignoreCase = true) &&
                    event.note.trim().isNotBlank() &&
                    !CallReportServerNoteClassifier.isExplicitGeneralNote(event))
        }

    private fun hasRealContact(context: Context, phone: String): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return false
        }
        return RmRealContactLookup.findContactId(context, phone) > 0L
    }
}
