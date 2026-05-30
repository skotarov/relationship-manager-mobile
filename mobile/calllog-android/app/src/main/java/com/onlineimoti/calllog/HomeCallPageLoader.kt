package com.onlineimoti.calllog

import android.content.Context

object HomeCallPageLoader {
    private const val SEARCH_SCAN_LIMIT = 150
    private const val NOTE_CACHE_TTL_MS = 60_000L
    private const val MAX_NOTE_CACHE_SIZE = 300

    private data class NoteCacheEntry(
        val generalNote: String,
        val callNotes: List<ContactCallNote>,
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
        return trimmed.isNotBlank() && trimmed.length < 2
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
        val latestGeneralNoteMatches = linkedSetOf<String>()
        val matchedCalls = arrayListOf<PhoneCallRecord>()

        source.forEach { call ->
            val key = noteKey(call.number)
            val notes = cachedNotesForNumber(context, call.number, key)

            if (notes.generalNote.lowercase().contains(loweredQuery) && latestGeneralNoteMatches.add(key)) {
                matchedCalls.add(call)
                return@forEach
            }

            val callNoteMatches = notes.callNotes.any { note ->
                note.note.lowercase().contains(loweredQuery) && sameCallNote(call, note)
            }
            if (callNoteMatches) matchedCalls.add(call)
        }

        val offset = pageIndex * pageSize
        return matchedCalls.drop(offset).take(pageSize)
    }

    private fun cachedNotesForNumber(context: Context, number: String, key: String): NoteCacheEntry {
        val now = System.currentTimeMillis()
        noteCache[key]?.takeIf { now - it.loadedAt <= NOTE_CACHE_TTL_MS }?.let { return it }
        val entry = NoteCacheEntry(
            generalNote = ContactNoteReader.generalNoteForPhone(context, number),
            callNotes = ContactNoteReader.callNotesForPhone(context, number),
            loadedAt = now,
        )
        noteCache[key] = entry
        while (noteCache.size > MAX_NOTE_CACHE_SIZE) {
            val firstKey = noteCache.keys.firstOrNull() ?: break
            noteCache.remove(firstKey)
        }
        return entry
    }

    private fun sameCallNote(call: PhoneCallRecord, note: ContactCallNote): Boolean {
        if (note.callAt <= 0L) return false
        val sameCallAt = note.callAt == call.startedAt
        val sameDirection = note.direction.isBlank() || call.direction.isBlank() || note.direction == call.direction
        return sameCallAt && sameDirection
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