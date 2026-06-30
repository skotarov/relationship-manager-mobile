package com.onlineimoti.calllog

import android.content.Context

object HomeCallPageLoader {
    private const val SEARCH_SCAN_LIMIT = 500
    private const val FILTERED_CALL_SCAN_LIMIT = 500
    private const val FILTERED_SMS_SCAN_LIMIT = 150
    private const val CRM_CALL_SCAN_LIMIT = 1_000

    fun calls(
        context: Context,
        activePhoneFilter: String,
        searchQuery: String,
        pageIndex: Int,
        pageSize: Int,
        crmMode: Boolean = false,
    ): List<PhoneCallRecord> {
        val normalizedSearch = searchQuery.trim()
        if (normalizedSearch.isNotBlank()) {
            return searchCalls(context, activePhoneFilter, normalizedSearch, pageIndex, pageSize, crmMode)
        }
        return when {
            activePhoneFilter.isNotBlank() -> filteredTimelineForPhone(context, activePhoneFilter, pageIndex, pageSize, crmMode)
            crmMode -> crmCalls(context, pageIndex, pageSize)
            else -> PhoneCallReader.recentCalls(context, limit = pageSize, offset = pageIndex * pageSize)
        }
    }

    fun isSearchTooShort(query: String): Boolean {
        val trimmed = query.trim()
        val digits = trimmed.filter { it.isDigit() }
        return trimmed.isNotBlank() && trimmed.length < 2 && digits.length < 3
    }

    fun clearSearchCache() = Unit

    /** A CRM row is either an unknown number or a number explicitly marked CRM. */
    fun isCrmEligible(context: Context, phone: String): Boolean {
        if (noteKey(phone).isBlank()) return false
        return CrmContactSyncStore.isEnabled(context.applicationContext, phone) ||
            ContactServerCompanyScope.isUnknownNumber(context.applicationContext, phone)
    }

    private fun crmCalls(context: Context, pageIndex: Int, pageSize: Int): List<PhoneCallRecord> {
        return filterCrmEligible(
            context,
            PhoneCallReader.recentCalls(context, limit = CRM_CALL_SCAN_LIMIT, offset = 0),
        ).page(pageIndex, pageSize)
    }

    private fun filteredTimelineForPhone(
        context: Context,
        phone: String,
        pageIndex: Int,
        pageSize: Int,
        crmMode: Boolean,
    ): List<PhoneCallRecord> {
        val calls = PhoneCallReader.callsForPhone(
            context = context,
            phone = phone,
            limit = FILTERED_CALL_SCAN_LIMIT,
            offset = 0,
        )
        val messages = SmsMessageReader.messagesForPhone(
            context = context,
            phone = phone,
            limit = FILTERED_SMS_SCAN_LIMIT,
        ).map { sms ->
            PhoneCallRecord(
                number = phone,
                name = "",
                direction = if (sms.isOutgoing) "sms_out" else "sms_in",
                startedAt = sms.timestampMs,
                durationSeconds = 0L,
                smsBody = sms.body,
                providerId = sms.providerId,
            )
        }
        val timeline = (calls + messages).sortedByDescending { item -> item.startedAt }
        return (if (crmMode) filterCrmEligible(context, timeline) else timeline).page(pageIndex, pageSize)
    }

