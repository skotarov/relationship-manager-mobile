package com.onlineimoti.calllog

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import java.util.concurrent.Future

internal enum class HomeCrmContactKind {
    /** Number does not exist in a regular Android contact. */
    UNKNOWN,
    /** Existing Android contact explicitly enabled for CRM/cloud sync. */
    KNOWN_CRM,
    /** Existing Android contact without the CRM switch. */
    NOT_ELIGIBLE,
}

object HomeCallPageLoader {
    private const val SEARCH_SCAN_LIMIT = 500
    private const val FILTERED_CALL_SCAN_LIMIT = 500
    private const val FILTERED_SMS_SCAN_LIMIT = 150
    private const val CRM_CALL_SCAN_LIMIT = 1_000
    private const val REAL_CONTACT_CACHE_MS = 30_000L
    private const val SEARCH_CALL_CACHE_MS = 10_000L
    private const val MAX_FILTERED_SEARCH_CALL_CACHES = 8

    private val realContactPhoneKeysLock = Any()
    private var cachedRealContactPhoneKeys: Set<String> = emptySet()
    private var realContactPhoneKeysCachedAtMs = 0L

    private val searchCallCacheLock = Any()
    private var cachedUnfilteredSearchCalls = TimedCalls(0L, emptyList())
    private val cachedFilteredSearchCalls = linkedMapOf<String, TimedCalls>()
    private val searchSourceExecutor = Executors.newFixedThreadPool(3)

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

    /** Clears only local, short-lived snapshots; it never alters stored notes or contacts. */
    fun clearSearchCache() {
        synchronized(searchCallCacheLock) {
            cachedUnfilteredSearchCalls = TimedCalls(0L, emptyList())
            cachedFilteredSearchCalls.clear()
        }
        ContactSearchProvider.invalidate()
        StoredNoteSearchProvider.invalidate()
    }

    /** A CRM row is either an unknown number or a number explicitly marked CRM. */
    fun isCrmEligible(context: Context, phone: String): Boolean {
        if (noteKey(phone).isBlank()) return false
        return CrmContactSyncStore.isEnabled(context.applicationContext, phone) ||
            ContactServerCompanyScope.isUnknownNumber(context.applicationContext, phone)
    }

    /** Resolves the CRM category for each phone in one Contacts pass for Home-only filtering. */
    internal fun crmContactKinds(context: Context, phones: Iterable<String>): Map<String, HomeCrmContactKind> {
        val candidateKeys = phones.map(::noteKey).filter { it.isNotBlank() }.toSet()
        if (candidateKeys.isEmpty()) return emptyMap()
        val appContext = context.applicationContext
        val explicitlyCrmKeys = CrmContactSyncStore.enabledPhoneKeys(appContext)
        val knownRealContactKeys = realContactPhoneKeys(appContext)
        return candidateKeys.associateWith { key ->
            when {
                key !in knownRealContactKeys -> HomeCrmContactKind.UNKNOWN
                key in explicitlyCrmKeys -> HomeCrmContactKind.KNOWN_CRM
                else -> HomeCrmContactKind.NOT_ELIGIBLE
            }
        }
    }

    /**
     * Resolves CRM eligibility for a whole Home page at once. The explicit CRM set
     * comes directly from the per-contact CRM switches, then unknown numbers are
     * added by subtracting normal Android Contacts from the candidate numbers.
     */
    fun crmEligiblePhoneKeys(context: Context, phones: Iterable<String>): Set<String> {
        return crmContactKinds(context, phones)
            .filterValues { it != HomeCrmContactKind.NOT_ELIGIBLE }
            .keys
    }

    /** Raw chronological CRM candidates before contact, direction or company filters are applied. */
    fun crmCandidateCalls(context: Context): List<PhoneCallRecord> {
        return filterCrmEligible(
            context,
            PhoneCallReader.recentCalls(context, limit = CRM_CALL_SCAN_LIMIT, offset = 0),
        )
    }

    private fun crmCalls(context: Context, pageIndex: Int, pageSize: Int): List<PhoneCallRecord> {
        return crmCandidateCalls(context).page(pageIndex, pageSize)
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
        val sources = loadSearchSources(context.applicationContext, activePhoneFilter, query)
        val recentCalls = sources.calls
        val contactResults = sources.contacts
            .filter { result -> activePhoneFilter.isBlank() || noteKey(result.phone) == noteKey(activePhoneFilter) }
        val noteResults = sources.notes
            .filter { result -> activePhoneFilter.isBlank() || result.phoneKey == noteKey(activePhoneFilter) }

        val seen = linkedSetOf<String>()
        val ordered = arrayListOf<PhoneCallRecord>()
        contactResults.forEach { contact ->
            val key = noteKey(contact.phone)
            if (!seen.add("contact:$key")) return@forEach
            ordered.add(bestCallForPhone(recentCalls, contact.phone, contact.name, 0L, ""))
        }
        noteResults.forEach { note ->
            val resultKey = if (note.isCallNote && note.callAt > 0L) "call:${note.phoneKey}:${note.callAt}:${note.direction}" else "note:${note.phoneKey}"
            if (!seen.add(resultKey)) return@forEach
            ordered.add(bestCallForPhone(recentCalls, note.phone, note.phone, note.callAt, note.direction))
        }
        val filtered = if (crmMode) filterCrmEligible(context, ordered) else ordered
        return filtered.page(pageIndex, pageSize)
    }

