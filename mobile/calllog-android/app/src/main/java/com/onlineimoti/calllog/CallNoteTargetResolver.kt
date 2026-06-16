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

    fun resolve(
        context: Context,
        phone: String,
        directionHint: String,
        callAtHint: Long,
        durationHint: Long,
        actionIssuedAt: Long = 0L,
    ): CallNoteTarget {
        if (phone.isBlank()) return CallNoteTarget(directionHint, callAtHint, durationHint)

        val safeAfter = if (actionIssuedAt > 0L) actionIssuedAt - MATCH_BEFORE_ACTION_MS else 0L

        if (callAtHint > 0L && (safeAfter <= 0L || callAtHint >= safeAfter)) {
            return CallNoteTarget(directionHint, callAtHint, durationHint)
        }

        if (CallLifecycleStore.isActive(context, phone)) {
            return CallNoteTarget(directionHint, 0L, 0L)
        }

        val latestCall = PhoneCallReader.callsForPhone(context, phone, limit = 1).firstOrNull()
        if (latestCall != null && latestCall.startedAt > 0L && latestCall.startedAt >= safeAfter) {
            return CallNoteTarget(
                direction = latestCall.direction.ifBlank { directionHint },
                callAt = latestCall.startedAt,
                durationSeconds = latestCall.durationSeconds,
            )
        }

        return CallNoteTarget(directionHint, 0L, 0L)
    }
}
