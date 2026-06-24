package com.onlineimoti.calllog

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/** Backward-compatible WorkManager entry point delegating to the gated confirmation worker. */
class CallReportSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        return EnabledContactCommunicationSyncWorker(applicationContext, workerParams).doWork()
    }
}
