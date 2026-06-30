package com.onlineimoti.calllog

import android.content.Context
import java.util.concurrent.Callable
import java.util.concurrent.Executors

internal data class HomeCompanyScopeLabel(
    val companyId: String,
    val companyName: String,
    val hasGeneralNote: Boolean,
    val phase: Int,
)

/**
 * Creates compact company labels below a contact name in Home.
 * A yellow label means there is a company main note; a gray label means there
 * is no main note but the contact has a phase in that company.
 */
internal object HomeCompanyGeneralNoteLabels {
    fun fetch(context: Context, config: AppConfig, phones: List<String>): Map<String, List<HomeCompanyScopeLabel>> {
        if (!CallReportRemoteAccess.isReady(config)) return emptyMap()

        val requestedPhones = phones
            .filter { HomeCallPageLoader.noteKey(it).isNotBlank() }
            .distinctBy { HomeCallPageLoader.noteKey(it) }
            .take(MAX_VISIBLE_PHONE_LOOKUPS)
        if (requestedPhones.isEmpty()) return emptyMap()

        // CRM contacts and unknown numbers share the server-backed company scope.
        // Resolve them in one batch, instead of doing a full Contacts lookup per row.
        val scopedPhoneKeys = HomeCallPageLoader.crmEligiblePhoneKeys(context, requestedPhones)
        if (scopedPhoneKeys.isEmpty()) return emptyMap()

        val result = CallReportHistoryLookupClient.lookupMany(config, requestedPhones)
        val companiesById = result.principal.companies.associate { it.id to it.name }
        val labelsByPhone = linkedMapOf<String, LinkedHashMap<String, MutableScopeLabel>>()

        fun labelFor(phoneKey: String, companyId: String): MutableScopeLabel {
            val companyName = companiesById[companyId].orEmpty().ifBlank { companyId }
            return labelsByPhone
                .getOrPut(phoneKey) { linkedMapOf() }
                .getOrPut(companyId) { MutableScopeLabel(companyId, companyName) }
        }

        for (event in result.events) {
            if (event.note.isBlank() || event.companyId.isBlank()) continue
            val phoneKey = HomeCallPageLoader.noteKey(event.phone)
            if (phoneKey.isBlank() || phoneKey !in scopedPhoneKeys) continue

            // Topic general notes have a stable event id. The fallback preserves
            // compatibility with earlier server records that have no such id.
            val isGeneralNote = event.clientEventId.contains(":topic:general:") ||
                event.clientEventId.contains(":note:general:") ||
                (event.communicationType.equals("note", ignoreCase = true) &&
                    event.direction.isBlank() && event.durationSeconds <= 0L)
            if (isGeneralNote) labelFor(phoneKey, event.companyId).hasGeneralNote = true
        }

        // A locally saved company note is useful immediately, before the durable
        // topic outbox has completed its server round trip.
        for (phone in requestedPhones) {
            val phoneKey = HomeCallPageLoader.noteKey(phone)
            if (phoneKey !in scopedPhoneKeys) continue
            for (company in result.principal.companies) {
                if (CallReportCompanyGeneralNoteStore.noteFor(context, phone, company.id).isNotBlank()) {
                    labelFor(phoneKey, company.id).hasGeneralNote = true
                }
            }
        }

        // The phase endpoint already scopes each record to one company. Lookups
        // are parallel and run only in this background Home loader.
        val phaseRequests = requestedPhones
            .filter { HomeCallPageLoader.noteKey(it) in scopedPhoneKeys }
            .flatMap { phone -> result.principal.companies.map { company -> PhaseRequest(phone, company.id) } }
        if (phaseRequests.isNotEmpty()) {
            val executor = Executors.newFixedThreadPool(minOf(MAX_PARALLEL_PHASE_LOOKUPS, phaseRequests.size))
            try {
                val futures = phaseRequests.map { request ->
                    executor.submit(Callable {
                        val hasLocalCompanyState = CompanyNegotiationPhaseStore.hasSavedState(
                            context,
                            request.phone,
                            request.companyId,
                        )
                        val localState = if (hasLocalCompanyState) {
                            CompanyNegotiationPhaseStore.state(context, request.phone, request.companyId)
                        } else {
                            ContactNegotiationPhaseState()
                        }
                        val remoteState = runCatching {
                            CompanyNegotiationPhaseRemoteClient.fetch(config, request.phone, request.companyId)
                        }.getOrNull()
                        val resolvedState = when {
                            remoteState == null -> localState
                            remoteState.updatedAtMs >= localState.updatedAtMs -> remoteState
                            else -> localState
                        }
                        PhaseResult(
                            phoneKey = HomeCallPageLoader.noteKey(request.phone),
                            companyId = request.companyId,
                            phase = resolvedState.phase,
                        )
                    })
                }
                futures.forEach { future ->
                    val phase = runCatching { future.get() }.getOrNull() ?: return@forEach
                    if (phase.phoneKey.isBlank() || phase.phase !in 1..4) return@forEach
                    labelFor(phase.phoneKey, phase.companyId).phase = phase.phase
                }
            } finally {
                executor.shutdownNow()
            }
        }

        return labelsByPhone.mapValues { (_, labelsByCompany) ->
            labelsByCompany.values
                .filter { it.hasGeneralNote || it.phase in 1..4 }
                .sortedBy { it.companyName.lowercase() }
                .map { label ->
                    HomeCompanyScopeLabel(
                        companyId = label.companyId,
                        companyName = label.companyName,
                        hasGeneralNote = label.hasGeneralNote,
                        phase = label.phase,
                    )
                }
        }.filterValues { it.isNotEmpty() }
    }

    private data class MutableScopeLabel(
        val companyId: String,
        val companyName: String,
        var hasGeneralNote: Boolean = false,
        var phase: Int = ContactNegotiationPhaseStore.NONE,
    )

    private data class PhaseRequest(
        val phone: String,
        val companyId: String,
    )

    private data class PhaseResult(
        val phoneKey: String,
        val companyId: String,
        val phase: Int,
    )

    private const val MAX_VISIBLE_PHONE_LOOKUPS = 20
    private const val MAX_PARALLEL_PHASE_LOOKUPS = 4
}
