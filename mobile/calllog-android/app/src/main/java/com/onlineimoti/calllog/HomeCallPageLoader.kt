package com.onlineimoti.calllog

import android.content.Context

object HomeCallPageLoader {
    private const val SEARCH_SCAN_LIMIT = 150
    private const val NOTE_CACHE_TTL_MS = 60_000L
    private const val MAX_NOTE_CACHE_SIZE = 300

    private data class NoteCacheEntry(
        val generalNote: String,
        val callNotes: String,
        val loadedAt: Long,
    )

    private val noteCache = linkedMapOf<String, NoteCacheEntry>()

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
        val shouldSearchNotes = query.length >= 3 || digitsQuery.length >= 3
        val uniqueNumbers = source.map { it.number }.distinctBy { noteKey(it) }
        val contactNamesByKey = uniqueNumbers.associate { number ->
            val key = noteKey(number)
            val cachedCallName = source.firstOrNull { noteKey(it.number) == key }?.name.orEmpty()
            key to cachedCallName.ifBlank { ContactGroupFilter.resolveDisplayName(context, number).orEmpty() }
        }

        val matches = source.filter { call ->
            val key = noteKey(call.number)
            val displayName = contactNamesByKey[key].orEmpty().ifBlank { call.displayName }
            val phoneOrNameMatches = listOf(call.number, displayName).any { value -> value.lowercase().contains(loweredQuery) } ||
                digitsQuery.isNotBlank() && call.number.filter { it.isDigit() }.contains(digitsQuery) ||
                digitsQuery.isNotBlank() && key.contains(digitsQuery)
            if (phoneOrNameMatches) return@filter true
            if (!shouldSearchNotes) return@filter false
            val notes = cachedNotesForNumber(context, call.number, key)
            notes.generalNote.lowercase().contains(loweredQuery) || notes.callNotes.lowercase().contains(loweredQuery)
        }
        val offset = pageIndex * pageSize
        return matches.drop(offset).take(pageSize)
    }

    private fun cachedNotesForNumber(context: Context, number: String, key: String): NoteCacheEntry {
        val now = System.currentTimeMillis()
        noteCache[key]?.takeIf { now - it.loadedAt <= NOTE_CACHE_TTL_MS }?.let { return it }
        val entry = NoteCacheEntry(
            generalNote = ContactNoteReader.generalNoteForPhone(context, number),
            callNotes = ContactNoteReader.callNotesForPhone(context, number).joinToString("\n") { it.note },
            loadedAt = now,
        )
        noteCache[key] = entry
        while (noteCache.size > MAX_NOTE_CACHE_SIZE) {
            val firstKey = noteCache.keys.firstOrNull() ?: break
            noteCache.remove(firstKey)
        }
        return entry
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