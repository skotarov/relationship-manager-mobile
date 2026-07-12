package com.onlineimoti.calllog

/** Distinguishes yellow/general server notes from blue/conversation notes. */
internal object CallReportServerNoteClassifier {
    fun isExplicitGeneralNote(event: CallReportHistoryEvent): Boolean {
        if (!event.communicationType.equals("note", ignoreCase = true) || event.note.isBlank()) return false
        val id = event.clientEventId.lowercase()
        return id.contains(":note:general:") || id.contains(":topic:general:")
    }

    fun isExplicitCallNote(event: CallReportHistoryEvent): Boolean {
        if (!event.communicationType.equals("note", ignoreCase = true) || event.note.isBlank()) return false
        val id = event.clientEventId.lowercase()
        return id.contains(":note:call:") || id.contains(":topic:call:")
    }

    fun isGeneralNote(event: CallReportHistoryEvent): Boolean {
        if (!event.communicationType.equals("note", ignoreCase = true) || event.note.isBlank()) return false
        if (isExplicitCallNote(event)) return false
        if (isExplicitGeneralNote(event)) return true

        // Some server-side records can be returned without the Android client_event_id
        // marker. A note with no call direction and no duration is usually a contact/company
        // general note, but Home may still attach it as blue when its timestamp matches
        // one of the visible calls, mirroring the Full log grouping logic.
        return event.direction.isBlank() && event.durationSeconds <= 0L
    }

    fun isConcreteCallNote(event: CallReportHistoryEvent): Boolean {
        if (!event.communicationType.equals("note", ignoreCase = true) || event.note.isBlank()) return false
        if (isExplicitGeneralNote(event)) return false
        if (isExplicitCallNote(event)) return true
        return event.direction.isNotBlank() || event.durationSeconds > 0L
    }
}
