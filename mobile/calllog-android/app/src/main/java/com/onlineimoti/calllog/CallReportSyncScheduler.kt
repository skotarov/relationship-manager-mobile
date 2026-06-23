package com.onlineimoti.calllog

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/** Schedules a bounded, idempotent catch-up sync without delaying UI work. */
internal object CallReportSyncScheduler {
    private const val UNIQUE_WORK_NAME = "callreport_communication_sync"
    private const val INPUT_REASON = "reason"

    fun enqueueCatchUp(context: Context, reason: String, initialDelayMillis: Long = 0L) {
        val config = ConfigStore.load(context)
        if (!config.remoteEnabled || config.baseUrl.isBlank() || config.accessToken.isBlank()) return

        val request = OneTimeWorkRequestBuilder<CallReportSyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(Data.Builder().putString(INPUT_REASON, reason).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setInitialDelay(initialDelayMillis.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
            .addTag(UNIQUE_WORK_NAME)
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }
}
