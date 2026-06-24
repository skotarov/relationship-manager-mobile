package com.onlineimoti.calllog

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Uploads only Android call and SMS events whose phone is explicitly enabled for server sync.
 * It rechecks eligibility immediately before each HTTP request.
 */
class EnabledContactCommunicationSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val config = ConfigStore.load(applicationContext)
        if (!config.remoteEnabled || config.baseUrl.isBlank() || config.accessToken.isBlank()) {
            return@withContext Result.success()
        }

        val events = buildList {
            CallReportProviderEventReader.recentPhoneEvents(applicationContext, CALL_SYNC_LIMIT)
                .filter { call -> CrmContactSyncStore.isEnabled(applicationContext, call.phone) }
                .mapNotNullTo(this) { call -> CallReportSyncEventFactory.fromPhoneCall(applicationContext, call) }
            CallReportProviderEventReader.recentSmsEvents(applicationContext, SMS_SYNC_LIMIT)
                .filter { sms -> CrmContactSyncStore.isEnabled(applicationContext, sms.phone) }
                .mapNotNullTo(this) { sms -> CallReportSyncEventFactory.fromSms(applicationContext, sms) }
        }
            .distinctBy { event -> event.clientEventId }
            .sortedByDescending { event -> event.occurredAtMs }

        if (events.isEmpty()) return@withContext Result.success()

        try {
            events.chunked(MAX_BATCH_SIZE).forEach { candidates ->
                val enabledNow = candidates.filter { event ->
                    CrmContactSyncStore.isEnabled(applicationContext, event.phone)
                }
                if (enabledNow.isNotEmpty()) CallReportSyncClient.sync(config, enabledNow)
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
