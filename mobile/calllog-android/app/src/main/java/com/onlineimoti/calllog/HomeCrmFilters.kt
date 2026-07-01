package com.onlineimoti.calllog

import android.content.Context

/** One optional CRM overview filter: a phase from any company visible to the broker. */
internal data class HomeCrmFilterState(
    val phase: Int = ContactNegotiationPhaseStore.NONE,
) {
    val isActive: Boolean
        get() = phase in ContactNegotiationPhaseStore.PHASE_1..ContactNegotiationPhaseStore.PHASE_4

    /** Kept for the existing Home loader branch; phase filtering needs no company-membership lookup. */
    val isCompanyFiltered: Boolean get() = false
}

/** Keeps the CRM phase choice after the app is reopened. */
internal object HomeCrmFilterStore {
    private const val PREFS = "relationship_manager_prefs"
    private const val KEY_PHASE = "home_crm_phase_filter"

    fun load(context: Context): HomeCrmFilterState {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return HomeCrmFilterState(normalize(prefs.getInt(KEY_PHASE, ContactNegotiationPhaseStore.NONE)))
    }

    fun save(context: Context, state: HomeCrmFilterState) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_PHASE, normalize(state.phase))
            .apply()
    }

    private fun normalize(phase: Int): Int = phase.takeIf {
        it in ContactNegotiationPhaseStore.PHASE_1..ContactNegotiationPhaseStore.PHASE_4
    } ?: ContactNegotiationPhaseStore.NONE
}

/** Filters CRM candidates by a company-scoped phase before the list is paged. */
internal object HomeCrmFilterEngine {
    fun filterLocal(
        context: Context,
        calls: List<PhoneCallRecord>,
        state: HomeCrmFilterState,
    ): List<PhoneCallRecord> {
        if (!state.isActive || calls.isEmpty()) return calls
        val phasesByPhoneKey = HomeCrmPhaseLookup.resolve(
            config = ConfigStore.load(context.applicationContext),
            phones = calls.map { it.number },
        )
        return calls.filter { call ->
            state.phase in phasesByPhoneKey[HomeCallPageLoader.noteKey(call.number)].orEmpty()
        }
    }

    /** Compatibility no-op: Home no longer offers a separate company filter. */
    fun filterByCompany(
        calls: List<PhoneCallRecord>,
        state: HomeCrmFilterState,
        companyIdsByPhoneKey: Map<String, Set<String>>,
    ): List<PhoneCallRecord> = calls
}
