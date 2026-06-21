package com.onlineimoti.calllog

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class PhoneCallRecord(
    val number: String,
    val name: String,
    val direction: String,
    val startedAt: Long,
    val durationSeconds: Long,
    val smsBody: String = "",
) {
    val displayName: String
        get() = name.ifBlank { number }

    val isSms: Boolean
        get() = direction == "sms_in" || direction == "sms_out"

    val smsDirectionLabel: String
        get() = if (direction == "sms_out") "изпратено" else "получено"
}

object PhoneCallReader {
    private val timeFormat = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())

    fun hasCallLogPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED
    }

    fun latestCall(context: Context): PhoneCallRecord? {
        return recentCalls(context, limit = 1).firstOrNull()
    }

    fun recentCalls(context: Context, limit: Int = 20, offset: Int = 0): List<PhoneCallRecord> {
        if (!hasCallLogPermission(context)) return emptyList()
        val safeLimit = limit.coerceIn(1, 500)
        val safeOffset = offset.coerceAtLeast(0)
        return runCatching { readRecentCalls(context, safeLimit, safeOffset, phoneFilter = "") }.getOrElse { emptyList() }
    }

    fun callsForPhone(context: Context, phone: String, limit: Int = 50, offset: Int = 0): List<PhoneCallRecord> {
        if (!hasCallLogPermission(context) || phone.isBlank()) return emptyList()
        val safeLimit = limit.coerceIn(1, 500)
        val safeOffset = offset.coerceAtLeast(0)
        return runCatching { readRecentCalls(context, safeLimit, safeOffset, phoneFilter = phone) }.getOrElse { emptyList() }
    }

    private fun readRecentCalls(
        context: Context,
        safeLimit: Int,
        safeOffset: Int,
        phoneFilter: String,
    ): List<PhoneCallRecord> {
        val projection = arrayOf(
            CallLog.Calls.NUMBER,
            CallLog.Calls.CACHED_NAME,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION,
        )
        val normalizedPhoneFilter = normalizePhone(phoneFilter)
        val directCandidates = if (normalizedPhoneFilter.isBlank()) emptyList() else phoneCandidates(phoneFilter)
        val directSelection = directCandidates.takeIf { it.isNotEmpty() }
            ?.let { "${CallLog.Calls.NUMBER} IN (${it.joinToString(",") { "?" }})" }

        val direct = queryCalls(
            context = context,
            projection = projection,
            safeLimit = safeLimit,
            safeOffset = safeOffset,
            normalizedPhoneFilter = normalizedPhoneFilter,
            selection = directSelection,
            selectionArgs = directCandidates.toTypedArray(),
        )
        if (normalizedPhoneFilter.isBlank() || direct.isNotEmpty()) return direct

        // Formatted numbers (spaces/dashes) are rare. Ask the provider for their final nine digits
        // instead of reading every call record and filtering it in the application.
        val suffix = normalizedPhoneFilter.takeLast(9)
        return if (suffix.length < 7) {
            emptyList()
        } else {
            queryCalls(
                context = context,
                projection = projection,
                safeLimit = safeLimit,
                safeOffset = safeOffset,
                normalizedPhoneFilter = normalizedPhoneFilter,
                selection = "${CallLog.Calls.NUMBER} LIKE ?",
                selectionArgs = arrayOf("%$suffix"),
            )
        }
    }

    private fun queryCalls(
        context: Context,
        projection: Array<String>,
        safeLimit: Int,
        safeOffset: Int,
        normalizedPhoneFilter: String,
        selection: String?,
        selectionArgs: Array<String>,
    ): List<PhoneCallRecord> {
        val sortOrder = "${CallLog.Calls.DATE} DESC"
        return buildList {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                projection,
                selection,
                selectionArgs.takeIf { it.isNotEmpty() },
                sortOrder,
            )?.use { cursor ->
                val numberIndex = cursor.getColumnIndex(CallLog.Calls.NUMBER)
                val nameIndex = cursor.getColumnIndex(CallLog.Calls.CACHED_NAME)
                val typeIndex = cursor.getColumnIndex(CallLog.Calls.TYPE)
                val dateIndex = cursor.getColumnIndex(CallLog.Calls.DATE)
                val durationIndex = cursor.getColumnIndex(CallLog.Calls.DURATION)

                var skipped = 0
                while (cursor.moveToNext() && size < safeLimit) {
                    val number = if (numberIndex >= 0) cursor.getString(numberIndex).orEmpty() else ""
                    if (number.isBlank()) continue
                    if (normalizedPhoneFilter.isNotBlank() && !samePhone(normalizedPhoneFilter, number)) continue
                    if (skipped < safeOffset) {
                        skipped += 1
                        continue
                    }

                    val cachedName = if (nameIndex >= 0) cursor.getString(nameIndex).orEmpty() else ""
                    val type = if (typeIndex >= 0) cursor.getInt(typeIndex) else 0
                    val startedAt = if (dateIndex >= 0) cursor.getLong(dateIndex) else 0L
                    val duration = if (durationIndex >= 0) cursor.getLong(durationIndex) else 0L
                    add(
                        PhoneCallRecord(
                            number = number,
                            name = cachedName,
                            direction = directionFromType(type),
                            startedAt = startedAt,
                            durationSeconds = duration,
                        ),
                    )
                }
            }
        }
    }

    fun directionLabel(direction: String): String {
        return when (direction) {
            "in" -> "входящ"
            "out" -> "изходящ"
            else -> "разговор"
        }
    }

    fun formatStartedAt(startedAt: Long): String {
        if (startedAt <= 0L) return ""
        return timeFormat.format(Date(startedAt))
    }

    fun formatDuration(seconds: Long): String {
        if (seconds <= 0L) return "0 сек"
        val minutes = seconds / 60
        val restSeconds = seconds % 60
        return if (minutes > 0) "${minutes}м ${restSeconds}с" else "${restSeconds}с"
    }

    private fun directionFromType(type: Int): String {
        return when (type) {
            CallLog.Calls.INCOMING_TYPE,
            CallLog.Calls.MISSED_TYPE,
            CallLog.Calls.REJECTED_TYPE,
            CallLog.Calls.BLOCKED_TYPE -> "in"
            CallLog.Calls.OUTGOING_TYPE -> "out"
            else -> "in"
        }
    }

    private fun samePhone(normalizedPhone: String, candidate: String): Boolean {
        val normalizedCandidate = normalizePhone(candidate)
        if (normalizedPhone.isBlank() || normalizedCandidate.isBlank()) return false
        return normalizedPhone == normalizedCandidate || normalizedPhone.endsWith(normalizedCandidate) || normalizedCandidate.endsWith(normalizedPhone)
    }

    private fun phoneCandidates(value: String): List<String> {
        val digits = value.filter { it.isDigit() }
        val lastNine = digits.takeLast(9)
        return linkedSetOf<String>().apply {
            add(value.trim())
            add(digits)
            if (lastNine.length == 9) {
                add(lastNine)
                add("0$lastNine")
                add("359$lastNine")
                add("+359$lastNine")
            }
        }.filter { it.isNotBlank() }
    }

    private fun normalizePhone(phone: String): String {
        val digits = phone.filter { it.isDigit() }
        return if (digits.length > 9) digits.takeLast(9) else digits
    }
}
