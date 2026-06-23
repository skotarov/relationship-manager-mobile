package com.onlineimoti.calllog

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads only a bounded recent window from Android's providers. The server de-duplicates
 * by client_event_id, so retries and later catch-up runs remain safe.
 */
class CallReportSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val config = ConfigStore.load(applicationContext)
        if (!config.remoteEnabled || config.baseUrl.isBlank() || config.accessToken.isBlank()) {
            return@withContext Result.success()
        }

        val events = buildList {
            PhoneCallReader.recentCalls(applicationContext, limit = CALL_SYNC_LIMIT)
                .mapNotNullTo(this) { call -> CallReportSyncEventFactory.fromPhoneCall(applicationContext, call) }
            SmsMessageReader.recentMessages(applicationContext, limit = SMS_SYNC_LIMIT)
                .mapNotNullTo(this) { sms -> CallReportSyncEventFactory.fromSms(applicationContext, sms) }
        }
            .distinctBy { event -> event.clientEventId }
            .sortedByDescending { event -> event.occurredAtMs }

        if (events.isEmpty()) return@withContext Result.success()

        try {
            events.chunked(MAX_BATCH_SIZE).forEach { batch ->
                CallReportSyncClient.sync(config, batch)
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
