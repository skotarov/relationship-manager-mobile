package com.onlineimoti.calllog

import kotlin.math.abs

/** A blue Home-row note, selected from either local storage or server history. */
internal data class HomeCallNote(
    val text: String,
    val updatedAtMs: Long,
    val fromServer: Boolean,
    val authorName: String = "",
    /** Firm assigned to the same concrete conversation, when known. */
    val companyId: String = "",
    /** Lets Home edit an existing server-only note in place instead of duplicating it. */
    val serverClientEventId: String = "",
    /** Foreign server notes stay visible but must not be opened for mutation. */
    val editable: Boolean = true,
)

/**
 * Resolves notes for the exact Call Log rows visible on Home. General/contact
 * notes are deliberately excluded: the blue card below a call belongs only to
 * that concrete conversation.
 */
internal object HomeCallNotesResolver {
    fun localNotes(context: android.content.Context, calls: List<PhoneCallRecord>): Map<String, HomeCallNote> {
        if (calls.isEmpty()) return emptyMap()
        val notesByPhoneKey = calls
            .filterNot { it.isSms }
            .map { it.number }
            .distinctBy(HomeCallPageLoader::noteKey)
            .associateBy(
                keySelector = HomeCallPageLoader::noteKey,
                valueTransform = { phone -> ContactNoteReader.callNotesForPhone(context, phone) },
            )
        val result = linkedMapOf<String, HomeCallNote>()
        val claimedNotes = hashSetOf<String>()
        calls.filterNot { it.isSms }.forEach { call ->
            val local = notesByPhoneKey[HomeCallPageLoader.noteKey(call.number)]
                .orEmpty()
                .filter { note -> sameLocalCall(call, note) }
                .filterNot { note -> claimedNoteKey(note) in claimedNotes }
                .maxWithOrNull(compareBy<ContactCallNote> { localMatchScore(call, it) }.thenBy(::localVersionMs))
            if (local != null) {
                claimedNotes += claimedNoteKey(local)
                result[keyFor(call)] = HomeCallNote(
                    text = local.note,
                    updatedAtMs = localVersionMs(local),
                    fromServer = false,
                    companyId = local.companyId.trim(),
                )
                return@forEach
            }

            // Restored older calllog.notes files may not contain the v2 "type"
            // marker, so they are skipped by the bulk parser. The direct lookup
            // parses the row by call_at/direction and still recovers the note.
            val directNote = ContactNoteReader.callNoteForPhone(context, call.number, call.startedAt, call.direction)
            if (directNote.isNotBlank()) {
                result[keyFor(call)] = HomeCallNote(
                    text = directNote,
                    updatedAtMs = call.startedAt,
                    fromServer = false,
                    companyId = LocalNotesFileStore.companyIdForCall(context, call.number, call.startedAt, call.direction),
                )
            }
        }
        return result
    }

