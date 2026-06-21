package com.onlineimoti.calllog

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Telephony
import androidx.core.content.ContextCompat

internal data class SmsMessageRecord(
    val body: String,
    val timestampMs: Long,
    val type: Int,
) {
    val isOutgoing: Boolean
        get() = type == Telephony.Sms.MESSAGE_TYPE_SENT || type == Telephony.Sms.MESSAGE_TYPE_OUTBOX

    val directionLabel: String
        get() = if (isOutgoing) "изпратено" else "получено"
}

/** Reads the device SMS provider only when Android has granted SMS access. */
internal object SmsMessageReader {
    private const val MAX_MESSAGES_PER_CONTACT = 150
    private const val MAX_SCANNED_ROWS = 10_000

    fun messagesForPhone(context: Context, phone: String, limit: Int = MAX_MESSAGES_PER_CONTACT): List<SmsMessageRecord> {
        val targetPhone = PhoneNormalizer.normalize(phone)
        if (targetPhone.isBlank()) return emptyList()
        if (!canReadSms(context)) return emptyList()

        val projection = arrayOf(
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.TYPE,
        )
        return runCatching {
            val rows = mutableListOf<SmsMessageRecord>()
            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                projection,
                null,
                null,
                "${Telephony.Sms.DATE} DESC",
            )?.use { cursor ->
                val addressIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                val bodyIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
                val dateIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
                val typeIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.TYPE)
                var scanned = 0
                while (cursor.moveToNext() && scanned < MAX_SCANNED_ROWS && rows.size < limit) {
                    scanned += 1
                    val address = cursor.getString(addressIndex).orEmpty()
                    if (!PhoneNormalizer.samePhone(address, targetPhone)) continue
                    rows += SmsMessageRecord(
                        body = cursor.getString(bodyIndex).orEmpty().trim(),
                        timestampMs = cursor.getLong(dateIndex),
                        type = cursor.getInt(typeIndex),
                    )
                }
            }
            rows
        }.getOrDefault(emptyList())
    }

    private fun canReadSms(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
    }
}
