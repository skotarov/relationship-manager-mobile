package com.onlineimoti.calllog

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

/**
 * Coalesces all Home-row requests made during one render into one notes_lookup batch.
 * The endpoint accepts at most 50 phones, matching the Home page size limit.
 */
internal object HomeServerNotesLoader {
    private const val BATCH_DELAY_MS = 80L
    private const val MAX_BATCH_SIZE = 50

    private data class Pending(
        val phone: String,
        val callbacks: MutableList<(CallReportServerNote?) -> Unit> = mutableListOf(),
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val lock = Any()
    private val pendingByKey = linkedMapOf<String, Pending>()
    private var flushScheduled = false

    fun load(context: Context, phone: String, callback: (CallReportServerNote?) -> Unit) {
        val appContext = context.applicationContext
        val config = ConfigStore.load(appContext)
        if (!config.remoteEnabled || config.baseUrl.isBlank() || config.accessToken.isBlank()) {
            callback(null)
            return
        }
        val normalizedKey = HomeCallPageLoader.noteKey(phone)
        if (normalizedKey.isBlank()) {
            callback(null)
            return
        }

        synchronized(lock) {
            pendingByKey.getOrPut(normalizedKey) { Pending(phone) }.callbacks += callback
            if (!flushScheduled) {
                flushScheduled = true
                mainHandler.postDelayed({ flush(appContext) }, BATCH_DELAY_MS)
            }
        }
    }

    private fun flush(appContext: Context) {
        val batch = synchronized(lock) {
            flushScheduled = false
            val items = pendingByKey.entries.take(MAX_BATCH_SIZE)
            items.forEach { entry -> pendingByKey.remove(entry.key) }
            if (pendingByKey.isNotEmpty() && !flushScheduled) {
                flushScheduled = true
                mainHandler.postDelayed({ flush(appContext) }, BATCH_DELAY_MS)
            }
            items.map { entry -> entry.key to entry.value }
        }
        if (batch.isEmpty()) return

        val config = ConfigStore.load(appContext)
        executor.execute {
            val notes = runCatching {
                CallReportNotesLookupClient.lookup(config, batch.map { (_, pending) -> pending.phone })
            }.getOrDefault(emptyMap())
            mainHandler.post {
                batch.forEach { (key, pending) ->
                    val note = notes[key]
                    pending.callbacks.forEach { callback -> callback(note) }
                }
            }
        }
    }
}
