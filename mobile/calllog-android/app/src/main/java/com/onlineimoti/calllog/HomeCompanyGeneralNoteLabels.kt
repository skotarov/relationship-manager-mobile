package com.onlineimoti.calllog

/**
 * Creates the yellow company labels shown below a contact name in Home.
 * It uses the existing scoped history client, so server authorization and phone
 * normalization stay identical to the History screen.
 */
internal object HomeCompanyGeneralNoteLabels {
    fun fetch(config: AppConfig, phones: List<String>): Map<String, String> {
        if (!CallReportRemoteAccess.isReady(config)) return emptyMap()
        val labelsByPhone = linkedMapOf<String, LinkedHashSet<String>>()
        phones
            .filter { HomeCallPageLoader.noteKey(it).isNotBlank() }
            .distinctBy { HomeCallPageLoader.noteKey(it) }
            .take(20)
            .forEach { phone ->
                val result = runCatching { CallReportHistoryLookupClient.lookup(config, phone) }.getOrNull() ?: return@forEach
                val companiesById = result.principal.companies.associate { it.id to it.name }
                val phoneKey = HomeCallPageLoader.noteKey(phone)
                result.events.forEach { event ->
                    if (!event.communicationType.equals("note", ignoreCase = true)) return@forEach
                    if (event.note.isBlank() || event.companyId.isBlank()) return@forEach
                    if (HomeCallPageLoader.noteKey(event.phone) != phoneKey) return@forEach
                    val isGeneralNote = event.clientEventId.contains(":general:") ||
                        (event.direction.isBlank() && event.durationSeconds <= 0L)
                    if (!isGeneralNote) return@forEach
                    val companyName = companiesById[event.companyId].orEmpty().ifBlank { event.companyId }
                    labelsByPhone.getOrPut(phoneKey) { linkedSetOf() }.add(companyName)
                }
            }
        return labelsByPhone.mapValues { (_, companyNames) ->
            companyNames.sortedBy { it.lowercase() }.joinToString(", ") { name -> "[ $name ]" }
        }
    }
}
