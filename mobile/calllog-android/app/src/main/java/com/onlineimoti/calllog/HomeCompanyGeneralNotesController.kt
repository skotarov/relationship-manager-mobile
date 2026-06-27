package com.onlineimoti.calllog

import android.content.Context
import android.os.Handler
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Refreshes Home company-note labels without delaying the call-log rows. */
internal class HomeCompanyGeneralNotesController(
    private val context: Context,
    private val handler: Handler,
    private val onChanged: () -> Unit,
) {
    private val executor = Executors.newSingleThreadExecutor()
    private val generation = AtomicInteger(0)
    private var requestSignature = ""
    private var labelsByPhoneKey: Map<String, String> = emptyMap()

    fun labelsFor(calls: List<PhoneCallRecord>): Map<String, String> {
        val keys = calls.map { HomeCallPageLoader.noteKey(it.number) }.filter { it.isNotBlank() }.toSet()
        return labelsByPhoneKey.filterKeys { it in keys }
    }

    fun refresh(calls: List<PhoneCallRecord>) {
        val config = ConfigStore.load(context.applicationContext)
        val phones = calls
            .map { it.number }
            .filter { HomeCallPageLoader.noteKey(it).isNotBlank() }
            .distinctBy { HomeCallPageLoader.noteKey(it) }
            .take(20)
        val nextSignature = listOf(
            config.remoteEnabled.toString(),
            config.baseUrl,
            config.accessToken,
            phones.joinToString("|") { HomeCallPageLoader.noteKey(it) },
        ).joinToString("#")
        if (nextSignature == requestSignature) return
        requestSignature = nextSignature

        if (!CallReportRemoteAccess.isReady(config) || phones.isEmpty()) {
            if (labelsByPhoneKey.isNotEmpty()) {
                labelsByPhoneKey = emptyMap()
                onChanged()
            }
            return
        }

        val currentGeneration = generation.incrementAndGet()
        executor.execute {
            val labels = runCatching { HomeCompanyGeneralNoteLabels.fetch(config, phones) }.getOrDefault(emptyMap())
            handler.post {
                if (currentGeneration != generation.get()) return@post
                labelsByPhoneKey = labels
                onChanged()
            }
        }
    }

    fun invalidate() {
        requestSignature = ""
        generation.incrementAndGet()
    }

    fun release() {
        generation.incrementAndGet()
        executor.shutdownNow()
    }
}
