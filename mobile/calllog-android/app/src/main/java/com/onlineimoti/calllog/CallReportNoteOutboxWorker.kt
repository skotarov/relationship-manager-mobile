package com.onlineimoti.calllog

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CallReportNoteOutboxWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val config = ConfigStore.load(applicationContext)
        if (!config.remoteEnabled || config.baseUrl.isBlank() || config.accessToken.isBlank()) {
            return@withContext Result.success()
        }
        try {
            while (CallReportNoteOutbox.hasPending(applicationContext)) {
                val candidates = CallReportNoteOutbox.takeBatch(applicationContext, MAX_BATCH_SIZE)
                if (candidates.isEmpty()) break
                val batch = candidates.filter { CrmContactSyncStore.isEnabled(applicationContext, it.phone) }
                val skipped = candidates.map { it.clientEventId }.toSet() - batch.map { it.clientEventId }.toSet()
                if (skipped.isNotEmpty()) CallReportNoteOutbox.acknowledge(applicationContext, skipped)
                if (batch.isEmpty()) continue

                val confirmed = CallReportSyncClient.sync(config, batch.map { it.toSyncEvent(applicationContext) })
                val expected = batch.map { it.clientEventId }.toSet()
                if (!confirmed.containsAll(expected)) throw CallReportSyncException("Missing sync confirmations.", true)
                ServerRecordIndex.markConfirmed(applicationContext, confirmed)
                CallReportNoteOutbox.acknowledge(applicationContext, confirmed)
            }
            Result.success()
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
