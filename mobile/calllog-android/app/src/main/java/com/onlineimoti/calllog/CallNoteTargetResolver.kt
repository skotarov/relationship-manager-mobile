package com.onlineimoti.calllog

import android.content.Context

internal data class CallNoteTarget(
    val direction: String,
    val callAt: Long,
    val durationSeconds: Long,
) {
    val hasCall: Boolean get() = callAt > 0L
}

internal object CallNoteTargetResolver {
    const val EXTRA_ACTION_ISSUED_AT = "call_note_action_issued_at"
    private const val MATCH_BEFORE_ACTION_MS = 30_000L
    private const val MATCH_AFTER_CALL_END_MS = 2 * 60 * 1000L

    fun resolve(
        context: Context,
        phone: String,
        directionHint: String,
        callAtHint: Long,
        durationHint: Long,
        actionIssuedAt: Long = 0L,
    ): CallNoteTarget {
        if (phone.isBlank()) return CallNoteTarget(directionHint, callAtHint, durationHint)

        if (callAtHint > 0L) {
            return CallNoteTarget(directionHint, callAtHint, durationHint)
        }

        if (CallLifecycleStore.isActive(context, phone)) {
            return CallNoteTarget(directionHint, 0L, 0L)
        }

        val latestCall = PhoneCallReader.callsForPhone(context, phone, limit = 1).firstOrNull()
        if (latestCall != null && latestCall.startedAt > 0L && isAcceptablePostCallMatch(latestCall, actionIssuedAt)) {
            return CallNoteTarget(
                direction = latestCall.direction.ifBlank { directionHint },
                callAt = latestCall.startedAt,
                durationSeconds = latestCall.durationSeconds,
            )
        }

        return CallNoteTarget(directionHint, 0L, 0L)
    }

    private fun isAcceptablePostCallMatch(call: PhoneCallRecord, actionIssuedAt: Long): Boolean {
        if (actionIssuedAt <= 0L) return true
        val safeAfter = actionIssuedAt - MATCH_BEFORE_ACTION_MS
        if (call.startedAt >= safeAfter) return true
        val estimatedEndedAt = call.startedAt + call.durationSeconds.coerceAtLeast(0L) * 1000L
        return estimatedEndedAt > 0L && estimatedEndedAt + MATCH_AFTER_CALL_END_MS >= actionIssuedAt
    }
}
