package com.onlineimoti.calllog

import android.content.Context
import java.util.concurrent.Executors

internal object CrmNoteSyncer {
    private val executor = Executors.newSingleThreadExecutor()

    fun syncGeneralIfEnabled(context: Context, phone: String, note: String) {
        if (!CrmContactSyncStore.isEnabled(context, phone) || note.trim().isBlank()) return
        val appContext = context.applicationContext
        val config = ConfigStore.load(appContext)
        val clientNoteId = "general-${phone.normalizePhoneKey()}"
        executor.execute {
            runCatching { CrmNoteSaveWithClientIdClient.saveGeneral(config, phone, note, clientNoteId) }
        }
    }

    fun syncCallIfEnabled(
        context: Context,
        phone: String,
        note: String,
        direction: String,
        callAt: Long,
        durationSeconds: Long,
        clientNoteId: String = "",
    ) {
        if (!CrmContactSyncStore.isEnabled(context, phone) || note.trim().isBlank()) return
        val appContext = context.applicationContext
        val config = ConfigStore.load(appContext)
        val effectiveClientNoteId = clientNoteId.ifBlank { LocalNotesFileStore.clientNoteIdForCall(phone, callAt, direction) }
        executor.execute {
            runCatching {
                CrmNoteSaveWithClientIdClient.saveCall(
                    config = config,
                    phone = phone,
                    note = note,
                    direction = direction,
                    callAt = callAt,
                    durationSeconds = durationSeconds,
                    clientNoteId = effectiveClientNoteId,
                )
            }
        }
    }

    private fun String.normalizePhoneKey(): String = filter { it.isDigit() }.let { if (it.length > 9) it.takeLast(9) else it }
}
