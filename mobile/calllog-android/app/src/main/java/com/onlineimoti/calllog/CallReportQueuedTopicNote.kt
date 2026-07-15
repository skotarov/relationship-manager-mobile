package com.onlineimoti.calllog

import android.content.Context

internal data class CallReportQueuedTopicNote(
    val clientEventId: String,
    val companyId: String,
    val phone: String,
    val direction: String,
    val occurredAtMs: Long,
    val durationSeconds: Long,
    val note: String,
    val contactName: String,
    val updatedAtMs: Long,
    val communicationType: String = "note",
    val clearCompanyAssignment: Boolean = false,
) {
    fun toSyncEvent(context: Context) = CallReportTopicSyncEvent(
        clientEventId = clientEventId,
        companyId = companyId,
        phone = phone,
        direction = direction,
        occurredAtMs = occurredAtMs,
        durationSeconds = durationSeconds,
        note = note,
        contactName = contactName,
        deviceId = CallReportInstallationId.get(context),
        appVersion = BuildConfig.VERSION_NAME,
        communicationType = communicationType,
        clearCompanyAssignment = clearCompanyAssignment,
    )
}
