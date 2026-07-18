package com.onlineimoti.calllog

import android.content.Context
import android.os.Handler
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Enriches one rendered Home page using stale-while-revalidate. Cached server
 * notes are emitted first, then a successful network result updates the cache
 * and only re-renders when the visible data really changed.
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
        val expectedGeneration = generation.get()
        val phones = renderData.calls
            .filterNot { it.isSms }
            .map { it.number }
            .distinctBy(HomeCallPageLoader::noteKey)
        if (phones.isEmpty()) return

        executor.execute {
            var lastVisible = renderData
            fun emitIfChanged(candidate: HomeRenderData) {
                if (candidate == lastVisible) return
                lastVisible = candidate
                handler.post {
                    if (expectedGeneration == generation.get()) onUpdated(candidate)
                }
            }

            val cachedHistory = HomeServerNotesCacheStore.snapshot(appContext, phones)
            emitIfChanged(mergeHistory(renderData, cachedHistory))

            val config = ConfigStore.load(appContext)
            if (!CallReportRemoteAccess.isReady(config)) return@execute
            val remote = runCatching {
                CallReportHistoryLookupClient.lookupMany(config, phones, appContext)
            }.getOrDefault(CallReportHistoryLookupResult())
            if (!remote.requestSuccessful) return@execute

            HomeServerNotesCacheStore.update(appContext, remote)
            val refreshedHistory = HomeServerNotesCacheStore.snapshot(appContext, phones)
            emitIfChanged(mergeHistory(renderData, refreshedHistory))
        }
    }

    fun release() {
        generation.incrementAndGet()
        executor.shutdownNow()
    }

    private fun mergeHistory(
        renderData: HomeRenderData,
        history: CallReportHistoryLookupResult,
    ): HomeRenderData {
        val localCallNotes = renderData.callNotesByCall.filterValues { !it.fromServer }
        val localGeneralNotes = renderData.contactNotesByNumber.filterValues { !ServerNoteVisuals.isServerText(it) }
        return renderData.copy(
            callNotesByCall = HomeCallNotesResolver.mergeWithServer(
                calls = renderData.calls,
                localNotes = localCallNotes,
                serverEvents = history.events,
                principal = history.principal,
            ),
            contactNotesByNumber = mergeServerGeneralNotes(
                calls = renderData.calls,
                existing = localGeneralNotes,
                serverEvents = history.events,
            ),
        )
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
            // Yellow on Home is allowed only for explicit general records.
            if (!CallReportServerNoteClassifier.isExplicitGeneralNote(event)) return@forEach
            val key = HomeCallPageLoader.noteKey(event.phone)
            if (key.isBlank() || key !in requestedKeys) return@forEach
            val note = event.note.trim()
            if (note.isBlank()) return@forEach
            val changedAt = maxOf(event.updatedAtMs, event.createdAtMs, event.occurredAtMs)
            val current = latest[key]
            if (current == null || changedAt >= current.first) latest[key] = changedAt to ServerNoteVisuals.prefixed(note)
        }
        if (latest.isEmpty()) return existing

        val merged = existing.toMutableMap()
        latest.forEach { (key, value) ->
            // Local/general notes still win while editing offline.
            if (merged[key].isNullOrBlank()) merged[key] = value.second
        }
        return merged
    }
}