    /**
     * Keeps the latest note for each visible call. Server is the tie-breaker
     * because it is the shared/canonical copy when both timestamps are equal.
     */
    fun mergeWithServer(
        calls: List<PhoneCallRecord>,
        localNotes: Map<String, HomeCallNote>,
        serverEvents: List<CallReportHistoryEvent>,
        principal: CallReportHistoryPrincipal = CallReportHistoryPrincipal(),
    ): Map<String, HomeCallNote> {
        if (calls.isEmpty()) return emptyMap()
        // An earlier enrichment may have left server-only rows in the current
        // render snapshot. Start again from local rows so an authoritative empty
        // server response removes deleted records from Call Log immediately.
        val merged = localNotes.filterValues { note -> !note.fromServer }.toMutableMap()
        val callsByPhone = calls
            .filterNot { it.isSms }
            .groupBy { HomeCallPageLoader.noteKey(it.number) }
        val claimedEvents = hashSetOf<String>()

        serverEvents.forEachIndexed { index, event ->
            if (!event.communicationType.equals("note", ignoreCase = true) || event.note.isBlank()) return@forEachIndexed
            if (CallReportServerNoteClassifier.isExplicitGeneralNote(event)) return@forEachIndexed
            val candidates = callsByPhone[HomeCallPageLoader.noteKey(event.phone)].orEmpty()
            val call = candidates
                .filter { candidate -> sameServerCall(candidate, event) }
                .minByOrNull { candidate -> abs(candidate.startedAt - event.occurredAtMs) }
                ?: return@forEachIndexed
            if (!canAttachServerNoteToCall(event)) return@forEachIndexed
            val eventKey = event.clientEventId.ifBlank { "server:$index:${event.serverId}:${event.occurredAtMs}:${event.note.hashCode()}" }
            if (!claimedEvents.add(eventKey)) return@forEachIndexed
            val key = keyFor(call)
            val candidate = HomeCallNote(
                text = event.note.trim(),
                updatedAtMs = serverVersionMs(event),
                fromServer = true,
                authorName = event.authorBrokerName.trim(),
                companyId = event.companyId.trim(),
                serverClientEventId = event.clientEventId.trim(),
                editable = !isOtherBrokerAuthor(event, principal),
            )
            val current = merged[key]
            if (current == null || isNewer(candidate, current)) merged[key] = candidate
        }
        return merged
    }

    fun keyFor(call: PhoneCallRecord): String {
        return "${HomeCallPageLoader.noteKey(call.number)}|${call.startedAt}|${call.direction.trim()}"
    }

    private fun sameLocalCall(call: PhoneCallRecord, note: ContactCallNote): Boolean {
        if (call.startedAt <= 0L || note.callAt <= 0L) return false
        if (abs(call.startedAt - note.callAt) > LOCAL_NOTE_CALL_MATCH_WINDOW_MS) return false
        return call.direction.isBlank() || note.direction.isBlank() || call.direction == note.direction
    }

    private fun localMatchScore(call: PhoneCallRecord, note: ContactCallNote): Long = -abs(call.startedAt - note.callAt)

    private fun sameServerCall(call: PhoneCallRecord, event: CallReportHistoryEvent): Boolean {
        if (call.startedAt <= 0L || event.occurredAtMs <= 0L) return false
        if (abs(call.startedAt - event.occurredAtMs) > SERVER_NOTE_CALL_MATCH_WINDOW_MS) return false
        return call.direction.isBlank() || event.direction.isBlank() || call.direction == event.direction
    }

    private fun canAttachServerNoteToCall(event: CallReportHistoryEvent): Boolean {
        if (CallReportServerNoteClassifier.isExplicitGeneralNote(event)) return false
        // Full log attaches ordinary NOTE rows to nearby calls even when older
        // server records do not carry the Android :note:call marker. Home should
        // use the same rule once the timestamp has already matched a visible call.
        return CallReportServerNoteClassifier.isConcreteCallNote(event) ||
            (event.communicationType.equals("note", ignoreCase = true) && event.note.isNotBlank() && event.occurredAtMs > 0L)
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

    private fun claimedNoteKey(note: ContactCallNote): String {
        return note.clientNoteId.ifBlank { "${note.callAt}|${note.direction}|${note.note.hashCode()}" }
    }

    private fun localVersionMs(note: ContactCallNote): Long = maxOf(note.savedAt, note.callAt)

    private fun serverVersionMs(event: CallReportHistoryEvent): Long = maxOf(
        event.updatedAtMs,
        event.createdAtMs,
        event.occurredAtMs,
    )

    private fun isNewer(candidate: HomeCallNote, current: HomeCallNote): Boolean {
        return candidate.updatedAtMs > current.updatedAtMs ||
            (candidate.updatedAtMs == current.updatedAtMs && candidate.fromServer && !current.fromServer)
    }

    private const val LOCAL_NOTE_CALL_MATCH_WINDOW_MS = 5 * 60 * 1000L
    private const val SERVER_NOTE_CALL_MATCH_WINDOW_MS = 10 * 60 * 1000L
}
