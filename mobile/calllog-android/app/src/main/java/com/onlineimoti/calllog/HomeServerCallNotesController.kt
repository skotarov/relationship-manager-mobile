package com.onlineimoti.calllog

import android.content.Context
import android.os.Handler
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

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
            if (!isServerGeneralNote(event)) return@forEach
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

    private fun isServerGeneralNote(event: CallReportHistoryEvent): Boolean {
        if (!event.communicationType.equals("note", ignoreCase = true) || event.note.isBlank()) return false
        return event.clientEventId.contains(":note:general:") ||
            event.clientEventId.contains(":topic:general:") ||
            (event.direction.isBlank() && event.durationSeconds <= 0L)
    }
}