    /** The three data sources are independent; run them concurrently without reducing scope. */
    private fun loadSearchSources(context: Context, activePhoneFilter: String, query: String): SearchSources {
        val callsFuture = searchSourceExecutor.submit<List<PhoneCallRecord>> {
            searchCallSnapshot(context, activePhoneFilter)
        }
        val contactsFuture = searchSourceExecutor.submit<List<ContactSearchResult>> {
            ContactSearchProvider.search(context, query)
        }
        val notesFuture = searchSourceExecutor.submit<List<StoredNoteSearchResult>> {
            StoredNoteSearchProvider.search(context, query)
        }
        return SearchSources(
            calls = await(callsFuture, emptyList()),
            contacts = await(contactsFuture, emptyList()),
            notes = await(notesFuture, emptyList()),
        )
    }

    private fun searchCallSnapshot(context: Context, activePhoneFilter: String): List<PhoneCallRecord> {
        val key = noteKey(activePhoneFilter)
        val now = System.currentTimeMillis()
        synchronized(searchCallCacheLock) {
            val cached = if (key.isBlank()) cachedUnfilteredSearchCalls else cachedFilteredSearchCalls[key]
            if (cached != null && now - cached.loadedAtMs < SEARCH_CALL_CACHE_MS) return cached.calls
        }
        val loaded = if (activePhoneFilter.isBlank()) {
            PhoneCallReader.recentCalls(context, limit = SEARCH_SCAN_LIMIT, offset = 0)
        } else {
            PhoneCallReader.callsForPhone(context, activePhoneFilter, limit = SEARCH_SCAN_LIMIT, offset = 0)
        }
        synchronized(searchCallCacheLock) {
            val entry = TimedCalls(now, loaded)
            if (key.isBlank()) {
                cachedUnfilteredSearchCalls = entry
            } else {
                cachedFilteredSearchCalls[key] = entry
                while (cachedFilteredSearchCalls.size > MAX_FILTERED_SEARCH_CALL_CACHES) {
                    cachedFilteredSearchCalls.remove(cachedFilteredSearchCalls.entries.first().key)
                }
            }
        }
        return loaded
    }

    private fun <T> await(future: Future<T>, fallback: T): T {
        return try {
            future.get()
        } catch (_: InterruptedException) {
            future.cancel(true)
            Thread.currentThread().interrupt()
            fallback
        } catch (_: Throwable) {
            fallback
        }
    }

    private fun filterCrmEligible(context: Context, calls: List<PhoneCallRecord>): List<PhoneCallRecord> {
        val categories = crmContactKinds(context, calls.asSequence().map { it.number }.asIterable())
        return calls.filter { call -> categories[noteKey(call.number)] != HomeCrmContactKind.NOT_ELIGIBLE }
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
            calls.firstOrNull { call -> noteKey(call.number) == key && call.startedAt == callAt && (direction.isBlank() || call.direction == direction) }
        } else null
        val latest = calls.firstOrNull { call -> noteKey(call.number) == key }
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
        calls.map { call -> call.number }.distinctBy { number -> noteKey(number) }.forEach { number ->
            ContactNoteReader.generalNoteForPhone(context, number).takeIf { note -> note.isNotBlank() }?.let { note ->
                notes[noteKey(number)] = note
            }
        }
        return notes
    }

    fun contactNames(context: Context, calls: List<PhoneCallRecord>): Map<String, String> {
        val names = linkedMapOf<String, String>()
        calls.map { call -> call.number }.distinctBy { number -> noteKey(number) }.forEach { number ->
            ContactGroupFilter.resolveDisplayName(context, number).orEmpty().takeIf { name -> name.isNotBlank() }?.let { name ->
                names[noteKey(number)] = name
            }
        }
        return names
    }

    fun noteKey(number: String): String {
        val digits = number.filter { it.isDigit() }
        return if (digits.length > 9) digits.takeLast(9) else digits
    }

    private data class TimedCalls(
        val loadedAtMs: Long,
        val calls: List<PhoneCallRecord>,
    )

    private data class SearchSources(
        val calls: List<PhoneCallRecord>,
        val contacts: List<ContactSearchResult>,
        val notes: List<StoredNoteSearchResult>,
    )
}
