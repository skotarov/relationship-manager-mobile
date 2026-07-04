package com.onlineimoti.calllog

import android.content.Context

/**
 * Builds the unfiltered Home timeline from the two device providers. Each source
 * is read only up to the end of the requested page; the resulting rows are then
 * merged newest-first, so pagination counts calls and SMS together.
 */
internal object HomeTimelineLoader {
    private const val CALL_BATCH_SIZE = 500
    private const val SMS_BATCH_SIZE = 100
    private const val CRM_TIMELINE_SCAN_LIMIT = 1_000

    fun page(context: Context, pageIndex: Int, pageSize: Int): List<PhoneCallRecord> {
        val safePageIndex = pageIndex.coerceAtLeast(0)
        val safePageSize = pageSize.coerceIn(5, 100)
        val endExclusive = ((safePageIndex + 1).toLong() * safePageSize.toLong())
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
        val timeline = mergedRows(context, endExclusive)
        return timeline.drop(safePageIndex * safePageSize).take(safePageSize)
    }

    /** Same chronological data set for CRM before its existing filters are applied. */
    fun crmCandidates(context: Context): List<PhoneCallRecord> {
        val timeline = mergedRows(context, CRM_TIMELINE_SCAN_LIMIT)
        val eligibleKeys = HomeCallPageLoader.crmEligiblePhoneKeys(context, timeline.map { it.number })
        return timeline.filter { call -> HomeCallPageLoader.noteKey(call.number) in eligibleKeys }
    }

    private fun mergedRows(context: Context, wanted: Int): List<PhoneCallRecord> {
        return (readCalls(context, wanted) + readSms(context, wanted))
            .sortedByDescending { it.startedAt }
    }

    private fun readCalls(context: Context, wanted: Int): List<PhoneCallRecord> {
        if (wanted <= 0) return emptyList()
        val rows = mutableListOf<PhoneCallRecord>()
        var offset = 0
        while (rows.size < wanted) {
            val requested = minOf(CALL_BATCH_SIZE, wanted - rows.size)
            val batch = PhoneCallReader.recentCalls(context, limit = requested, offset = offset)
            if (batch.isEmpty()) break
            rows += batch
            offset += batch.size
            if (batch.size < requested) break
        }
        return rows
    }

    private fun readSms(context: Context, wanted: Int): List<PhoneCallRecord> {
        if (wanted <= 0) return emptyList()
        val rows = mutableListOf<PhoneCallRecord>()
        var offset = 0
        while (rows.size < wanted) {
            val requested = minOf(SMS_BATCH_SIZE, wanted - rows.size)
            val batch = SmsMessageReader.recentMessages(context, offset = offset, limit = requested)
            if (batch.isEmpty()) break
            rows += batch.mapNotNull { message ->
                message.address.takeIf { it.isNotBlank() }?.let { address ->
                    PhoneCallRecord(
                        number = address,
                        name = "",
                        direction = if (message.isOutgoing) "sms_out" else "sms_in",
                        startedAt = message.timestampMs,
                        durationSeconds = 0L,
                        smsBody = message.body,
                        providerId = message.providerId,
                    )
                }
            }
            offset += batch.size
            if (batch.size < requested) break
        }
        return rows
    }
}
