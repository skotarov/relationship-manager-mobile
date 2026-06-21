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

/** Reads only the SMS rows for the requested number; it never walks the whole SMS inbox on the UI path. */
internal object SmsMessageReader {
    private const val MAX_MESSAGES_PER_CONTACT = 150

    fun messagesForPhone(context: Context, phone: String, limit: Int = MAX_MESSAGES_PER_CONTACT): List<SmsMessageRecord> {
        val targetPhone = PhoneNormalizer.normalize(phone)
        if (targetPhone.isBlank() || !canReadSms(context)) return emptyList()

        return runCatching {
            val exact = queryMessages(
                context = context,
                selection = "${Telephony.Sms.ADDRESS} IN (${phoneCandidates(targetPhone).joinToString(",") { "?" }})",
                selectionArgs = phoneCandidates(targetPhone).toTypedArray(),
                targetPhone = targetPhone,
                limit = limit,
            )
            if (exact.isNotEmpty()) {
                exact
            } else {
                // Some phones save an address with spaces or dashes. This fallback stays inside
                // the SMS provider instead of copying thousands of unrelated rows into the app.
                val lastDigits = targetPhone.filter { it.isDigit() }.takeLast(9)
                if (lastDigits.length < 7) emptyList() else queryMessages(
                    context = context,
                    selection = "${Telephony.Sms.ADDRESS} LIKE ?",
                    selectionArgs = arrayOf("%$lastDigits"),
                    targetPhone = targetPhone,
                    limit = limit,
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun queryMessages(
        context: Context,
        selection: String,
        selectionArgs: Array<String>,
        targetPhone: String,
        limit: Int,
    ): List<SmsMessageRecord> {
        val projection = arrayOf(
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.TYPE,
        )
        val rows = mutableListOf<SmsMessageRecord>()
        context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            "${Telephony.Sms.DATE} DESC",
        )?.use { cursor ->
            val addressIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val bodyIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val dateIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
            val typeIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.TYPE)
            while (cursor.moveToNext() && rows.size < limit) {
                val address = cursor.getString(addressIndex).orEmpty()
                if (!PhoneNormalizer.samePhone(address, targetPhone)) continue
                rows += SmsMessageRecord(
                    body = cursor.getString(bodyIndex).orEmpty().trim(),
                    timestampMs = cursor.getLong(dateIndex),
                    type = cursor.getInt(typeIndex),
                )
            }
        }
        return rows
    }

    private fun phoneCandidates(normalizedPhone: String): List<String> {
        val digits = normalizedPhone.filter { it.isDigit() }
        val lastNine = digits.takeLast(9)
        return linkedSetOf<String>().apply {
            add(normalizedPhone)
            add(digits)
            if (lastNine.length == 9) {
                add(lastNine)
                add("0$lastNine")
                add("359$lastNine")
                add("+359$lastNine")
            }
        }.filter { it.isNotBlank() }
    }

    private fun canReadSms(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
    }
}
