package com.onlineimoti.calllog

internal data class HomeServerNotesCacheState(
    val eventsByPhoneKey: Map<String, List<CallReportHistoryEvent>> = emptyMap(),
    val phoneUpdatedAtMs: Map<String, Long> = emptyMap(),
    val principal: CallReportHistoryPrincipal = CallReportHistoryPrincipal(),
    val accessibleCompaniesAuthoritative: Boolean = false,
)

/** Pure cache merge/filter logic, kept separate so it can be covered by JVM tests. */
internal object HomeServerNotesCacheMerger {
    fun apply(
        state: HomeServerNotesCacheState,
        result: CallReportHistoryLookupResult,
        nowMs: Long,
    ): HomeServerNotesCacheState {
        if (!result.requestSuccessful) return state

        val authoritativePhoneKeys = result.successfulPhoneKeys
            .ifEmpty {
                result.events
                    .mapTo(linkedSetOf()) { HomeCallPageLoader.noteKey(it.phone) }
                    .filterTo(linkedSetOf()) { it.isNotBlank() }
            }
        val nextEvents = state.eventsByPhoneKey.toMutableMap()
        val nextUpdated = state.phoneUpdatedAtMs.toMutableMap()
        authoritativePhoneKeys.forEach { phoneKey ->
            val normalizedEvents = result.events
                .filter { event -> HomeCallPageLoader.noteKey(event.phone) == phoneKey }
                .distinctBy(::stableEventKey)
                .sortedByDescending(::eventVersionMs)
            if (normalizedEvents.isEmpty()) {
                nextEvents.remove(phoneKey)
                nextUpdated.remove(phoneKey)
            } else {
                nextEvents[phoneKey] = normalizedEvents
                nextUpdated[phoneKey] = nowMs
            }
        }

        var nextPrincipal = state.principal
        var companiesAuthoritative = state.accessibleCompaniesAuthoritative
        if (result.principalCompaniesAuthoritative) {
            nextPrincipal = result.principal.copy(
                companies = result.principal.companies
                    .distinctBy { it.id }
                    .sortedBy { it.name.lowercase() },
            )
            companiesAuthoritative = true
        } else if (
            result.principal.brokerId.isNotBlank() ||
            result.principal.brokerName.isNotBlank()
        ) {
            nextPrincipal = state.principal.copy(
                brokerId = result.principal.brokerId.ifBlank { state.principal.brokerId },
                brokerName = result.principal.brokerName.ifBlank { state.principal.brokerName },
            )
        }

        return trim(
            HomeServerNotesCacheState(
                eventsByPhoneKey = nextEvents,
                phoneUpdatedAtMs = nextUpdated,
                principal = nextPrincipal,
                accessibleCompaniesAuthoritative = companiesAuthoritative,
            ),
        )
    }

    fun visibleResult(
        state: HomeServerNotesCacheState,
        phones: Collection<String>,
    ): CallReportHistoryLookupResult {
        val requestedKeys = phones
            .mapTo(linkedSetOf(), HomeCallPageLoader::noteKey)
            .filterTo(linkedSetOf()) { it.isNotBlank() }
        if (requestedKeys.isEmpty()) {
            return CallReportHistoryLookupResult(principal = state.principal)
        }
        val allowedCompanyIds = state.principal.companies.mapTo(hashSetOf()) { it.id.trim() }
        val events = requestedKeys
            .flatMap { state.eventsByPhoneKey[it].orEmpty() }
            .filter { event ->
                val companyId = event.companyId.trim()
                companyId.isBlank() ||
                    !state.accessibleCompaniesAuthoritative ||
                    companyId in allowedCompanyIds
            }
            .distinctBy(::stableEventKey)
            .sortedByDescending(::eventVersionMs)
        return CallReportHistoryLookupResult(
            principal = state.principal,
            events = events,
        )
    }

    internal fun stableEventKey(event: CallReportHistoryEvent): String {
        return event.clientEventId.trim()
            .ifBlank { event.serverId.trim() }
            .ifBlank {
                listOf(
                    HomeCallPageLoader.noteKey(event.phone),
                    event.companyId.trim(),
                    event.communicationType.trim().lowercase(),
                    event.direction.trim().lowercase(),
                    event.occurredAtMs.toString(),
                    event.note.trim().hashCode().toString(),
                ).joinToString("|")
            }
    }

    internal fun eventVersionMs(event: CallReportHistoryEvent): Long = maxOf(
        event.updatedAtMs,
        event.createdAtMs,
        event.occurredAtMs,
    )

    private fun trim(state: HomeServerNotesCacheState): HomeServerNotesCacheState {
        if (state.eventsByPhoneKey.size <= MAX_PHONE_GROUPS) return state
        val keep = state.eventsByPhoneKey.keys
            .sortedByDescending { state.phoneUpdatedAtMs[it] ?: 0L }
            .take(MAX_PHONE_GROUPS)
            .toSet()
        return state.copy(
            eventsByPhoneKey = state.eventsByPhoneKey.filterKeys { it in keep },
            phoneUpdatedAtMs = state.phoneUpdatedAtMs.filterKeys { it in keep },
        )
    }

    private const val MAX_PHONE_GROUPS = 1_500
}
