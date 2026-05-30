package com.onlineimoti.calllog

import android.content.Context

object HomeCallPageLoader {
    private const val SEARCH_SCAN_LIMIT = 200

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

    private fun searchCalls(context: Context, activePhoneFilter: String, query: String, pageIndex: Int, pageSize: Int): List<PhoneCallRecord> {
        val source = if (activePhoneFilter.isBlank()) {
            PhoneCallReader.recentCalls(context, limit = SEARCH_SCAN_LIMIT, offset = 0)
        } else {
            PhoneCallReader.callsForPhone(context, activePhoneFilter, limit = SEARCH_SCAN_LIMIT, offset = 0)
        }
        val loweredQuery = query.lowercase()
        val digitsQuery = query.filter { it.isDigit() }
        val uniqueNumbers = source.map { it.number }.distinctBy { noteKey(it) }
        val generalNotesByKey = uniqueNumbers.associate { number -> noteKey(number) to ContactNoteReader.generalNoteForPhone(context, number) }
        val callNotesByKey = uniqueNumbers.associate { number ->
            noteKey(number) to ContactNoteReader.callNotesForPhone(context, number).joinToString("\n") { it.note }
        }
        val contactNamesByKey = uniqueNumbers.associate { number ->
            val key = noteKey(number)
            val cachedCallName = source.firstOrNull { noteKey(it.number) == key }?.name.orEmpty()
            key to cachedCallName.ifBlank { ContactGroupFilter.resolveDisplayName(context, number).orEmpty() }
        }

        val matches = source.filter { call ->
            val key = noteKey(call.number)
            val displayName = contactNamesByKey[key].orEmpty().ifBlank { call.displayName }
            val generalNote = generalNotesByKey[key].orEmpty()
            val callNotes = callNotesByKey[key].orEmpty()
            listOf(call.number, displayName, generalNote, callNotes).any { value -> value.lowercase().contains(loweredQuery) } ||
                digitsQuery.isNotBlank() && call.number.filter { it.isDigit() }.contains(digitsQuery) ||
                digitsQuery.isNotBlank() && key.contains(digitsQuery)
        }
        val offset = pageIndex * pageSize
        return matches.drop(offset).take(pageSize)
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