package com.onlineimoti.calllog

import android.content.Context

/**
 * Creates the yellow company labels shown below a contact name in Home.
 * One batch request covers the visible call-log page, so labels appear promptly
 * even when several contacts are shown.
 */
internal object HomeCompanyGeneralNoteLabels {
    fun fetch(context: Context, config: AppConfig, phones: List<String>): Map<String, String> {
        if (!CallReportRemoteAccess.isReady(config)) return emptyMap()

        val requestedPhones = phones
            .filter { HomeCallPageLoader.noteKey(it).isNotBlank() }
            .distinctBy { HomeCallPageLoader.noteKey(it) }
            .take(50)
        if (requestedPhones.isEmpty()) return emptyMap()

        // Company scopes are meaningful only after CRM has been enabled for this contact.
        val crmPhoneKeys = requestedPhones
            .filter { CrmContactSyncStore.isEnabled(context, it) }
            .map { HomeCallPageLoader.noteKey(it) }
            .toSet()
        if (crmPhoneKeys.isEmpty()) return emptyMap()

        val result = CallReportHistoryLookupClient.lookupMany(config, requestedPhones)
        val companiesById = result.principal.companies.associate { it.id to it.name }
        val namesByPhone = linkedMapOf<String, LinkedHashSet<String>>()

        for (event in result.events) {
            if (event.note.isBlank() || event.companyId.isBlank()) continue
            val phoneKey = HomeCallPageLoader.noteKey(event.phone)
            if (phoneKey.isBlank() || phoneKey !in crmPhoneKeys) continue

            // Topic general notes have a stable event id. The fallback preserves
            // compatibility with earlier server records that have no such id.
            val isGeneralNote = event.clientEventId.contains(":topic:general:") ||
                event.clientEventId.contains(":note:general:") ||
                (event.communicationType.equals("note", ignoreCase = true) &&
                    event.direction.isBlank() && event.durationSeconds <= 0L)
            if (!isGeneralNote) continue

            val companyName = companiesById[event.companyId].orEmpty().ifBlank { event.companyId }
            namesByPhone.getOrPut(phoneKey) { linkedSetOf() }.add(companyName)
        }

        // A locally saved company note is useful immediately, before the durable
        // topic outbox has completed its server round trip.
        for (phone in requestedPhones) {
            val phoneKey = HomeCallPageLoader.noteKey(phone)
            if (phoneKey !in crmPhoneKeys) continue
            for (company in result.principal.companies) {
                if (CallReportCompanyGeneralNoteStore.noteFor(context, phone, company.id).isNotBlank()) {
                    namesByPhone.getOrPut(phoneKey) { linkedSetOf() }.add(company.name)
                }
            }
        }

        return namesByPhone.mapValues { (_, companyNames) ->
            companyNames.sortedBy { it.lowercase() }.joinToString(", ") { name -> "[ $name ]" }
        }
    }
}
