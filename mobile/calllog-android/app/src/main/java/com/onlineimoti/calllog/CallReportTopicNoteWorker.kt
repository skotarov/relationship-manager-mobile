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
                    notifyNotesChanged()
                    return@withContext Result.retry()
                }

                ServerRecordIndex.markConfirmed(applicationContext, confirmed)
                CallReportTopicNoteOutbox.acknowledge(applicationContext, confirmed)
                CallReportTopicNoteOutbox.clearFailure(applicationContext)
                notifyNotesChanged()
            }
            Result.success()
        } catch (error: Throwable) {
            val message = error.message.orEmpty().trim().ifBlank { "Няма връзка със сървъра." }
            CallReportTopicNoteOutbox.recordFailure(applicationContext, message)
            notifyNotesChanged()
            if (isCompanyAssignmentRejected(message)) Result.success() else Result.retry()
        }
    }

    private fun notifyNotesChanged() {
        applicationContext.sendBroadcast(
            Intent(PostCallOverlayService.ACTION_NOTES_CHANGED)
                .setPackage(applicationContext.packageName),
        )
    }

    /** A membership/firm validation will not heal through network backoff alone. */
    private fun isCompanyAssignmentRejected(message: String): Boolean {
        return message.contains("Нямате достъп до избраната фирма", ignoreCase = true) ||
            message.contains("Основната бележка трябва да е към фирма", ignoreCase = true)
    }

    private companion object {
        const val MAX_BATCH_SIZE = 50
    }
}
