package com.onlineimoti.calllog

import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CallReportTopicNoteWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val config = ConfigStore.load(applicationContext)
        if (!CallReportRemoteAccess.isReady(config)) return@withContext Result.success()

        try {
            while (CallReportTopicNoteOutbox.hasPending(applicationContext)) {
                val batch = CallReportTopicNoteOutbox.takeBatch(applicationContext, MAX_BATCH_SIZE)
                if (batch.isEmpty()) break
                val confirmed = CallReportTopicSyncClient.sync(
                    config,
                    batch.map { it.toSyncEvent(applicationContext) },
                )
                val expected = batch.map { it.clientEventId }.toSet()
                if (!confirmed.containsAll(expected)) {
                    CallReportTopicNoteOutbox.recordFailure(
                        applicationContext,
                        "Сървърът не потвърди всички бележки. Отвори бележката и избери друга фирма, ако проблемът остане.",
                    )
                    return@withContext Result.retry()
                }

                ServerRecordIndex.markConfirmed(applicationContext, confirmed)
                CallReportTopicNoteOutbox.acknowledge(applicationContext, confirmed)
                CallReportTopicNoteOutbox.clearFailure(applicationContext)
                applicationContext.sendBroadcast(
                    Intent(PostCallOverlayService.ACTION_NOTES_CHANGED)
                        .setPackage(applicationContext.packageName),
                )
            }
            Result.success()
        } catch (error: Throwable) {
            CallReportTopicNoteOutbox.recordFailure(
                applicationContext,
                error.message.orEmpty().trim().ifBlank { "Няма връзка със сървъра." },
            )
            Result.retry()
        }
    }

    private companion object {
        const val MAX_BATCH_SIZE = 50
    }
}
