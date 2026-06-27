package com.onlineimoti.calllog

import android.content.Context

/** A note shown in the incoming lookup popup after the server history arrives. */
internal data class PostCallLookupRemoteRow(
    val kind: Kind,
    val companyName: String,
    val note: String,
    val occurredAtMs: Long,
) {
    enum class Kind { GENERAL_NOTE, CALL_NOTE }
}

/**
 * Keeps the incoming lookup popup intentionally small: all company-scoped main
 * notes plus the single most recent conversation note are enough to identify a
 * caller without turning the overlay into a full history screen.
 */
internal object PostCallLookupRemoteRows {
    private const val MAX_GENERAL_NOTES = 3

    fun shouldLookup(context: Context, phone: String): Boolean {
        if (phone.isBlank()) return false
        val config = ConfigStore.load(context.applicationContext)
        return CallReportRemoteAccess.isReady(config) &&
            ContactServerCompanyScope.isAvailable(context.applicationContext, phone)
    }

    fun load(context: Context, phone: String): List<PostCallLookupRemoteRow> {
        val appContext = context.applicationContext
        if (!shouldLookup(appContext, phone)) return emptyList()
        val history = CallReportHistoryLookupClient.lookup(ConfigStore.load(appContext), phone)
        return fromHistory(history, phone)
    }

    internal fun fromHistory(
        history: CallReportHistoryLookupResult,
        phone: String,
    ): List<PostCallLookupRemoteRow> {
        val phoneKey = HomeCallPageLoader.noteKey(phone)
        if (phoneKey.isBlank()) return emptyList()
        val companyNames = history.principal.companies.associate { company -> company.id to company.name }
        val relevantNotes = history.events.filter { event ->
            event.communicationType.equals("note", ignoreCase = true) &&
                event.note.trim().isNotBlank() &&
                event.companyId.isNotBlank() &&
                HomeCallPageLoader.noteKey(event.phone) == phoneKey
        }
        if (relevantNotes.isEmpty()) return emptyList()

        val latestMainNoteByCompany = mutableMapOf<String, CallReportHistoryEvent>()
        relevantNotes.filter(::isMainNote).forEach { event ->
            val current = latestMainNoteByCompany[event.companyId]
            if (current == null || eventTimestamp(event) >= eventTimestamp(current)) {
                latestMainNoteByCompany[event.companyId] = event
            }
        }

        val mainRows = latestMainNoteByCompany.values
            .sortedByDescending(::eventTimestamp)
            .take(MAX_GENERAL_NOTES)
            .map { event ->
                PostCallLookupRemoteRow(
                    kind = PostCallLookupRemoteRow.Kind.GENERAL_NOTE,
                    companyName = companyNameFor(event, companyNames),
                    note = compact(event.note),
                    occurredAtMs = eventTimestamp(event),
                )
            }

        val latestConversation = relevantNotes
            .asSequence()
            .filterNot(::isMainNote)
            .maxByOrNull(::eventTimestamp)
            ?.let { event ->
                PostCallLookupRemoteRow(
                    kind = PostCallLookupRemoteRow.Kind.CALL_NOTE,
                    companyName = companyNameFor(event, companyNames),
                    note = compact(event.note),
                    occurredAtMs = eventTimestamp(event),
                )
            }

        return buildList {
            addAll(mainRows)
            latestConversation?.let(::add)
        }
    }

    private fun isMainNote(event: CallReportHistoryEvent): Boolean {
        if (event.clientEventId.contains(":topic:general:") || event.clientEventId.contains(":note:general:")) {
            return true
        }
        return event.direction.isBlank() && event.durationSeconds <= 0L
    }

    private fun companyNameFor(
        event: CallReportHistoryEvent,
        companyNames: Map<String, String>,
    ): String = companyNames[event.companyId].orEmpty().ifBlank { event.companyId }

    private fun eventTimestamp(event: CallReportHistoryEvent): Long = maxOf(event.updatedAtMs, event.occurredAtMs, event.createdAtMs)

    private fun compact(value: String): String = value.trim().replace(Regex("\\s+"), " ")
}
