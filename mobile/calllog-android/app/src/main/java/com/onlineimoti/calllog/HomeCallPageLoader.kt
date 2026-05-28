package com.onlineimoti.calllog

import android.content.Context

object HomeCallPageLoader {
    fun calls(context: Context, activePhoneFilter: String, pageIndex: Int, pageSize: Int): List<PhoneCallRecord> {
        val offset = pageIndex * pageSize
        return if (activePhoneFilter.isBlank()) {
            PhoneCallReader.recentCalls(context, limit = pageSize, offset = offset)
        } else {
            PhoneCallReader.callsForPhone(context, activePhoneFilter, limit = pageSize, offset = offset)
        }
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
