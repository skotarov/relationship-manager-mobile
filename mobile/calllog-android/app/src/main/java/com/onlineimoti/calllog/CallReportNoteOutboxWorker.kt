package com.onlineimoti.calllog

import android.content.Context
import android.content.Intent
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
        // Server-off mode is fully local. Do not contact the server and do not create a
        // visible failure status for retained pending notes.
        if (!CallReportRemoteAccess.isEnabled(config)) {
            return@withContext Result.success()
        }
        if (!CallReportRemoteAccess.isReady(config)) {
            CallReportNoteOutbox.recordFailure(applicationContext, "Липсва активна server конфигурация.")
            return@withContext Result.success()
        }
        try {
            while (CallReportNoteOutbox.hasPending(applicationContext)) {
                val candidates = CallReportNoteOutbox.takeBatch(applicationContext, MAX_BATCH_SIZE)
                if (candidates.isEmpty()) break
                val batch = candidates.filter { operation ->
                    operation.isGeneralNote || CrmContactSyncStore.isEnabled(applicationContext, operation.phone)
                }
                val skipped = candidates.map { it.clientEventId }.toSet() - batch.map { it.clientEventId }.toSet()
                if (skipped.isNotEmpty()) CallReportNoteOutbox.acknowledge(applicationContext, skipped)
                if (batch.isEmpty()) continue

                val confirmed = CallReportSyncClient.sync(config, batch.map { it.toSyncEvent(applicationContext) })
                val expected = batch.map { it.clientEventId }.toSet()
                if (!confirmed.containsAll(expected)) throw CallReportSyncException("Сървърът не потвърди всички бележки.", true)
                ServerRecordIndex.markConfirmed(applicationContext, confirmed)
                batch.asSequence()
                    .filter { it.clientEventId in confirmed && it.isGeneralNote }
                    .map { it.phone }
                    .distinct()
                    .forEach(CallReportHistoryLookupClient::markGeneralNoteOnServer)
                CallReportNoteOutbox.acknowledge(applicationContext, confirmed)
                CallReportNoteOutbox.clearFailure(applicationContext)
                notifyUiOfConfirmedSync()
            }
            Result.success()
        } catch (error: CallReportSyncException) {
            CallReportNoteOutbox.recordFailure(applicationContext, error.message.orEmpty())
            if (error.retryable) Result.retry() else Result.failure()
        } catch (error: Throwable) {
            CallReportNoteOutbox.recordFailure(applicationContext, "Неочаквана грешка при синхронизация.")
            Result.retry()
        }
    }

    private fun notifyUiOfConfirmedSync() {
        applicationContext.sendBroadcast(
            Intent(PostCallOverlayService.ACTION_NOTES_CHANGED)
                .setPackage(applicationContext.packageName),
        )
    }

    private companion object {
        const val MAX_BATCH_SIZE = 50
    }
}
