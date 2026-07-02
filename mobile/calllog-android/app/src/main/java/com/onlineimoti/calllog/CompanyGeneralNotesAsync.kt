package com.onlineimoti.calllog

import android.app.Activity
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

internal object CompanyGeneralNotesAsync {
    private val executor = Executors.newFixedThreadPool(2)
    private val mainHandler = Handler(Looper.getMainLooper())

    fun load(activity: Activity, config: AppConfig, phone: String, onLoaded: (List<CallReportCompanyMainNote>) -> Unit) {
        executor.execute {
            val notes = runCatching {
                CallReportCompanyGeneralNotesClient.fetch(activity.applicationContext, config, phone)
            }.getOrDefault(emptyList())
            mainHandler.post {
                if (!activity.isFinishing && !activity.isDestroyed) onLoaded(notes)
            }
        }
    }
}
