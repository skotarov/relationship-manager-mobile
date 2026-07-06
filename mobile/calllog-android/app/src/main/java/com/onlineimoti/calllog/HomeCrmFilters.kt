package com.onlineimoti.calllog

import android.content.Context

/** CRM Home filters. Empty sets mean "All" for that filter. */
internal data class HomeCrmFilterState(
    val phases: Set<Int> = emptySet(),
    val companyIds: Set<String> = emptySet(),
) {
    val hasPhaseFilter: Boolean get() = phases.isNotEmpty()
    val hasCompanyFilter: Boolean get() = companyIds.isNotEmpty()
    val isActive: Boolean get() = hasPhaseFilter || hasCompanyFilter

    /**
     * When phases are selected as well, [HomeCrmFilterEngine] evaluates the
     * company + phase pair in one pass. A membership-only lookup is needed only
     * for a firm-only filter.
     */
    val isCompanyFiltered: Boolean get() = hasCompanyFilter && !hasPhaseFilter
}

/** Keeps the CRM selections after the app is reopened. */
internal object HomeCrmFilterStore {
    private const val PREFS = "relationship_manager_prefs"
    private const val KEY_PHASES = "home_crm_phase_filters_v2"
    private const val KEY_COMPANIES = "home_crm_company_filters_v1"
    private const val LEGACY_KEY_PHASE = "home_crm_phase_filter"

    fun load(context: Context): HomeCrmFilterState {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val storedPhases = prefs.getStringSet(KEY_PHASES, null)
            ?.mapNotNull { it.toIntOrNull() }
            ?.toSet()
            ?: legacyPhases(prefs.getInt(LEGACY_KEY_PHASE, ContactNegotiationPhaseStore.NONE))
        val companies = prefs.getStringSet(KEY_COMPANIES, emptySet())
            .orEmpty()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toSet()
        return HomeCrmFilterState(
            phases = normalizePhases(storedPhases),
            companyIds = companies,
        )
    }

    fun save(context: Context, state: HomeCrmFilterState) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_PHASES, normalizePhases(state.phases).mapTo(linkedSetOf()) { it.toString() })
            .putStringSet(KEY_COMPANIES, state.companyIds.map { it.trim() }.filter { it.isNotBlank() }.toSet())
            .remove(LEGACY_KEY_PHASE)
            .apply()
    }

    private fun legacyPhases(phase: Int): Set<Int> = if (
        phase in ContactNegotiationPhaseStore.PHASE_1..ContactNegotiationPhaseStore.PHASE_4
    ) setOf(phase) else emptySet()

    private fun normalizePhases(phases: Set<Int>): Set<Int> = phases.filterTo(linkedSetOf()) {
        it in ContactNegotiationPhaseStore.PHASE_1..ContactNegotiationPhaseStore.PHASE_4
    }
}

/** Filters CRM candidates before pagination. */
internal object HomeCrmFilterEngine {
    fun filterLocal(
        context: Context,
        calls: List<PhoneCallRecord>,
        state: HomeCrmFilterState,
    ): List<PhoneCallRecord> {
        if (!state.hasPhaseFilter || calls.isEmpty()) return calls
        val phasesByCompanyByPhone = HomeCrmPhaseLookup.resolveEffectiveCompanyPhases(
            context = context.applicationContext,
            config = ConfigStore.load(context.applicationContext),
            phones = calls.map { it.number },
        )
        return calls.filter { call ->
            val phasesByCompany = phasesByCompanyByPhone[HomeCallPageLoader.noteKey(call.number)].orEmpty()
            phasesByCompany.any { (companyId, phase) ->
                phase in state.phases && (!state.hasCompanyFilter || companyId in state.companyIds)
            }
        }
    }

    /** Applies a firm-only filter from the durable relationship-history cache. */
    fun filterByCompany(
        calls: List<PhoneCallRecord>,
        state: HomeCrmFilterState,
        companyIdsByPhoneKey: Map<String, Set<String>>,
    ): List<PhoneCallRecord> {
        if (!state.isCompanyFiltered) return calls
        return calls.filter { call ->
            companyIdsByPhoneKey[HomeCallPageLoader.noteKey(call.number)]
                ?.any { it in state.companyIds }
                ?: false
        }
    }
}
