package com.onlineimoti.calllog

/**
 * Creates the yellow company labels shown below a contact name in Home.
 * It uses the existing scoped history client, so server authorization and phone
 * normalization stay identical to the History screen.
 */
internal object HomeCompanyGeneralNoteLabels {
    fun fetch(config: AppConfig, phones: List<String>): Map<String, String> {
        if (!CallReportRemoteAccess.isReady(config)) return emptyMap()

        val requestedPhones = phones
            .filter { HomeCallPageLoader.noteKey(it).isNotBlank() }
            .distinctBy { HomeCallPageLoader.noteKey(it) }
            .take(20)
        val labelsByPhone = linkedMapOf<String, LinkedHashSet<String>>()

        for (phone in requestedPhones) {
            val result = runCatching { CallReportHistoryLookupClient.lookup(config, phone) }.getOrNull() ?: continue
            val companiesById = result.principal.companies.associate { it.id to it.name }
            val phoneKey = HomeCallPageLoader.noteKey(phone)
            for (event in result.events) {
                if (!event.communicationType.equals("note", ignoreCase = true)) continue
                if (event.note.isBlank() || event.companyId.isBlank()) continue
                if (HomeCallPageLoader.noteKey(event.phone) != phoneKey) continue

                val isGeneralNote = event.clientEventId.contains(":general:") ||
                    (event.direction.isBlank() && event.durationSeconds <= 0L)
                if (!isGeneralNote) continue

                val companyName = companiesById[event.companyId].orEmpty().ifBlank { event.companyId }
                labelsByPhone.getOrPut(phoneKey) { linkedSetOf() }.add(companyName)
            }
        }

        return labelsByPhone.mapValues { (_, companyNames) ->
            companyNames.sortedBy { it.lowercase() }.joinToString(", ") { name -> "[ $name ]" }
        }
    }
}
