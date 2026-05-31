package com.onlineimoti.calllog

import android.content.Context

object HomeCallPageLoader {
    private const val SEARCH_SCAN_LIMIT = 150
    private const val CACHE_TTL_MS = 60_000L
    private const val MAX_CACHE_SIZE = 300

    private data class NoteCacheEntry(
        val generalNote: String,
        val callNotes: List<ContactCallNote>,
        val loadedAt: Long,
    )

    private data class ContactNameCacheEntry(
        val name: String,
        val loadedAt: Long,
    )

    private val noteCache = linkedMapOf<String, NoteCacheEntry>()
    private val contactNameCache = linkedMapOf<String, ContactNameCacheEntry>()

    fun calls(context: Context, activePhoneFilter: String, searchQuery: String, pageIndex: Int, pageSize: Int): List<PhoneCallRecord> {
        val normalizedSearch = searchQuery.trim()
        if (normalizedSearch.isNotBlank()) return searchCalls(context, activePhoneFilter, normalizedSearch, pageIndex, pageSize)
        val offset = pageIndex * pageSize
        return if (activePhoneFilter.isBlank()) {
            PhoneCallReader.recentCalls(context, limit = pageSize, offset = offset)
        } else {
            PhoneCallReader.callsForPhone(context, activePhoneFilter, limit = pageSize, offset = offset)
        }
    }

    fun isSearchTooShort(query: String): Boolean {
        val trimmed = query.trim()
        val digits = trimmed.filter { it.isDigit() }
        return trimmed.isNotBlank() && trimmed.length < 2 && digits.length < 3
    }

    fun clearSearchCache() {
        noteCache.clear()
        contactNameCache.clear()
    }

    private fun searchCalls(context: Context, activePhoneFilter: String, query: String, pageIndex: Int, pageSize: Int): List<PhoneCallRecord> {
        if (isSearchTooShort(query)) return emptyList()
        val source = if (activePhoneFilter.isBlank()) {
            PhoneCallReader.recentCalls(context, limit = SEARCH_SCAN_LIMIT, offset = 0)
        } else {
            PhoneCallReader.callsForPhone(context, activePhoneFilter, limit = SEARCH_SCAN_LIMIT, offset = 0)
        }
        val loweredQuery = query.lowercase()
        val digitsQuery = query.filter { it.isDigit() }
        if (digitsQuery.length >= 3 && query.all { it.isDigit() || it.isWhitespace() || it == '+' }) {
            return source.filter { call -> phoneMatches(call, digitsQuery) }.page(pageIndex, pageSize)
        }

        val latestGeneralNoteMatches = linkedSetOf<String>()
        val matchedCalls = arrayListOf<PhoneCallRecord>()
        source.forEach { call ->
            val key = noteKey(call.number)
            if (phoneMatches(call, digitsQuery) || call.name.lowercase().contains(loweredQuery)) {
                matchedCalls.add(call)
                return@forEach
            }
            val notes = cachedNotesForNumber(context, call.number, key)
            if (notes.generalNote.lowercase().contains(loweredQuery) && latestGeneralNoteMatches.add(key)) {
                matchedCalls.add(call)
                return@forEach
            }
            if (notes.callNotes.any { it.note.lowercase().contains(loweredQuery) && sameCallNote(call, it) }) {
                matchedCalls.add(call)
                return@forEach
            }
            val contactName = cachedContactName(context, call.number, key)
            if (contactName.lowercase().contains(loweredQuery)) matchedCalls.add(call)
        }
        return matchedCalls.page(pageIndex, pageSize)
    }

    private fun phoneMatches(call: PhoneCallRecord, digitsQuery: String): Boolean {
        if (digitsQuery.length < 3) return false
        val callDigits = call.number.filter { it.isDigit() }
        return callDigits.contains(digitsQuery) || noteKey(call.number).contains(digitsQuery)
    }

    private fun cachedContactName(context: Context, number: String, key: String): String {
        val now = System.currentTimeMillis()
        contactNameCache[key]?.takeIf { now - it.loadedAt <= CACHE_TTL_MS }?.let { return it.name }
        val name = ContactGroupFilter.resolveDisplayName(context, number).orEmpty()
        contactNameCache[key] = ContactNameCacheEntry(name, now)
        trimCache(contactNameCache)
        return name
    }

    private fun cachedNotesForNumber(context: Context, number: String, key: String): NoteCacheEntry {
        val now = System.currentTimeMillis()
        noteCache[key]?.takeIf { now - it.loadedAt <= CACHE_TTL_MS }?.let { return it }
        val entry = NoteCacheEntry(
            generalNote = ContactNoteReader.generalNoteForPhone(context, number),
            callNotes = ContactNoteReader.callNotesForPhone(context, number),
            loadedAt = now,
        )
        noteCache[key] = entry
        trimCache(noteCache)
        return entry
    }

    private fun <T> trimCache(cache: MutableMap<String, T>) {
        while (cache.size > MAX_CACHE_SIZE) cache.remove(cache.keys.firstOrNull() ?: break)
    }

    private fun sameCallNote(call: PhoneCallRecord, note: ContactCallNote): Boolean {
        if (note.callAt <= 0L) return false
        val sameCallAt = note.callAt == call.startedAt
        val sameDirection = note.direction.isBlank() || call.direction.isBlank() || note.direction == call.direction
        return sameCallAt && sameDirection
    }

    private fun List<PhoneCallRecord>.page(pageIndex: Int, pageSize: Int): List<PhoneCallRecord> {
        return drop(pageIndex * pageSize).take(pageSize)
    }

    fun contactNotes(context: Context, calls: List<PhoneCallRecord>): Map<String, String> {
        val notes = linkedMapOf<String, String>()
        calls.map { it.number }.distinctBy { noteKey(it) }.forEach { number ->
            ContactNoteReader.generalNoteForPhone(context, number).takeIf { it.isNotBlank() }?.let { note ->
                notes[noteKey(number)] = note
            }
        }
        return notes
    }

    fun contactNames(context: Context, calls: List<PhoneCallRecord>): Map<String, String> {
        val names = linkedMapOf<String, String>()
        calls.map { it.number }.distinctBy { noteKey(it) }.forEach { number ->
            ContactGroupFilter.resolveDisplayName(context, number).orEmpty().takeIf { it.isNotBlank() }?.let { name ->
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