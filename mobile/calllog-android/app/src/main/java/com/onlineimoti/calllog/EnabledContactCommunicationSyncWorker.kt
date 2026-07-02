package com.onlineimoti.calllog

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class EnabledContactCommunicationSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        // A stale scheduled job must not read or upload call history after the
        // company session expires or a user signs out of the public Play build.
        if (BuildConfig.IS_PLAY_DISTRIBUTION && !EnterpriseAccessGate.isReady(applicationContext)) {
            return@withContext Result.success()
        }

        val config = ConfigStore.load(applicationContext)
        if (!config.remoteEnabled || config.baseUrl.isBlank() || config.accessToken.isBlank()) {
            return@withContext Result.success()
        }

        // The acknowledgement index is a cache, not source data; trim legacy growth first.
        ServerRecordIndex.prune(applicationContext)

        val events = buildList {
            CallReportProviderEventReader.recentPhoneEvents(applicationContext, CALL_SYNC_LIMIT)
                .filter { CrmContactSyncStore.isEnabled(applicationContext, it.phone) }
                .mapNotNullTo(this) { CallReportSyncEventFactory.fromPhoneCall(applicationContext, it) }
            CallReportProviderEventReader.recentSmsEvents(applicationContext, SMS_SYNC_LIMIT)
                .filter { CrmContactSyncStore.isEnabled(applicationContext, it.phone) }
                .mapNotNullTo(this) { CallReportSyncEventFactory.fromSms(applicationContext, it) }
        }.distinctBy { it.clientEventId }.sortedByDescending { it.occurredAtMs }

        try {
            events.chunked(MAX_BATCH_SIZE).forEach { candidates ->
                val batch = candidates.filter { CrmContactSyncStore.isEnabled(applicationContext, it.phone) }
                if (batch.isNotEmpty()) {
                    val confirmed = CallReportSyncClient.sync(config, batch)
                    ServerRecordIndex.markConfirmed(applicationContext, confirmed)
                }
            }
            Result.success()
        } catch (error: CallReportSyncException) {
            if (error.retryable && runAttemptCount < MAX_RETRY_ATTEMPTS) Result.retry() else Result.failure()
        } catch (_: Throwable) {
            if (runAttemptCount < MAX_RETRY_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    private companion object {
        const val CALL_SYNC_LIMIT = 100
        const val SMS_SYNC_LIMIT = 100
        const val MAX_BATCH_SIZE = 50
        const val MAX_RETRY_ATTEMPTS = 5
    }
}
