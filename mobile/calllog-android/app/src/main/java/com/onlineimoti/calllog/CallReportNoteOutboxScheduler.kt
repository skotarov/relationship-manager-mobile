package com.onlineimoti.calllog

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/** Schedules the persisted note outbox without blocking note editing or the Home UI. */
internal object CallReportNoteOutboxScheduler {
    private const val UNIQUE_WORK_NAME = "callreport_note_outbox_sync"

    fun enqueue(context: Context, reason: String = "unspecified") {
        val appContext = context.applicationContext
        if (!CallReportNoteOutbox.hasPending(appContext)) return
        val request = OneTimeWorkRequestBuilder<CallReportNoteOutboxWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(UNIQUE_WORK_NAME)
            .build()
        // Keep the in-flight worker. It always reads the latest coalesced outbox state.
        WorkManager.getInstance(appContext)
            .enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }
}
