package com.onlineimoti.calllog

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import androidx.core.content.ContextCompat

object LocalCallStatsProvider {
    private const val LOCAL_CALL_SCAN_LIMIT = 5000
    private const val CURRENT_CALL_PROTECTION_WINDOW_MS = 30_000L

    fun buildLine(context: Context, phone: String): String {
        val summary = summarize(context, phone) ?: return ""
        return when {
            summary.count <= 0 -> "Няма предишни разговори"
            summary.lastCallAgo.isBlank() -> "Провеждани разговори: ${summary.count}"
            else -> "Провеждани разговори: ${summary.count} · последен: ${summary.lastCallAgo}"
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
        )
    }

    private fun getStats(context: Context, phone: String): LocalCallStats {
        val digits = normalizePhone(phone)
        if (digits.isBlank()) {
            return LocalCallStats(count = 0, lastCallAtMillis = null)
        }

        val projection = arrayOf(
            CallLog.Calls.NUMBER,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION,
        )
        val sortOrder = "${CallLog.Calls.DATE} DESC"
        val now = System.currentTimeMillis()
        var count = 0
        var lastCallAtMillis: Long? = null
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

            while (cursor.moveToNext() && scanned < LOCAL_CALL_SCAN_LIMIT) {
                scanned += 1
                val callNumber = if (numberIndex >= 0) cursor.getString(numberIndex).orEmpty() else ""
                if (!samePhone(digits, callNumber)) {
                    continue
                }

                val callAtMillis = if (dateIndex >= 0) cursor.getLong(dateIndex) else 0L
                val durationSeconds = if (durationIndex >= 0) cursor.getLong(durationIndex) else 0L
                val looksLikeCurrentCall = callAtMillis > 0L &&
                    now - callAtMillis in 0..CURRENT_CALL_PROTECTION_WINDOW_MS &&
                    durationSeconds <= 0L
                if (looksLikeCurrentCall) {
                    continue
                }

                count += 1
                if (lastCallAtMillis == null && callAtMillis > 0L) {
                    lastCallAtMillis = callAtMillis
                }
            }
        }

        return LocalCallStats(count = count, lastCallAtMillis = lastCallAtMillis)
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
    )

    private data class LocalCallStats(
        val count: Int,
        val lastCallAtMillis: Long?,
    )
}
