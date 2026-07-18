package com.onlineimoti.calllog

import android.content.Context
import android.os.Handler
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Adds server notes to Home rows and keeps prefetch blocked until they are applied. */
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
        prefetchToken: Int? = null,
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
        prefetchToken?.let(HomePageEnrichmentState::serverStarted)

        runCatching {
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
                val updated = if (
                    mergedNotes == renderData.callNotesByCall &&
                    mergedGeneralNotes == renderData.contactNotesByNumber
                ) null else renderData.copy(
                    contactNotesByNumber = mergedGeneralNotes,
                    callNotesByCall = mergedNotes,
                )
                handler.post {
                    if (expectedGeneration == generation.get() && updated != null) onUpdated(updated)
                    prefetchToken?.let(HomePageEnrichmentState::serverComplete)
                }
            }
        }.onFailure {
            prefetchToken?.let(HomePageEnrichmentState::serverComplete)
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
