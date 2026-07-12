package com.onlineimoti.calllog

import android.content.Context
import android.os.Handler
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/**
 * Enriches one rendered Home page with the newest matching server notes. The
 * visible Call Log rows are shown first; this controller then adds server blue
 * call notes and yellow/general notes just like the History screen already does.
 */
internal class HomeServerCallNotesController(
    context: Context,
    private val handler: Handler,
) {
    private val appContext = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor()
    private val generation = AtomicInteger(0)

    fun invalidate() {
        generation.incrementAndGet()
    }

    fun enrichAsync(
        renderData: HomeRenderData,
        onUpdated: (HomeRenderData) -> Unit,
    ) {
        if (renderData.calls.isEmpty()) return
        val config = ConfigStore.load(appContext)
        if (!CallReportRemoteAccess.isReady(config)) return
        val expectedGeneration = generation.get()
        val phones = renderData.calls
            .filterNot { it.isSms }
            .map { it.number }
            .distinctBy(HomeCallPageLoader::noteKey)
        if (phones.isEmpty()) return

        executor.execute {
            val history = runCatching {
                CallReportHistoryLookupClient.lookupMany(config, phones, appContext)
            }.getOrDefault(CallReportHistoryLookupResult())
            val mergedNotes = HomeCallNotesResolver.mergeWithServer(
                calls = renderData.calls,
                localNotes = renderData.callNotesByCall,
                serverEvents = history.events,
                principal = history.principal,
            )
            val mergedGeneralNotes = mergeServerGeneralNotes(
                calls = renderData.calls,
                existing = renderData.contactNotesByNumber,
                serverEvents = history.events,
            )
            if (mergedNotes == renderData.callNotesByCall && mergedGeneralNotes == renderData.contactNotesByNumber) {
                return@execute
            }
            val updated = renderData.copy(
                contactNotesByNumber = mergedGeneralNotes,
                callNotesByCall = mergedNotes,
            )
            handler.post {
                if (expectedGeneration == generation.get()) onUpdated(updated)
            }
        }
    }

    fun release() {
        generation.incrementAndGet()
        executor.shutdownNow()
    }

    private fun mergeServerGeneralNotes(
        calls: List<PhoneCallRecord>,
        existing: Map<String, String>,
        serverEvents: List<CallReportHistoryEvent>,
    ): Map<String, String> {
        val requestedKeys = calls
            .filterNot { it.isSms }
            .mapTo(linkedSetOf()) { HomeCallPageLoader.noteKey(it.number) }
            .filterTo(linkedSetOf()) { it.isNotBlank() }
        if (requestedKeys.isEmpty()) return existing

        val latest = linkedMapOf<String, Pair<Long, String>>()
        serverEvents.forEach { event ->
            if (!CallReportServerNoteClassifier.isGeneralNote(event)) return@forEach
            if (!CallReportServerNoteClassifier.isExplicitGeneralNote(event) && matchesVisibleCall(calls, event)) return@forEach
            val key = HomeCallPageLoader.noteKey(event.phone)
            if (key.isBlank() || key !in requestedKeys) return@forEach
            val note = event.note.trim()
            if (note.isBlank()) return@forEach
            val changedAt = maxOf(event.updatedAtMs, event.createdAtMs, event.occurredAtMs)
            val current = latest[key]
            if (current == null || changedAt >= current.first) latest[key] = changedAt to note
        }
        if (latest.isEmpty()) return existing

        val merged = existing.toMutableMap()
        latest.forEach { (key, value) ->
            // Local/general notes still win while editing offline; server fills the
            // blank rows that History can already display.
            if (merged[key].isNullOrBlank()) merged[key] = value.second
        }
        return merged
    }

    private fun matchesVisibleCall(calls: List<PhoneCallRecord>, event: CallReportHistoryEvent): Boolean {
        if (!event.communicationType.equals("note", ignoreCase = true) || event.occurredAtMs <= 0L) return false
        val eventKey = HomeCallPageLoader.noteKey(event.phone)
        if (eventKey.isBlank()) return false
        return calls.filterNot { it.isSms }.any { call ->
            HomeCallPageLoader.noteKey(call.number) == eventKey &&
                call.startedAt > 0L &&
                abs(call.startedAt - event.occurredAtMs) <= SERVER_NOTE_CALL_MATCH_WINDOW_MS &&
                (call.direction.isBlank() || event.direction.isBlank() || call.direction == event.direction)
        }
    }

    private companion object {
        const val SERVER_NOTE_CALL_MATCH_WINDOW_MS = 10 * 60 * 1000L
    }
}
