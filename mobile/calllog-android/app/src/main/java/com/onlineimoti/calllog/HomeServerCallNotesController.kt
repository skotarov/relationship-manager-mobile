package com.onlineimoti.calllog

import android.content.Context
import android.os.Handler
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Enriches one rendered Home page with the newest matching server call notes.
 * The request is batched for all visible phone numbers and stale page results are
 * discarded after search/filter/page changes.
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
                CallReportHistoryLookupClient.lookupMany(config, phones)
            }.getOrDefault(CallReportHistoryLookupResult())
            val mergedNotes = HomeCallNotesResolver.mergeWithServer(
                calls = renderData.calls,
                localNotes = renderData.callNotesByCall,
                serverEvents = history.events,
            )
            if (mergedNotes == renderData.callNotesByCall) return@execute
            val updated = renderData.copy(callNotesByCall = mergedNotes)
            handler.post {
                if (expectedGeneration == generation.get()) onUpdated(updated)
            }
        }
    }

    fun release() {
        generation.incrementAndGet()
        executor.shutdownNow()
    }
}
