package com.onlineimoti.calllog

import android.content.Context

/**
 * Builds the unfiltered Home timeline from the two device providers. Each source
 * is read only up to the end of the requested page in button mode. Automatic
 * scrolling uses a short-lived larger snapshot so pages never split a day.
 */
internal object HomeTimelineLoader {
    private const val CALL_BATCH_SIZE = 500
    private const val SMS_BATCH_SIZE = 100
    private const val CRM_TIMELINE_SCAN_LIMIT = 1_000
    private const val GROUPED_TIMELINE_SCAN_LIMIT = 2_000
    private const val GROUPED_TIMELINE_CACHE_MS = 30_000L

    private val groupedCacheLock = Any()
    private var groupedCache = TimedTimeline(0L, emptyList())

    fun page(context: Context, pageIndex: Int, pageSize: Int): List<PhoneCallRecord> {
        val safePageIndex = pageIndex.coerceAtLeast(0)
        val safePageSize = pageSize.coerceIn(5, 100)
        if (PageLoadingModeStore.usesPrefetch(context)) {
            return TimelineGroupedPager.page(
                items = groupedTimeline(context.applicationContext),
                pageIndex = safePageIndex,
                minimumPageSize = safePageSize,
                groupKey = { row -> TimelineGroupKeys.day(row.startedAt) },
            )
        }

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

    fun invalidateCache() {
        synchronized(groupedCacheLock) {
            groupedCache = TimedTimeline(0L, emptyList())
        }
    }

    private fun groupedTimeline(context: Context): List<PhoneCallRecord> {
        val now = System.currentTimeMillis()
        synchronized(groupedCacheLock) {
            if (now - groupedCache.loadedAtMs < GROUPED_TIMELINE_CACHE_MS) return groupedCache.rows
        }
        val loaded = mergedRows(context, GROUPED_TIMELINE_SCAN_LIMIT)
        synchronized(groupedCacheLock) {
            groupedCache = TimedTimeline(now, loaded)
        }
        return loaded
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

    private data class TimedTimeline(
        val loadedAtMs: Long,
        val rows: List<PhoneCallRecord>,
    )
}
