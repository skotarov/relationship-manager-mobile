package com.onlineimoti.calllog

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

/** Runs phase reconciliation away from the UI and redraws only when server wins a conflict. */
internal object ContactNegotiationPhaseSyncDispatcher {
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun synchronize(context: Context, phone: String, onResolved: (Boolean) -> Unit) {
        val appContext = context.applicationContext
        if (phone.isBlank() || !CallReportRemoteAccess.isReady(ConfigStore.load(appContext))) return
        executor.execute {
            val changed = runCatching {
                ContactNegotiationPhaseSync.synchronize(appContext, phone)
            }.getOrDefault(false)
            mainHandler.post { onResolved(changed) }
        }
    }
}
