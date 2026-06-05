package com.onlineimoti.calllog

import android.content.Context
import java.util.concurrent.Executors

internal object CrmNoteSyncer {
    private val executor = Executors.newSingleThreadExecutor()

    fun syncGeneralIfEnabled(context: Context, phone: String, note: String) {
        if (!CrmContactSyncStore.isEnabled(context, phone) || note.trim().isBlank()) return
        val appContext = context.applicationContext
        val config = ConfigStore.load(appContext)
        executor.execute {
            runCatching { CrmNoteSaveClient.saveGeneralNote(config, phone, note) }
        }
    }

    fun syncCallIfEnabled(
        context: Context,
        phone: String,
        note: String,
        direction: String,
        callAt: Long,
        durationSeconds: Long,
    ) {
        if (!CrmContactSyncStore.isEnabled(context, phone) || note.trim().isBlank()) return
        val appContext = context.applicationContext
        val config = ConfigStore.load(appContext)
        executor.execute {
            runCatching {
                CrmNoteSaveClient.saveCallNote(
                    config = config,
                    phone = phone,
                    note = note,
                    direction = direction,
                    callAt = callAt,
                    durationSeconds = durationSeconds,
                )
            }
        }
    }
}
