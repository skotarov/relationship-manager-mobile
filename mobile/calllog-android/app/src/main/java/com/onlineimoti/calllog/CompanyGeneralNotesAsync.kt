package com.onlineimoti.calllog

import android.app.Activity
import android.os.AsyncTask

internal object CompanyGeneralNotesAsync {
    @Suppress("DEPRECATION")
    fun load(activity: Activity, config: AppConfig, phone: String, onLoaded: (List<CallReportCompanyMainNote>) -> Unit) {
        AsyncTask.execute {
            val notes = runCatching {
                CallReportCompanyGeneralNotesClient.fetch(activity.applicationContext, config, phone)
            }.getOrDefault(emptyList())
            activity.runOnUiThread {
                if (!activity.isFinishing && !activity.isDestroyed) onLoaded(notes)
            }
        }
    }
}
