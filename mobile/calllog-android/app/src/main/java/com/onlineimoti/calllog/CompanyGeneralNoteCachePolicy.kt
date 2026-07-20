package com.onlineimoti.calllog

internal enum class CompanyGeneralNoteCacheDecision {
    SHOW_LOCAL,
    CLEAR_LOCAL,
    IGNORE_LOCAL,
}

/** Resolves the short period between outbox acknowledgement and lookup propagation. */
internal object CompanyGeneralNoteCachePolicy {
    const val CONFIRMATION_GRACE_MS = 5 * 60_000L

    fun decide(
        localNote: String,
        pending: Boolean,
        savedAtMs: Long,
        nowMs: Long,
        serverConfirmed: Boolean,
    ): CompanyGeneralNoteCacheDecision {
        if (localNote.trim().isBlank()) return CompanyGeneralNoteCacheDecision.IGNORE_LOCAL
        if (serverConfirmed) return CompanyGeneralNoteCacheDecision.CLEAR_LOCAL
        if (pending) return CompanyGeneralNoteCacheDecision.SHOW_LOCAL
        if (savedAtMs <= 0L) return CompanyGeneralNoteCacheDecision.CLEAR_LOCAL
        val ageMs = (nowMs - savedAtMs).coerceAtLeast(0L)
        return if (ageMs <= CONFIRMATION_GRACE_MS) {
            CompanyGeneralNoteCacheDecision.SHOW_LOCAL
        } else {
            CompanyGeneralNoteCacheDecision.CLEAR_LOCAL
        }
    }

    fun belongsInGenericLane(explicitGeneral: Boolean, companyId: String): Boolean {
        return explicitGeneral && companyId.trim().isBlank()
    }
}
