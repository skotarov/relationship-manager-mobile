package com.onlineimoti.calllog

import android.content.Context
import android.os.Bundle
import android.provider.CallLog

object RecentCallsRepository {
    private val projection = arrayOf(
        CallLog.Calls._ID,
        CallLog.Calls.NUMBER,
        CallLog.Calls.CACHED_NAME,
        CallLog.Calls.DATE,
        CallLog.Calls.DURATION,
        CallLog.Calls.TYPE,
    )

    fun loadPage(context: Context, offset: Int, limit: Int): RecentCallPage {
        return runCatching {
            val items = mutableListOf<RecentCallItem>()
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                projection,
                queryArgs(offset, limit + 1),
                null,
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(CallLog.Calls._ID)
                val numberIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
                val nameIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME)
                val dateIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.DATE)
                val durationIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.DURATION)
                val typeIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.TYPE)

                while (cursor.moveToNext()) {
                    items += RecentCallItem(
                        id = cursor.getLong(idIndex),
                        number = cursor.getString(numberIndex).orEmpty().trim(),
                        displayName = cursor.getString(nameIndex).orEmpty().trim(),
                        callDateMs = cursor.getLong(dateIndex),
                        durationSeconds = cursor.getLong(durationIndex),
                        callType = cursor.getInt(typeIndex),
                    )
                }
            }
            val hasMore = items.size > limit
            val visibleItems = if (hasMore) items.take(limit) else items
            RecentCallPage(
                items = visibleItems,
                hasMore = hasMore,
            )
        }.getOrElse {
            RecentCallPage(emptyList(), hasMore = false)
        }
    }

    fun findLatestMatchingCall(context: Context, number: String, sinceMs: Long): RecentCallItem? {
        val targetDigits = phoneLastDigits(number)
        if (targetDigits.isBlank()) {
            return null
        }

        return runCatching {
            var match: RecentCallItem? = null
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                projection,
                queryArgs(offset = 0, limit = 25),
                null,
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(CallLog.Calls._ID)
                val numberIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
                val nameIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME)
                val dateIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.DATE)
                val durationIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.DURATION)
                val typeIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.TYPE)

                while (cursor.moveToNext()) {
                    val callDateMs = cursor.getLong(dateIndex)
                    if (callDateMs < sinceMs) {
                        continue
                    }

                    val loggedNumber = cursor.getString(numberIndex).orEmpty().trim()
                    if (phoneLastDigits(loggedNumber) != targetDigits) {
                        continue
                    }

                    match = RecentCallItem(
                        id = cursor.getLong(idIndex),
                        number = loggedNumber,
                        displayName = cursor.getString(nameIndex).orEmpty().trim(),
                        callDateMs = callDateMs,
                        durationSeconds = cursor.getLong(durationIndex),
                        callType = cursor.getInt(typeIndex),
                    )
                    break
                }
            }
            match
        }.getOrNull()
    }

    private fun queryArgs(offset: Int, limit: Int): Bundle {
        return Bundle().apply {
            putStringArray(
                android.content.ContentResolver.QUERY_ARG_SORT_COLUMNS,
                arrayOf(CallLog.Calls.DATE)
            )
            putInt(
                android.content.ContentResolver.QUERY_ARG_SORT_DIRECTION,
                android.content.ContentResolver.QUERY_SORT_DIRECTION_DESCENDING
            )
            putInt(android.content.ContentResolver.QUERY_ARG_LIMIT, limit)
            putInt(android.content.ContentResolver.QUERY_ARG_OFFSET, offset)
        }
    }
}
