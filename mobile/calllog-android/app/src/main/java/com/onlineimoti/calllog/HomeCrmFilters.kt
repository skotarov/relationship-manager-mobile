package com.onlineimoti.calllog

import android.content.Context
import android.provider.CallLog

/** Contact audience within CRM mode. */
internal enum class HomeCrmContactScope {
    ALL,
    UNKNOWN,
    KNOWN,
}

/** Communication kind within CRM mode. */
internal enum class HomeCrmDirectionScope {
    ALL,
    INCOMING,
    OUTGOING,
    MISSED,
    SMS,
}

/** Empty [companyId] means every firm; [NO_COMPANY_ID] means no firm is known. */
internal data class HomeCrmFilterState(
    val contactScope: HomeCrmContactScope = HomeCrmContactScope.ALL,
    val directionScope: HomeCrmDirectionScope = HomeCrmDirectionScope.ALL,
    val companyId: String = "",
    val pendingOnly: Boolean = false,
) {
    val isCompanyFiltered: Boolean get() = companyId.isNotBlank()
    val isActive: Boolean
        get() = contactScope != HomeCrmContactScope.ALL ||
            directionScope != HomeCrmDirectionScope.ALL ||
            companyId.isNotBlank() ||
            pendingOnly

    companion object {
        const val NO_COMPANY_ID = "__callreport_no_company__"
    }
}

/** Keeps the Home CRM view exactly as the broker left it. */
internal object HomeCrmFilterStore {
    private const val PREFS = "relationship_manager_prefs"
    private const val KEY_CONTACT_SCOPE = "home_crm_contact_scope"
    private const val KEY_DIRECTION_SCOPE = "home_crm_direction_scope"
    private const val KEY_COMPANY_ID = "home_crm_company_id"
    private const val KEY_PENDING_ONLY = "home_crm_pending_only"

    fun load(context: Context): HomeCrmFilterState {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return HomeCrmFilterState(
            contactScope = enumValueOrDefault(prefs.getString(KEY_CONTACT_SCOPE, ""), HomeCrmContactScope.ALL),
            directionScope = enumValueOrDefault(prefs.getString(KEY_DIRECTION_SCOPE, ""), HomeCrmDirectionScope.ALL),
            companyId = prefs.getString(KEY_COMPANY_ID, "").orEmpty().trim(),
            pendingOnly = prefs.getBoolean(KEY_PENDING_ONLY, false),
        )
    }

    fun save(context: Context, state: HomeCrmFilterState) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CONTACT_SCOPE, state.contactScope.name)
            .putString(KEY_DIRECTION_SCOPE, state.directionScope.name)
            .putString(KEY_COMPANY_ID, state.companyId.trim())
            .putBoolean(KEY_PENDING_ONLY, state.pendingOnly)
            .apply()
    }

    private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String?, fallback: T): T {
        return enumValues<T>().firstOrNull { it.name == value.orEmpty() } ?: fallback
    }
}

/** Applies local-only CRM criteria before a company membership lookup is needed. */
internal object HomeCrmFilterEngine {
    fun filterLocal(
        context: Context,
        calls: List<PhoneCallRecord>,
        state: HomeCrmFilterState,
    ): List<PhoneCallRecord> {
        if (calls.isEmpty()) return emptyList()
        val kinds = HomeCallPageLoader.crmContactKinds(context, calls.map { it.number })
        val pendingPhoneKeys = if (state.pendingOnly) {
            CallReportTopicNoteOutbox.pendingPhoneKeys(context) +
                CallReportDeferredCompanyAssignmentStore.pendingPhoneKeys(context)
        } else {
            emptySet()
        }
        return calls.filter { call ->
            val phoneKey = HomeCallPageLoader.noteKey(call.number)
            matchesContact(kinds[phoneKey] ?: HomeCrmContactKind.NOT_ELIGIBLE, state.contactScope) &&
                matchesDirection(call, state.directionScope) &&
                (!state.pendingOnly || phoneKey in pendingPhoneKeys)
        }
    }

    fun filterByCompany(
        calls: List<PhoneCallRecord>,
        state: HomeCrmFilterState,
        companyIdsByPhoneKey: Map<String, Set<String>>,
    ): List<PhoneCallRecord> {
        val selected = state.companyId.trim()
        if (selected.isBlank()) return calls
        return calls.filter { call ->
            // A missing cache value means the phone has not been safely resolved.
            // It must not become an incorrect "No company" result.
            val ids = companyIdsByPhoneKey[HomeCallPageLoader.noteKey(call.number)] ?: return@filter false
            if (selected == HomeCrmFilterState.NO_COMPANY_ID) ids.isEmpty() else selected in ids
        }
    }

    private fun matchesContact(kind: HomeCrmContactKind, scope: HomeCrmContactScope): Boolean = when (scope) {
        HomeCrmContactScope.ALL -> kind != HomeCrmContactKind.NOT_ELIGIBLE
        HomeCrmContactScope.UNKNOWN -> kind == HomeCrmContactKind.UNKNOWN
        HomeCrmContactScope.KNOWN -> kind == HomeCrmContactKind.KNOWN_CRM
    }

    private fun matchesDirection(call: PhoneCallRecord, scope: HomeCrmDirectionScope): Boolean = when (scope) {
        HomeCrmDirectionScope.ALL -> true
        HomeCrmDirectionScope.SMS -> call.isSms
        HomeCrmDirectionScope.OUTGOING -> !call.isSms && call.direction == "out"
        HomeCrmDirectionScope.INCOMING -> !call.isSms && call.callType == CallLog.Calls.INCOMING_TYPE
        HomeCrmDirectionScope.MISSED -> !call.isSms && call.callType in setOf(
            CallLog.Calls.MISSED_TYPE,
            CallLog.Calls.REJECTED_TYPE,
            CallLog.Calls.BLOCKED_TYPE,
            CallLog.Calls.VOICEMAIL_TYPE,
        )
    }
}
