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
                val candidateBatch = CallReportNoteOutbox.takeBatch(applicationContext, MAX_BATCH_SIZE)
                if (candidateBatch.isEmpty()) return@withContext Result.success()

                // The setting may be switched off between queue selection and the HTTP request.
                val batch = candidateBatch.filter { operation ->
                    CrmContactSyncStore.isEnabled(applicationContext, operation.phone)
                }
                val skippedIds = candidateBatch.map { operation -> operation.clientEventId }.toSet() - batch.map { operation -> operation.clientEventId }.toSet()
                if (skippedIds.isNotEmpty()) {
                    CallReportNoteOutbox.acknowledge(applicationContext, skippedIds)
                }
                if (batch.isEmpty()) continue

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
