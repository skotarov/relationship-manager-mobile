package com.onlineimoti.calllog

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import androidx.core.content.ContextCompat

object LocalCallStatsProvider {
    private const val LOCAL_CALL_SCAN_LIMIT = 5000
    private const val CURRENT_CALL_PROTECTION_WINDOW_MS = 30_000L
    private const val ICON_FAILED_CALL = "✕"
    private const val ICON_GENERAL_NOTE = "☰"
    private const val ICON_CALL_NOTE = "💬"
    private const val ICON_INCOMING = "↙"
    private const val ICON_OUTGOING = "↗"

    fun buildLine(context: Context, phone: String): String {
        return buildPopupInfoRows(context, phone).firstOrNull().orEmpty()
    }

    fun buildPopupInfoRows(context: Context, phone: String): List<String> {
        val summary = summarize(context, phone)
        val contactNote = ContactNoteReader.noteForPhone(context, phone)
            .trim()
            .replace(Regex("\\s+"), " ")
        val latestCallNote = ContactNoteReader.callNotesForPhone(context, phone)
            .firstOrNull()
            ?.note
            .orEmpty()
            .trim()
            .replace(Regex("\\s+"), " ")

        return buildList {
            if (summary != null && summary.count > 0 && summary.lastCallAgo.isNotBlank()) {
                add(callInfoLine(summary))
            }
            if (contactNote.isNotBlank()) {
                add("$ICON_GENERAL_NOTE $contactNote")
            }
            if (latestCallNote.isNotBlank()) {
                add("$ICON_CALL_NOTE $latestCallNote")
            }
        }
    }

    fun summarize(context: Context, phone: String): LocalCallSummary? {
        if (phone.isBlank()) {
            return null
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) {
            return null
        }

        val stats = runCatching { getStats(context, phone) }.getOrNull() ?: return null
        val lastCallAgo = stats.lastCallAtMillis?.let { timestamp ->
            formatAgo(System.currentTimeMillis() - timestamp)
        }.orEmpty()

        return LocalCallSummary(
            count = stats.count,
            lastCallAgo = lastCallAgo,
            lastCallType = stats.lastCallType,
            lastDurationSeconds = stats.lastDurationSeconds,
            directionIcon = directionIcon(stats.lastCallType),
            failedIcon = failedIcon(stats.lastCallType, stats.lastDurationSeconds),
        )
    }

    private fun callInfoLine(summary: LocalCallSummary): String {
        return listOf(summary.directionIcon, summary.failedIcon, summary.lastCallAgo)
            .filter { it.isNotBlank() }
            .joinToString(" ")
    }

    private fun getStats(context: Context, phone: String): LocalCallStats {
        val digits = normalizePhone(phone)
        if (digits.isBlank()) {
            return LocalCallStats(count = 0, lastCallAtMillis = null, lastCallType = null, lastDurationSeconds = null)
        }

        val projection = arrayOf(
            CallLog.Calls.NUMBER,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION,
            CallLog.Calls.TYPE,
        )
        val sortOrder = "${CallLog.Calls.DATE} DESC"
        val now = System.currentTimeMillis()
        var count = 0
        var lastCallAtMillis: Long? = null
        var lastCallType: Int? = null
        var lastDurationSeconds: Long? = null
        var scanned = 0

        context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            projection,
            null,
            null,
            sortOrder,
        )?.use { cursor ->
            val numberIndex = cursor.getColumnIndex(CallLog.Calls.NUMBER)
            val dateIndex = cursor.getColumnIndex(CallLog.Calls.DATE)
            val durationIndex = cursor.getColumnIndex(CallLog.Calls.DURATION)
            val typeIndex = cursor.getColumnIndex(CallLog.Calls.TYPE)

            while (cursor.moveToNext() && scanned < LOCAL_CALL_SCAN_LIMIT) {
                scanned += 1
                val callNumber = if (numberIndex >= 0) cursor.getString(numberIndex).orEmpty() else ""
                if (!samePhone(digits, callNumber)) {
                    continue
                }

                val callAtMillis = if (dateIndex >= 0) cursor.getLong(dateIndex) else 0L
                val durationSeconds = if (durationIndex >= 0) cursor.getLong(durationIndex) else 0L
                val callType = if (typeIndex >= 0) cursor.getInt(typeIndex) else null
                val looksLikeCurrentCall = callAtMillis > 0L &&
                    now - callAtMillis in 0..CURRENT_CALL_PROTECTION_WINDOW_MS &&
                    durationSeconds <= 0L
                if (looksLikeCurrentCall) {
                    continue
                }

                count += 1
                if (lastCallAtMillis == null && callAtMillis > 0L) {
                    lastCallAtMillis = callAtMillis
                    lastCallType = callType
                    lastDurationSeconds = durationSeconds
                }
            }
        }

