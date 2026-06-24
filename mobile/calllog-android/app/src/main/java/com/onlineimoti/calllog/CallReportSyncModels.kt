package com.onlineimoti.calllog

import android.content.Context
import java.util.UUID

/** Canonical Android representation of one server-side communication event. */
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
    /** null means do not alter a server note; an empty string explicitly clears it. */
    val note: String? = null,
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
    fun fromPhoneCall(context: Context, call: CallReportProviderEventReader.PhoneEvent): CallReportSyncEvent? =
        fromProviderEvent(
            context = context,
            type = "phone",
            providerId = call.providerId,
            phone = call.phone,
            contactName = call.contactName,
            direction = call.direction,
            status = call.status,
            occurredAtMs = call.occurredAtMs,
            durationSeconds = call.durationSeconds,
        )

    fun fromSms(context: Context, sms: CallReportProviderEventReader.SmsEvent): CallReportSyncEvent? =
        fromProviderEvent(
            context = context,
            type = "sms",
            providerId = sms.providerId,
            phone = sms.phone,
            contactName = "",
            direction = sms.direction,
            status = sms.status,
            occurredAtMs = sms.occurredAtMs,
            durationSeconds = 0L,
        )

    private fun fromProviderEvent(
        context: Context,
        type: String,
        providerId: String,
        phone: String,
        contactName: String,
        direction: String,
        status: String,
        occurredAtMs: Long,
        durationSeconds: Long,
    ): CallReportSyncEvent? {
        if (providerId.isBlank() || phone.isBlank() || occurredAtMs <= 0L) return null
        // The per-contact switch is the sole authorization for sending communication metadata.
        if (!CrmContactSyncStore.isEnabled(context.applicationContext, phone)) return null
        val deviceId = CallReportInstallationId.get(context)
        val resolvedName = contactName.trim().ifBlank {
            ContactGroupFilter.resolveDisplayName(context, phone).orEmpty().trim()
        }
        return CallReportSyncEvent(
            clientEventId = "$deviceId:$type:$providerId",
            communicationType = type,
            direction = direction,
            status = status,
            phone = phone,
            contactName = resolvedName,
            occurredAtMs = occurredAtMs,
            durationSeconds = durationSeconds.coerceAtLeast(0L),
            providerRowId = providerId,
            deviceId = deviceId,
            appVersion = BuildConfig.VERSION_NAME,
        )
    }

    fun latestPhoneCallContext(context: Context, phone: String, direction: String): CallReportLookupContext {
        val latest = CallReportProviderEventReader.recentPhoneEvents(context, 8)
            .firstOrNull { item -> PhoneNormalizer.samePhone(item.phone, phone) && (direction.isBlank() || item.direction == direction) }
        if (latest != null) return fromPhoneCall(context, latest)?.toLookupContext() ?: CallReportLookupContext(
            communicationType = "phone",
            status = latest.status,
            contactName = latest.contactName,
            occurredAtMs = latest.occurredAtMs,
            durationSeconds = latest.durationSeconds,
        )
        return CallReportLookupContext(
            communicationType = "phone",
            contactName = ContactGroupFilter.resolveDisplayName(context, phone).orEmpty().trim(),
        )
    }
}
