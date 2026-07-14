package com.onlineimoti.calllog

import android.content.Context

/** Server-only note helpers for the Clients page. */
internal object HomeCrmClientServerNotes {
    fun latestCallNotes(
        context: Context,
        contacts: List<PhoneCallRecord>,
    ): Map<String, HomeCallNote> {
        if (contacts.isEmpty()) return emptyMap()
        val config = ConfigStore.load(context.applicationContext)
        if (!CallReportRemoteAccess.isReady(config)) return emptyMap()
        val phones = contacts
            .map { it.number }
            .filter { HomeCallPageLoader.noteKey(it).isNotBlank() }
            .distinctBy(HomeCallPageLoader::noteKey)
        if (phones.isEmpty()) return emptyMap()
        val history = runCatching {
            CallReportHistoryLookupClient.lookupMany(config, phones, context.applicationContext)
        }.getOrDefault(CallReportHistoryLookupResult())
        return latestCallNotes(contacts, history)
    }

    fun latestCallNotes(
        contacts: List<PhoneCallRecord>,
        history: CallReportHistoryLookupResult,
    ): Map<String, HomeCallNote> {
        if (contacts.isEmpty() || history.events.isEmpty()) return emptyMap()
        val rowByPhoneKey = contacts
            .filter { HomeCallPageLoader.noteKey(it.number).isNotBlank() }
            .associateBy { HomeCallPageLoader.noteKey(it.number) }
        if (rowByPhoneKey.isEmpty()) return emptyMap()

        val latest = linkedMapOf<String, Pair<Long, HomeCallNote>>()
        history.events.forEach { event ->
            val phoneKey = HomeCallPageLoader.noteKey(event.phone)
            val row = rowByPhoneKey[phoneKey] ?: return@forEach
            if (!isBlueServerNote(event)) return@forEach
            val changedAt = maxOf(event.updatedAtMs, event.createdAtMs, event.occurredAtMs)
            val key = HomeCallNotesResolver.keyFor(row)
            val note = HomeCallNote(
                text = event.note.trim(),
                updatedAtMs = changedAt,
                fromServer = true,
                authorName = event.authorBrokerName.trim(),
                companyId = event.companyId.trim(),
                serverClientEventId = event.clientEventId.trim(),
                editable = !isOtherBrokerAuthor(event, history.principal),
            )
            val current = latest[key]
            if (current == null || changedAt >= current.first) latest[key] = changedAt to note
        }
        return latest.mapValues { it.value.second }
    }

    /**
     * On the Clients page, ordinary server NOTE rows are conversation/blue notes.
     * Yellow notes come from the explicit company/main-note channel, not from these
     * contact history events. This keeps the Clients card able to show both lanes.
     */
    private fun isBlueServerNote(event: CallReportHistoryEvent): Boolean {
        return event.communicationType.equals("note", ignoreCase = true) &&
            event.note.trim().isNotBlank() &&
            !CallReportServerNoteClassifier.isExplicitGeneralNote(event)
    }

    private fun isOtherBrokerAuthor(
        event: CallReportHistoryEvent,
        principal: CallReportHistoryPrincipal,
    ): Boolean {
        val authorId = event.authorBrokerId.trim()
        val authorName = event.authorBrokerName.trim()
        val currentId = principal.brokerId.trim()
        val currentName = principal.brokerName.trim()
        if (authorId.isNotBlank() && currentId.isNotBlank()) return authorId != currentId
        if (authorName.isNotBlank() && currentName.isNotBlank()) return !authorName.equals(currentName, ignoreCase = true)
        return false
    }
}