package com.onlineimoti.calllog

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

object HomeCallPageLoader {
    private const val SEARCH_SCAN_LIMIT = 500
    private const val FILTERED_CALL_SCAN_LIMIT = 500
    private const val FILTERED_SMS_SCAN_LIMIT = 150
    private const val CRM_CALL_SCAN_LIMIT = 1_000
    private const val REAL_CONTACT_CACHE_MS = 30_000L

    private val realContactPhoneKeysLock = Any()
    private var cachedRealContactPhoneKeys: Set<String> = emptySet()
    private var realContactPhoneKeysCachedAtMs = 0L

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

    /**
     * Resolves CRM eligibility for a whole Home page at once. The explicit CRM set
     * comes directly from the per-contact CRM switches, then unknown numbers are
     * added by subtracting normal Android Contacts from the candidate numbers.
     */
    fun crmEligiblePhoneKeys(context: Context, phones: Iterable<String>): Set<String> {
        val candidateKeys = phones.map(::noteKey).filter { it.isNotBlank() }.toSet()
        if (candidateKeys.isEmpty()) return emptySet()

        val appContext = context.applicationContext
        val explicitlyCrmKeys = CrmContactSyncStore.enabledPhoneKeys(appContext)
        val knownRealContactKeys = realContactPhoneKeys(appContext)
        return candidateKeys.filterTo(linkedSetOf()) { key ->
            key in explicitlyCrmKeys || key !in knownRealContactKeys
        }
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
        val eligiblePhoneKeys = crmEligiblePhoneKeys(context, calls.asSequence().map { it.number }.asIterable())
        return calls.filter { call -> noteKey(call.number) in eligiblePhoneKeys }
    }

    /**
     * Known-contact lookup is cached because CRM Home and the company-label loader
     * both need it. Without READ_CONTACTS the established behavior is to consider
     * all numbers unknown, so the cache remains empty.
     */
    private fun realContactPhoneKeys(context: Context): Set<String> {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return emptySet()
        }

        val now = System.currentTimeMillis()
        synchronized(realContactPhoneKeysLock) {
            if (realContactPhoneKeysCachedAtMs > 0L && now - realContactPhoneKeysCachedAtMs < REAL_CONTACT_CACHE_MS) {
                return cachedRealContactPhoneKeys
            }
        }

        val loaded = loadRealContactPhoneKeys(context)
        synchronized(realContactPhoneKeysLock) {
            cachedRealContactPhoneKeys = loaded
            realContactPhoneKeysCachedAtMs = now
            return cachedRealContactPhoneKeys
        }
    }

    /**
     * Query only provider-valid columns: collect normal raw contact IDs first,
     * then collect phone rows belonging to those IDs. The app's own Call Report
     * contact account is intentionally excluded from the known-contact set.
     */
    private fun loadRealContactPhoneKeys(context: Context): Set<String> {
        return runCatching {
            val realRawContactIds = context.contentResolver.query(
                ContactsContract.RawContacts.CONTENT_URI,
                arrayOf(ContactsContract.RawContacts._ID),
                "${ContactsContract.RawContacts.DELETED}=0 AND " +
                    "(${ContactsContract.RawContacts.ACCOUNT_TYPE} IS NULL OR " +
                    "${ContactsContract.RawContacts.ACCOUNT_TYPE}!=?)",
                arrayOf(CallReportContactIntegration.ACCOUNT_TYPE),
                null,
            )?.use { cursor ->
                buildSet {
                    while (cursor.moveToNext()) add(cursor.getLong(0))
                }
            } ?: emptySet()

            if (realRawContactIds.isEmpty()) return@runCatching emptySet()

            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.RAW_CONTACT_ID,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                ),
                null,
                null,
                null,
            )?.use { cursor ->
                buildSet {
                    while (cursor.moveToNext()) {
                        if (cursor.getLong(0) !in realRawContactIds) continue
                        noteKey(cursor.getString(1).orEmpty()).takeIf { it.isNotBlank() }?.let(::add)
                    }
                }
            } ?: emptySet()
        }.getOrDefault(emptySet())
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
