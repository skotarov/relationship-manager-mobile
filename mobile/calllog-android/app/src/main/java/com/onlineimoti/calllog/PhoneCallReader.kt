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
) {
    val displayName: String
        get() = name.ifBlank { number }
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
        if (!hasCallLogPermission(context)) {
            return emptyList()
        }

        val safeLimit = limit.coerceIn(1, 50)
        val safeOffset = offset.coerceAtLeast(0)
        return runCatching {
            readRecentCalls(context, safeLimit, safeOffset)
        }.getOrElse {
            emptyList()
        }
    }

    private fun readRecentCalls(context: Context, safeLimit: Int, safeOffset: Int): List<PhoneCallRecord> {
        val projection = arrayOf(
            CallLog.Calls.NUMBER,
            CallLog.Calls.CACHED_NAME,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION,
        )
        val sortOrder = "${CallLog.Calls.DATE} DESC"

        return buildList {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                projection,
                null,
                null,
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
                    if (number.isBlank()) {
                        continue
                    }
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
                        )
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
        if (startedAt <= 0L) {
            return ""
        }
        return timeFormat.format(Date(startedAt))
    }

    fun formatDuration(seconds: Long): String {
        if (seconds <= 0L) {
            return "0 сек"
        }
        val minutes = seconds / 60
        val restSeconds = seconds % 60
        return if (minutes > 0) {
            "${minutes}м ${restSeconds}с"
        } else {
            "${restSeconds}с"
        }
    }

    private fun directionFromType(type: Int): String {
        return when (type) {
            CallLog.Calls.INCOMING_TYPE -> "in"
            CallLog.Calls.OUTGOING_TYPE -> "out"
            else -> ""
        }
    }
}
