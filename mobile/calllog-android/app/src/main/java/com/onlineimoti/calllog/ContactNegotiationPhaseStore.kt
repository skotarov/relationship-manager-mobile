package com.onlineimoti.calllog

import android.content.Context

internal data class ContactNegotiationPhaseState(
    val phase: Int = ContactNegotiationPhaseStore.NONE,
    val updatedAtMs: Long = 0L,
)

/**
 * Persists one optional negotiation phase per normalized contact phone number.
 * The timestamp is the conflict-resolution clock: newest state wins over server state.
 */
internal object ContactNegotiationPhaseStore {
    const val NONE = 0
    const val PHASE_1 = 1
    const val PHASE_2 = 2
    const val PHASE_3 = 3
    const val PHASE_4 = 4

    private const val PREFS_NAME = "contact_negotiation_phases"
    private const val KEY_PHASE_PREFIX = "phase_"
    private const val KEY_UPDATED_AT_PREFIX = "phase_updated_at_"

    fun selectedPhase(context: Context, phone: String): Int = state(context, phone).phase

    fun state(context: Context, phone: String): ContactNegotiationPhaseState {
        val key = phoneKey(phone) ?: return ContactNegotiationPhaseState()
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val phase = normalize(prefs.getInt(KEY_PHASE_PREFIX + key, NONE))
        val storedTimestamp = prefs.getLong(KEY_UPDATED_AT_PREFIX + key, 0L).coerceAtLeast(0L)
        // Migrate an already selected phase from the first local implementation.
        val updatedAtMs = if (phase != NONE && storedTimestamp == 0L) System.currentTimeMillis() else storedTimestamp
        if (updatedAtMs != storedTimestamp) {
            prefs.edit().putLong(KEY_UPDATED_AT_PREFIX + key, updatedAtMs).apply()
        }
        return ContactNegotiationPhaseState(phase, updatedAtMs)
    }

    fun togglePhase(context: Context, phone: String, phase: Int): ContactNegotiationPhaseState {
        require(phase in PHASE_1..PHASE_4) { "Unsupported negotiation phase." }
        val current = state(context, phone)
        val next = if (current.phase == phase) NONE else phase
        return save(context, phone, ContactNegotiationPhaseState(next, System.currentTimeMillis()))
    }

    /** Applies a confirmed server state only when it is newer, or equally recent on a tie. */
    fun applyServerState(context: Context, phone: String, server: ContactNegotiationPhaseState): ContactNegotiationPhaseState {
        val normalizedServer = server.copy(phase = normalize(server.phase), updatedAtMs = server.updatedAtMs.coerceAtLeast(0L))
        val local = state(context, phone)
        if (normalizedServer.updatedAtMs == 0L || normalizedServer.updatedAtMs < local.updatedAtMs) {
            return local
        }
        return save(context, phone, normalizedServer)
    }

    private fun save(context: Context, phone: String, state: ContactNegotiationPhaseState): ContactNegotiationPhaseState {
        val key = phoneKey(phone) ?: return ContactNegotiationPhaseState()
        val normalized = state.copy(phase = normalize(state.phase), updatedAtMs = state.updatedAtMs.coerceAtLeast(0L))
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_PHASE_PREFIX + key, normalized.phase)
            .putLong(KEY_UPDATED_AT_PREFIX + key, normalized.updatedAtMs)
            .apply()
        return normalized
    }

    private fun normalize(value: Int): Int = value.takeIf { it in PHASE_1..PHASE_4 } ?: NONE

    private fun phoneKey(phone: String): String? {
        return HomeCallPageLoader.noteKey(phone).takeIf { it.isNotBlank() }
    }
}
