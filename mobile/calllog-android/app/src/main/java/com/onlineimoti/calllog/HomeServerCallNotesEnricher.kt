package com.onlineimoti.calllog

import android.content.Context
import android.os.Handler
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Adds matching server and pending company notes to one rendered Home page. */
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
        onFinished: () -> Unit = {},
        onUpdated: (HomeRenderData) -> Unit,
    ) {
        if (renderData.calls.isEmpty()) {
            handler.post(onFinished)
            return
        }
        val config = ConfigStore.load(appContext)
        if (!CallReportRemoteAccess.isReady(config)) {
            handler.post(onFinished)
            return
        }
        val expectedGeneration = generation.get()
        val phones = renderData.calls
            .filterNot { it.isSms }
            .map { it.number }
            .distinctBy(HomeCallPageLoader::noteKey)
        if (phones.isEmpty()) {
            handler.post(onFinished)
            return
        }

        executor.execute {
            val history = runCatching {
                CallReportHistoryLookupClient.lookupMany(config, phones, appContext)
            }.getOrDefault(CallReportHistoryLookupResult())
            // Pending operations come last. Their newer timestamp wins immediately;
            // an empty pending note acts as a tombstone until the server confirms it.
            val combinedEvents = history.events + CompanyCallNoteOutbox.pendingEvents(appContext, phones)
            val updated = renderData.copy(
                contactNotesByNumber = mergeServerGeneralNotes(
                    calls = renderData.calls,
                    existing = renderData.contactNotesByNumber,
                    serverEvents = history.events,
                ),
                callNotesByCall = HomeCallNotesResolver.mergeWithServer(
                    calls = renderData.calls,
                    localNotes = renderData.callNotesByCall,
                    serverEvents = combinedEvents,
                    principal = history.principal,
                ),
            )
            handler.post {
                if (expectedGeneration != generation.get()) return@post
                if (updated != renderData) onUpdated(updated)
                onFinished()
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
            if (!CallReportServerNoteClassifier.isExplicitGeneralNote(event)) return@forEach
            val key = HomeCallPageLoader.noteKey(event.phone)
            if (key.isBlank() || key !in requestedKeys) return@forEach
            val note = event.note.trim()
            if (note.isBlank()) return@forEach
            val changedAt = maxOf(event.updatedAtMs, event.createdAtMs, event.occurredAtMs)
            val current = latest[key]
            if (current == null || changedAt >= current.first) {
                latest[key] = changedAt to ServerNoteVisuals.prefixed(note)
            }
        }

        val merged = existing.toMutableMap()
        requestedKeys.forEach { key ->
            if (ServerNoteVisuals.isPrefixed(merged[key].orEmpty())) merged.remove(key)
        }
        latest.forEach { (key, value) ->
            if (merged[key].isNullOrBlank()) merged[key] = value.second
        }
        return merged
    }
}
