package com.onlineimoti.calllog

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

/** Runs company phase reconciliation away from the UI thread. */
internal object CompanyNegotiationPhaseSyncDispatcher {
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun synchronize(context: Context, phone: String, companyId: String, onResolved: (Boolean) -> Unit) {
        val appContext = context.applicationContext
        if (
            phone.isBlank() ||
            companyId.isBlank() ||
            !CallReportRemoteAccess.isReady(ConfigStore.load(appContext))
        ) return
        executor.execute {
            val changed = runCatching {
                CompanyNegotiationPhaseSync.synchronize(appContext, phone, companyId)
            }.getOrDefault(false)
            mainHandler.post { onResolved(changed) }
        }
    }
}
