package com.onlineimoti.calllog

import android.provider.CallLog

data class ServerHistorySnippet(
    val lastDigits: String,
    val latestNote: String,
    val latestNoteAt: String,
    val latestNoteTimestampMs: Long,
    val latestNoteDirection: String,
    val latestNoteContact: String,
    val latestNotePropertyId: String,
    val latestNotePropertyTitle: String,
    val latestContact: String,
    val latestPropertyTitle: String,
    val entryCount: Int,
)

data class RecentCallItem(
    val id: Long,
    val number: String,
    val displayName: String,
    val callDateMs: Long,
    val durationSeconds: Long,
    val callType: Int,
    val serverHistory: ServerHistorySnippet? = null,
) {
    val directionSlug: String
        get() = when (callType) {
            CallLog.Calls.OUTGOING_TYPE -> "out"
            else -> "in"
        }

    val displayLabel: String
        get() = displayName.ifBlank {
            number.ifBlank { "Скрит номер" }
        }

    val lastDigits: String
        get() = phoneLastDigits(number)

    val isMissedLike: Boolean
        get() = callType == CallLog.Calls.MISSED_TYPE ||
            callType == CallLog.Calls.REJECTED_TYPE ||
            callType == CallLog.Calls.BLOCKED_TYPE
}

data class RecentCallPage(
    val items: List<RecentCallItem>,
    val hasMore: Boolean,
)
