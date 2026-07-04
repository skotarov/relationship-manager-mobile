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
    val providerId: String = "",
) {
    val isOutgoing: Boolean
        get() = type == Telephony.Sms.MESSAGE_TYPE_SENT || type == Telephony.Sms.MESSAGE_TYPE_OUTBOX

    val directionLabel: String
        get() = if (isOutgoing) "изпратено" else "получено"
}

/** A single row from the device-wide SMS timeline. */
internal data class SmsTimelineMessage(
    val address: String,
    val body: String,
    val timestampMs: Long,
    val type: Int,
    val providerId: String,
) {
    val isOutgoing: Boolean
        get() = type == Telephony.Sms.MESSAGE_TYPE_SENT || type == Telephony.Sms.MESSAGE_TYPE_OUTBOX

    val directionLabel: String
        get() = if (isOutgoing) "изпратено" else "получено"
}

/** Reads only the SMS rows needed by the current screen or search request. */
internal object SmsMessageReader {
    private const val MAX_MESSAGES_PER_CONTACT = 150

    fun hasReadSmsPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
    }

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

    /**
     * Searches the device SMS store by message text and sender/recipient number.
     * It uses provider filtering and then normalizes digits locally so formatted
     * Bulgarian phone numbers can still be found by a plain numeric query.
     */
    fun searchMessages(context: Context, query: String, limit: Int): List<SmsTimelineMessage> {
        val trimmedQuery = query.trim()
        if (trimmedQuery.isBlank() || !canReadSms(context)) return emptyList()
        val safeLimit = limit.coerceIn(1, 500)
        val digits = trimmedQuery.filter(Char::isDigit)
        val addressNeedle = when {
            digits.length >= 9 -> digits.takeLast(9)
            digits.isNotBlank() -> digits
            else -> trimmedQuery
        }
        val selection = "${Telephony.Sms.BODY} LIKE ? OR ${Telephony.Sms.ADDRESS} LIKE ?"
        val selectionArgs = arrayOf("%$trimmedQuery%", "%$addressNeedle%")
        return runCatching {
            queryTimelineMessages(
                context = context,
                selection = selection,
                selectionArgs = selectionArgs,
                limit = safeLimit,
            ).filter { message ->
                message.body.contains(trimmedQuery, ignoreCase = true) ||
                    (digits.isNotBlank() && message.address.filter(Char::isDigit).contains(digits)) ||
                    (digits.isBlank() && message.address.contains(trimmedQuery, ignoreCase = true))
            }
        }.getOrDefault(emptyList())
    }

    /**
     * Reads a single chronological window from the device SMS store. The caller
     * requests one extra row to determine whether a next page exists.
     */
    fun recentMessages(context: Context, offset: Int, limit: Int): List<SmsTimelineMessage> {
        if (!canReadSms(context)) return emptyList()
        val safeOffset = offset.coerceAtLeast(0)
        val safeLimit = limit.coerceIn(1, 101)
        return runCatching {
            val projection = timelineProjection()
            val rows = mutableListOf<SmsTimelineMessage>()
            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                projection,
                null,
                null,
                "${Telephony.Sms.DATE} DESC",
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(Telephony.Sms._ID)
                val addressIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                val bodyIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
                val dateIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
                val typeIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.TYPE)
                var skipped = 0
                while (cursor.moveToNext()) {
                    if (skipped < safeOffset) {
                        skipped++
                        continue
                    }
                    rows += cursor.timelineMessage(idIndex, addressIndex, bodyIndex, dateIndex, typeIndex)
                    if (rows.size >= safeLimit) break
                }
            }
            rows
        }.getOrDefault(emptyList())
    }

    private fun queryTimelineMessages(
        context: Context,
        selection: String,
        selectionArgs: Array<String>,
        limit: Int,
    ): List<SmsTimelineMessage> {
        val rows = mutableListOf<SmsTimelineMessage>()
        context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            timelineProjection(),
            selection,
            selectionArgs,
            "${Telephony.Sms.DATE} DESC",
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(Telephony.Sms._ID)
            val addressIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val bodyIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val dateIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
            val typeIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.TYPE)
            while (cursor.moveToNext() && rows.size < limit) {
                rows += cursor.timelineMessage(idIndex, addressIndex, bodyIndex, dateIndex, typeIndex)
            }
        }
        return rows
    }

    private fun timelineProjection(): Array<String> = arrayOf(
        Telephony.Sms._ID,
        Telephony.Sms.ADDRESS,
        Telephony.Sms.BODY,
        Telephony.Sms.DATE,
        Telephony.Sms.TYPE,
    )

    private fun android.database.Cursor.timelineMessage(
        idIndex: Int,
        addressIndex: Int,
        bodyIndex: Int,
        dateIndex: Int,
        typeIndex: Int,
    ): SmsTimelineMessage = SmsTimelineMessage(
        address = getString(addressIndex).orEmpty(),
        body = getString(bodyIndex).orEmpty().trim(),
        timestampMs = getLong(dateIndex),
        type = getInt(typeIndex),
        providerId = getLong(idIndex).toString(),
    )

    private fun queryMessages(
        context: Context,
        selection: String,
        selectionArgs: Array<String>,
        targetPhone: String,
        limit: Int,
    ): List<SmsMessageRecord> {
        val projection = timelineProjection()
        val rows = mutableListOf<SmsMessageRecord>()
        context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            "${Telephony.Sms.DATE} DESC",
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(Telephony.Sms._ID)
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
                    providerId = cursor.getLong(idIndex).toString(),
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

    private fun canReadSms(context: Context): Boolean = hasReadSmsPermission(context)
}
