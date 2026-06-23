package com.onlineimoti.calllog

import android.content.Context
import java.util.UUID

/**
 * Canonical Android representation of one server-side communication event.
 * The provider row id is part of clientEventId so a catch-up sync is idempotent.
 */
internal data class CallReportSyncEvent(
    val clientEventId: String,
    val communicationType: String,
    val direction: String,
    val status: String,
    val phone: String,
    val contactName: String,
    val occurredAtMs: Long,
    val durationSeconds: Long,
    val providerRowId: String,
    val deviceId: String,
    val appVersion: String,
) {
    fun toLookupContext(): CallReportLookupContext = CallReportLookupContext(
        communicationType = communicationType,
        status = status,
        contactName = contactName,
        occurredAtMs = occurredAtMs,
        durationSeconds = durationSeconds,
        clientEventId = clientEventId,
    )
}

internal data class CallReportLookupContext(
    val communicationType: String = "phone",
    val status: String = "",
    val contactName: String = "",
    val occurredAtMs: Long = 0L,
    val durationSeconds: Long = 0L,
    val clientEventId: String = "",
) {
    fun asQueryParameters(): LinkedHashMap<String, String> = linkedMapOf(
        "communication_type" to communicationType,
        "status" to status,
        "contact_name" to contactName,
        "call_at" to occurredAtMs.takeIf { it > 0L }?.toString().orEmpty(),
        "occurred_at_ms" to occurredAtMs.takeIf { it > 0L }?.toString().orEmpty(),
        "duration" to durationSeconds.takeIf { it > 0L }?.toString().orEmpty(),
        "client_event_id" to clientEventId,
    )
}

internal object CallReportInstallationId {
    private const val PREFS = "callreport_sync"
    private const val KEY_INSTALLATION_ID = "installation_id"

    fun get(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = prefs.getString(KEY_INSTALLATION_ID, "").orEmpty()
        if (current.isNotBlank()) return current
        return UUID.randomUUID().toString().also { value ->
            prefs.edit().putString(KEY_INSTALLATION_ID, value).apply()
        }
    }
}

internal object CallReportSyncEventFactory {
    fun fromPhoneCall(context: Context, call: PhoneCallRecord): CallReportSyncEvent? {
        val providerId = call.providerId.trim()
        val phone = call.number.trim()
        if (providerId.isBlank() || phone.isBlank() || call.startedAt <= 0L) return null
        val deviceId = CallReportInstallationId.get(context)
        val contactName = call.name.trim().ifBlank {
            ContactGroupFilter.resolveDisplayName(context, phone).orEmpty().trim()
        }
        return CallReportSyncEvent(
            clientEventId = "$deviceId:phone:$providerId",
            communicationType = "phone",
            direction = call.direction.ifBlank { "in" },
            status = call.status.ifBlank { "answered" },
            phone = phone,
            contactName = contactName,
            occurredAtMs = call.startedAt,
            durationSeconds = call.durationSeconds.coerceAtLeast(0L),
            providerRowId = providerId,
            deviceId = deviceId,
            appVersion = BuildConfig.VERSION_NAME,
        )
    }

    fun fromSms(context: Context, sms: SmsMessageRecord): CallReportSyncEvent? {
        val providerId = sms.providerId.trim()
        val phone = sms.address.trim()
        if (providerId.isBlank() || phone.isBlank() || sms.timestampMs <= 0L) return null
        val deviceId = CallReportInstallationId.get(context)
        val contactName = ContactGroupFilter.resolveDisplayName(context, phone).orEmpty().trim()
        return CallReportSyncEvent(
            clientEventId = "$deviceId:sms:$providerId",
            communicationType = "sms",
            direction = if (sms.isOutgoing) "out" else "in",
            status = sms.status,
            phone = phone,
            contactName = contactName,
            occurredAtMs = sms.timestampMs,
            durationSeconds = 0L,
            providerRowId = providerId,
            deviceId = deviceId,
            appVersion = BuildConfig.VERSION_NAME,
        )
    }

    fun latestPhoneCallContext(context: Context, phone: String, direction: String): CallReportLookupContext {
        val latest = PhoneCallReader.callsForPhone(context, phone, limit = 5)
            .firstOrNull { item -> direction.isBlank() || item.direction == direction }
        if (latest != null) {
            return fromPhoneCall(context, latest)?.toLookupContext()
                ?: CallReportLookupContext(
                    communicationType = "phone",
                    status = latest.status,
                    contactName = latest.name.trim(),
                    occurredAtMs = latest.startedAt,
                    durationSeconds = latest.durationSeconds,
                )
        }
        return CallReportLookupContext(
            communicationType = "phone",
            contactName = ContactGroupFilter.resolveDisplayName(context, phone).orEmpty().trim(),
        )
    }
}