        return LocalCallStats(
            count = count,
            lastCallAtMillis = lastCallAtMillis,
            lastCallType = lastCallType,
            lastDurationSeconds = lastDurationSeconds,
        )
    }

    private fun directionIcon(callType: Int?): String {
        return when (callType) {
            CallLog.Calls.OUTGOING_TYPE -> ICON_OUTGOING
            else -> ICON_INCOMING
        }
    }

    private fun failedIcon(callType: Int?, durationSeconds: Long?): String {
        return when (callType) {
            CallLog.Calls.MISSED_TYPE,
            CallLog.Calls.REJECTED_TYPE,
            CallLog.Calls.BLOCKED_TYPE -> ICON_FAILED_CALL
            CallLog.Calls.OUTGOING_TYPE -> if ((durationSeconds ?: 0L) <= 0L) ICON_FAILED_CALL else ""
            else -> if ((durationSeconds ?: 1L) <= 0L) ICON_FAILED_CALL else ""
        }
    }

    private fun formatAgo(diffMs: Long): String {
        val safeDiffMs = diffMs.coerceAtLeast(0L)
        val minuteMs = 60_000L
        val hourMs = 60L * minuteMs
        val dayMs = 24L * hourMs
        val weekMs = 7L * dayMs
        val monthMs = 30L * dayMs
        val yearMs = 365L * dayMs

        return when {
            safeDiffMs < hourMs -> formatAgoUnit((safeDiffMs / minuteMs).coerceAtLeast(1L), "минута", "минути")
            safeDiffMs < dayMs -> formatAgoUnit((safeDiffMs / hourMs).coerceAtLeast(1L), "час", "часа")
            safeDiffMs < 14L * dayMs -> formatAgoUnit((safeDiffMs / dayMs).coerceAtLeast(1L), "ден", "дни")
            safeDiffMs < 8L * weekMs -> formatAgoUnit((safeDiffMs / weekMs).coerceAtLeast(1L), "седмица", "седмици")
            safeDiffMs < 24L * monthMs -> formatAgoUnit((safeDiffMs / monthMs).coerceAtLeast(1L), "месец", "месеца")
            else -> formatAgoUnit((safeDiffMs / yearMs).coerceAtLeast(1L), "година", "години")
        }
    }

    private fun formatAgoUnit(value: Long, singular: String, plural: String): String {
        val unit = if (value == 1L) singular else plural
        return "преди $value $unit"
    }

    private fun samePhone(normalizedPhone: String, candidate: String): Boolean {
        val normalizedCandidate = normalizePhone(candidate)
        if (normalizedPhone.isBlank() || normalizedCandidate.isBlank()) {
            return false
        }
        return normalizedPhone == normalizedCandidate ||
            normalizedPhone.endsWith(normalizedCandidate) ||
            normalizedCandidate.endsWith(normalizedPhone)
    }

    private fun normalizePhone(phone: String): String {
        val digits = phone.filter { it.isDigit() }
        return if (digits.length > 9) digits.takeLast(9) else digits
    }

    data class LocalCallSummary(
        val count: Int,
        val lastCallAgo: String,
        val lastCallType: Int? = null,
        val lastDurationSeconds: Long? = null,
        val directionIcon: String = ICON_INCOMING,
        val failedIcon: String = "",
    )

    private data class LocalCallStats(
        val count: Int,
        val lastCallAtMillis: Long?,
        val lastCallType: Int?,
        val lastDurationSeconds: Long?,
    )
}