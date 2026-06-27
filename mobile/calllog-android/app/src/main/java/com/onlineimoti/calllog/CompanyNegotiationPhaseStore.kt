package com.onlineimoti.calllog

import android.content.Context

/**
 * Company-scoped negotiation phases. Phone-only values from the prior store are
 * used only as an initial fallback until a company gets its own saved state.
 */
internal object CompanyNegotiationPhaseStore {
    private const val PREFS_NAME = "company_negotiation_phases"
    private const val KEY_PHASE_PREFIX = "phase_"
    private const val KEY_UPDATED_AT_PREFIX = "phase_updated_at_"

    fun state(context: Context, phone: String, companyId: String): ContactNegotiationPhaseState {
        val key = key(phone, companyId) ?: return ContactNegotiationPhaseState()
        val prefs = prefs(context)
        if (!hasSavedState(context, phone, companyId)) {
            return ContactNegotiationPhaseStore.state(context, phone)
        }
        val phase = normalize(prefs.getInt(KEY_PHASE_PREFIX + key, ContactNegotiationPhaseStore.NONE))
        val storedAt = prefs.getLong(KEY_UPDATED_AT_PREFIX + key, 0L).coerceAtLeast(0L)
        val updatedAt = if (phase != ContactNegotiationPhaseStore.NONE && storedAt == 0L) System.currentTimeMillis() else storedAt
        if (updatedAt != storedAt) prefs.edit().putLong(KEY_UPDATED_AT_PREFIX + key, updatedAt).apply()
        return ContactNegotiationPhaseState(phase, updatedAt)
    }

    fun hasSavedState(context: Context, phone: String, companyId: String): Boolean {
        val key = key(phone, companyId) ?: return false
        val prefs = prefs(context)
        return prefs.contains(KEY_PHASE_PREFIX + key) || prefs.contains(KEY_UPDATED_AT_PREFIX + key)
    }

    fun selectedPhase(context: Context, phone: String, companyId: String): Int = state(context, phone, companyId).phase

    fun togglePhase(context: Context, phone: String, companyId: String, phase: Int): ContactNegotiationPhaseState {
        require(phase in ContactNegotiationPhaseStore.PHASE_1..ContactNegotiationPhaseStore.PHASE_4) { "Unsupported negotiation phase." }
        val current = state(context, phone, companyId)
        val next = if (current.phase == phase) ContactNegotiationPhaseStore.NONE else phase
        return save(context, phone, companyId, ContactNegotiationPhaseState(next, System.currentTimeMillis()))
    }

    fun applyServerState(
        context: Context,
        phone: String,
        companyId: String,
        server: ContactNegotiationPhaseState,
    ): ContactNegotiationPhaseState {
        val normalized = server.copy(
            phase = normalize(server.phase),
            updatedAtMs = server.updatedAtMs.coerceAtLeast(0L),
        )
        val hasCompanyState = hasSavedState(context, phone, companyId)
        if (!hasCompanyState) {
            // A real server value belongs to this company and must override the
            // old phone-wide fallback. A missing server value keeps the fallback visible.
            return if (normalized.updatedAtMs > 0L) save(context, phone, companyId, normalized) else state(context, phone, companyId)
        }

        val local = state(context, phone, companyId)
        if (normalized.updatedAtMs == 0L || normalized.updatedAtMs < local.updatedAtMs) return local
        return save(context, phone, companyId, normalized)
    }

    private fun save(
        context: Context,
        phone: String,
        companyId: String,
        state: ContactNegotiationPhaseState,
    ): ContactNegotiationPhaseState {
        val key = key(phone, companyId) ?: return ContactNegotiationPhaseState()
        val normalized = state.copy(phase = normalize(state.phase), updatedAtMs = state.updatedAtMs.coerceAtLeast(0L))
        prefs(context).edit()
            .putInt(KEY_PHASE_PREFIX + key, normalized.phase)
            .putLong(KEY_UPDATED_AT_PREFIX + key, normalized.updatedAtMs)
            .apply()
        return normalized
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun key(phone: String, companyId: String): String? {
        val phoneKey = HomeCallPageLoader.noteKey(phone).takeIf { it.isNotBlank() } ?: return null
        val rawCompanyId = companyId.trim().takeIf { it.isNotBlank() } ?: return null
        val safeCompanyId = rawCompanyId.lowercase().map { char ->
            if (char.isLetterOrDigit() || char == '_' || char == '-') char else '_'
        }.joinToString("").ifBlank { rawCompanyId.hashCode().toString() }
        return "${phoneKey}_$safeCompanyId"
    }

    private fun normalize(phase: Int): Int = phase.takeIf {
        it in ContactNegotiationPhaseStore.PHASE_1..ContactNegotiationPhaseStore.PHASE_4
    } ?: ContactNegotiationPhaseStore.NONE
}
