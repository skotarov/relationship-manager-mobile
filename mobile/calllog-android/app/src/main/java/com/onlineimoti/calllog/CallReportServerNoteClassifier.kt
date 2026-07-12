package com.onlineimoti.calllog

/** Distinguishes yellow/general server notes from blue/conversation notes. */
internal object CallReportServerNoteClassifier {
    fun isGeneralNote(event: CallReportHistoryEvent): Boolean {
        if (!event.communicationType.equals("note", ignoreCase = true) || event.note.isBlank()) return false
        val id = event.clientEventId.lowercase()
        if (id.contains(":note:call:") || id.contains(":topic:call:")) return false
        if (id.contains(":note:general:") || id.contains(":topic:general:")) return true

        // Some server-side records can be returned without the Android client_event_id
        // marker. A note with no call direction and no duration is a contact/company
        // general note and must stay yellow, not be attached to a call row as blue.
        return event.direction.isBlank() && event.durationSeconds <= 0L
    }

    fun isConcreteCallNote(event: CallReportHistoryEvent): Boolean {
        if (!event.communicationType.equals("note", ignoreCase = true) || event.note.isBlank()) return false
        val id = event.clientEventId.lowercase()
        if (isGeneralNote(event)) return false
        if (id.contains(":note:call:") || id.contains(":topic:call:")) return true
        return event.direction.isNotBlank() || event.durationSeconds > 0L
    }
}
