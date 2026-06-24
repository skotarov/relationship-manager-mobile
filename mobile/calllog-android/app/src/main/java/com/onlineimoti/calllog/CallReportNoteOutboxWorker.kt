package com.onlineimoti.calllog

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Sends persisted note operations only after Android reports a usable network. */
class CallReportNoteOutboxWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val config = ConfigStore.load(applicationContext)
        // Keep the durable queue intact until server configuration is completed again.
        if (!config.remoteEnabled || config.baseUrl.isBlank() || config.accessToken.isBlank()) {
            return@withContext Result.success()
        }

        try {
            while (true) {
                val batch = CallReportNoteOutbox.takeBatch(applicationContext, MAX_BATCH_SIZE)
                if (batch.isEmpty()) return@withContext Result.success()
                CallReportSyncClient.sync(config, batch.map { operation -> operation.toSyncEvent(applicationContext) })
                CallReportNoteOutbox.acknowledge(applicationContext, batch.map { operation -> operation.clientEventId })
            }
        } catch (error: CallReportSyncException) {
            if (error.retryable) Result.retry() else Result.failure()
        } catch (_: Throwable) {
            Result.retry()
        }
    }

    private companion object {
        const val MAX_BATCH_SIZE = 50
    }
}