    private fun searchCalls(
        context: Context,
        activePhoneFilter: String,
        query: String,
        pageIndex: Int,
        pageSize: Int,
        crmMode: Boolean,
    ): List<PhoneCallRecord> {
        if (isSearchTooShort(query)) return emptyList()
        val recentCalls = if (activePhoneFilter.isBlank()) {
            PhoneCallReader.recentCalls(context, limit = SEARCH_SCAN_LIMIT, offset = 0)
        } else {
            PhoneCallReader.callsForPhone(context, activePhoneFilter, limit = SEARCH_SCAN_LIMIT, offset = 0)
        }
        val contactResults: List<ContactSearchResult> = ContactSearchProvider.search(context, query)
            .filter { result: ContactSearchResult -> activePhoneFilter.isBlank() || noteKey(result.phone) == noteKey(activePhoneFilter) }
        val noteResults: List<StoredNoteSearchResult> = StoredNoteSearchProvider.search(context, query)
            .filter { result: StoredNoteSearchResult -> activePhoneFilter.isBlank() || result.phoneKey == noteKey(activePhoneFilter) }

        val seen = linkedSetOf<String>()
        val ordered = arrayListOf<PhoneCallRecord>()
        contactResults.forEach { contact: ContactSearchResult ->
            val key = noteKey(contact.phone)
            if (!seen.add("contact:$key")) return@forEach
            ordered.add(bestCallForPhone(recentCalls, contact.phone, contact.name, 0L, ""))
        }
        noteResults.forEach { note: StoredNoteSearchResult ->
            val resultKey = if (note.isCallNote && note.callAt > 0L) "call:${note.phoneKey}:${note.callAt}:${note.direction}" else "note:${note.phoneKey}"
            if (!seen.add(resultKey)) return@forEach
            ordered.add(bestCallForPhone(recentCalls, note.phone, note.phone, note.callAt, note.direction))
        }
        val filtered = if (crmMode) filterCrmEligible(context, ordered) else ordered
        return filtered.page(pageIndex, pageSize)
    }

    private fun filterCrmEligible(context: Context, calls: List<PhoneCallRecord>): List<PhoneCallRecord> {
        val eligibilityByNumber = mutableMapOf<String, Boolean>()
        return calls.filter { call ->
            val key = noteKey(call.number)
            if (key.isBlank()) return@filter false
            eligibilityByNumber.getOrPut(key) { isCrmEligible(context, call.number) }
        }
    }

    private fun bestCallForPhone(calls: List<PhoneCallRecord>, phone: String, fallbackName: String, callAt: Long, direction: String): PhoneCallRecord {
        val key = noteKey(phone)
        val exact = if (callAt > 0L) {
            calls.firstOrNull { call: PhoneCallRecord -> noteKey(call.number) == key && call.startedAt == callAt && (direction.isBlank() || call.direction == direction) }
        } else null
        val latest = calls.firstOrNull { call: PhoneCallRecord -> noteKey(call.number) == key }
        return exact ?: latest ?: PhoneCallRecord(
            number = phone,
            name = fallbackName.takeIf { it.isNotBlank() && noteKey(it) != key }.orEmpty(),
            direction = direction,
            startedAt = callAt,
            durationSeconds = 0L,
        )
    }

    private fun List<PhoneCallRecord>.page(pageIndex: Int, pageSize: Int): List<PhoneCallRecord> {
        return drop(pageIndex * pageSize).take(pageSize)
    }

    fun contactNotes(context: Context, calls: List<PhoneCallRecord>): Map<String, String> {
        val notes = linkedMapOf<String, String>()
        calls.map { call: PhoneCallRecord -> call.number }.distinctBy { number: String -> noteKey(number) }.forEach { number: String ->
            ContactNoteReader.generalNoteForPhone(context, number).takeIf { note: String -> note.isNotBlank() }?.let { note: String ->
                notes[noteKey(number)] = note
            }
        }
        return notes
    }

    fun contactNames(context: Context, calls: List<PhoneCallRecord>): Map<String, String> {
        val names = linkedMapOf<String, String>()
        calls.map { call: PhoneCallRecord -> call.number }.distinctBy { number: String -> noteKey(number) }.forEach { number: String ->
            ContactGroupFilter.resolveDisplayName(context, number).orEmpty().takeIf { name: String -> name.isNotBlank() }?.let { name: String ->
                names[noteKey(number)] = name
            }
        }
        return names
    }

    fun noteKey(number: String): String {
        val digits = number.filter { it.isDigit() }
        return if (digits.length > 9) digits.takeLast(9) else digits
    }
}
