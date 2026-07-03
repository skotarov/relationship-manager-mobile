package com.onlineimoti.calllog

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
        calls.filterNot { it.isSms }.forEach { call ->
            val local = notesByPhoneKey[HomeCallPageLoader.noteKey(call.number)]
                .orEmpty()
                .filter { note -> sameLocalCall(call, note) }
                .maxByOrNull(::localVersionMs)
                ?: return@forEach
            result[keyFor(call)] = HomeCallNote(
                text = local.note,
                updatedAtMs = localVersionMs(local),
                fromServer = false,
                companyId = local.companyId.trim(),
            )
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
    ): Map<String, HomeCallNote> {
        if (calls.isEmpty()) return emptyMap()
        val merged = localNotes.toMutableMap()
        val callsByPhone = calls
            .filterNot { it.isSms }
            .groupBy { HomeCallPageLoader.noteKey(it.number) }

        serverEvents.forEach { event ->
            if (!isConcreteCallNote(event)) return@forEach
            val candidates = callsByPhone[HomeCallPageLoader.noteKey(event.phone)].orEmpty()
            candidates
                .filter { call -> sameServerCall(call, event) }
                .forEach { call ->
                    val key = keyFor(call)
                    val candidate = HomeCallNote(
                        text = event.note.trim(),
                        updatedAtMs = serverVersionMs(event),
                        fromServer = true,
                        authorName = event.authorBrokerName.trim(),
                        companyId = event.companyId.trim(),
                        serverClientEventId = event.clientEventId.trim(),
                    )
                    val current = merged[key]
                    if (current == null || isNewer(candidate, current)) merged[key] = candidate
                }
        }
        return merged
    }

    fun keyFor(call: PhoneCallRecord): String {
        return "${HomeCallPageLoader.noteKey(call.number)}|${call.startedAt}|${call.direction.trim()}"
    }

    private fun sameLocalCall(call: PhoneCallRecord, note: ContactCallNote): Boolean {
        if (call.startedAt <= 0L || note.callAt != call.startedAt) return false
        return call.direction.isBlank() || note.direction.isBlank() || call.direction == note.direction
    }

    private fun sameServerCall(call: PhoneCallRecord, event: CallReportHistoryEvent): Boolean {
        if (call.startedAt <= 0L || event.occurredAtMs != call.startedAt) return false
        return call.direction.isBlank() || event.direction.isBlank() || call.direction == event.direction
    }

    private fun isConcreteCallNote(event: CallReportHistoryEvent): Boolean {
        if (!event.communicationType.equals("note", ignoreCase = true) || event.note.isBlank()) return false
        val id = event.clientEventId
        if (id.contains(":note:general:") || id.contains(":topic:general:")) return false
        return event.direction.isNotBlank() ||
            event.durationSeconds > 0L ||
            id.contains(":note:call:") ||
            id.contains(":topic:call:")
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
}
