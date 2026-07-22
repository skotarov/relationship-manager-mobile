package com.onlineimoti.calllog

import android.content.Context
import kotlin.math.abs

/** Local sources needed by both the embedded History tab and the legacy Home full-log adapter. */
internal data class FilteredFullLogLocalData(
    val calls: List<PhoneCallRecord> = emptyList(),
    val sms: List<SmsMessageRecord> = emptyList(),
    val notes: List<ContactCallNote> = emptyList(),
)

/**
 * Single source of truth for loading, merging and grouping one contact's complete timeline.
 * The Home screen currently adapts this data to ActivityHomeBinding; History renders the
 * same entries in-place. Removing the old Home entry later will not remove this shared logic.
 */
internal object FilteredFullLogLoader {
    fun loadLocal(context: Context, phone: String): FilteredFullLogLocalData {
        if (phone.isBlank()) return FilteredFullLogLocalData()
        return FilteredFullLogLocalData(
            calls = PhoneCallReader.callsForPhone(context, phone, limit = SOURCE_CALL_LIMIT),
            sms = SmsMessageReader.messagesForPhone(context, phone, limit = SOURCE_SMS_LIMIT),
            notes = ContactNoteReader.callNotesForPhone(context, phone),
        )
    }

    fun load(context: Context, phone: String, remoteEnabled: Boolean): List<FilteredFullLogEntry> {
        if (phone.isBlank()) return emptyList()
        val local = loadLocal(context, phone)
        val serverHistory = if (remoteEnabled) {
            runCatching {
                CallReportHistoryLookupClient.lookup(
                    config = ConfigStore.load(context),
                    phone = phone,
                    context = context,
                )
            }.getOrDefault(CallReportHistoryLookupResult())
        } else {
            CallReportHistoryLookupResult()
        }
        return prepare(
            context = context,
            phone = phone,
            remoteEnabled = remoteEnabled,
            principal = serverHistory.principal,
            local = local,
            serverEvents = serverHistory.events,
        )
    }

    fun prepare(
        context: Context,
        phone: String,
        remoteEnabled: Boolean,
        principal: CallReportHistoryPrincipal,
        local: FilteredFullLogLocalData,
        serverEvents: List<CallReportHistoryEvent>,
    ): List<FilteredFullLogEntry> {
        if (phone.isBlank()) return emptyList()
        val merged = CallReportHistoryMerge.merge(
            context = context,
            phone = phone,
            principal = if (remoteEnabled) principal else CallReportHistoryPrincipal(),
            localCalls = local.calls,
            localSms = local.sms,
            localNotes = local.notes,
            serverEvents = if (remoteEnabled) serverEvents else emptyList(),
        )
        return groupedEntries(merged)
    }

    private fun groupedEntries(timeline: List<CallReportHistoryRow>): List<FilteredFullLogEntry> {
        val callIndexes = timeline.mapIndexedNotNull { index, row ->
            index.takeIf { row.kind == CallReportHistoryRowKind.PHONE }
        }
        val notesByCall = mutableMapOf<Int, MutableList<CallReportHistoryRow>>()
        val attachedIndexes = mutableSetOf<Int>()
        timeline.forEachIndexed { noteIndex, note ->
            if (note.kind != CallReportHistoryRowKind.NOTE) return@forEachIndexed
            if (note.serverEvent?.let(CallReportServerNoteClassifier::isGeneralNote) == true) return@forEachIndexed
            val callIndex = matchingCallIndex(note, callIndexes, timeline) ?: return@forEachIndexed
            notesByCall.getOrPut(callIndex) { mutableListOf() }.add(note)
            attachedIndexes += noteIndex
        }
        return timeline.mapIndexedNotNull { index, row ->
            if (row.kind == CallReportHistoryRowKind.NOTE && index in attachedIndexes) null
            else FilteredFullLogEntry(row, notesByCall[index].orEmpty().sortedBy { it.timeMs })
        }
    }

    private fun matchingCallIndex(
        note: CallReportHistoryRow,
        callIndexes: List<Int>,
        timeline: List<CallReportHistoryRow>,
    ): Int? {
        val notePhone = HomeCallPageLoader.noteKey(note.phone)
        var closestIndex: Int? = null
        var closestDelta = Long.MAX_VALUE
        callIndexes.forEach { callIndex ->
            val call = timeline[callIndex]
            if (HomeCallPageLoader.noteKey(call.phone) != notePhone) return@forEach
            if (note.direction.isNotBlank() && call.direction.isNotBlank() && note.direction != call.direction) return@forEach
            val delta = abs(note.timeMs - call.timeMs)
            if (delta <= NOTE_CALL_MATCH_WINDOW_MS && delta < closestDelta) {
                closestIndex = callIndex
                closestDelta = delta
            }
        }
        return closestIndex
    }

    private const val SOURCE_CALL_LIMIT = 200
    private const val SOURCE_SMS_LIMIT = 150
    private const val NOTE_CALL_MATCH_WINDOW_MS = 90_000L
}
