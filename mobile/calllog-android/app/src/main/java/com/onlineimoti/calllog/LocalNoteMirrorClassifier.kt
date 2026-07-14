package com.onlineimoti.calllog

import android.content.Context

/**
 * Keeps legacy local note fields from being displayed as yellow/general notes
 * when the same text already exists as a blue/conversation note.
 */
internal object LocalNoteMirrorClassifier {
    fun safeGeneralNote(
        context: Context,
        phoneNumber: String,
        candidate: String,
    ): String {
        return safeGeneralNote(
            candidate = candidate,
            callNoteTexts = LocalNotesFileStore.allCallNotes(context, phoneNumber).map { it.note },
        )
    }

    fun safeGeneralNote(
        candidate: String,
        callNoteTexts: Iterable<String>,
    ): String {
        val note = candidate.trim()
        if (note.isBlank()) return ""
        return if (mirrorsAnyCallNote(note, callNoteTexts)) "" else note
    }

    fun mirrorsAnyCallNote(
        candidate: String,
        callNoteTexts: Iterable<String>,
    ): Boolean {
        val normalizedCandidate = normalizedNoteText(candidate)
        if (normalizedCandidate.isBlank()) return false
        return callNoteTexts.any { callNote ->
            val normalizedCallNote = normalizedNoteText(callNote)
            when {
                normalizedCallNote.isBlank() -> false
                normalizedCallNote == normalizedCandidate -> true
                normalizedCandidate.length >= MIRRORED_NOTE_MIN_MATCH_LENGTH &&
                    normalizedCallNote.startsWith(normalizedCandidate) -> true
                normalizedCallNote.length >= MIRRORED_NOTE_MIN_MATCH_LENGTH &&
                    normalizedCandidate.startsWith(normalizedCallNote) -> true
                normalizedCandidate.length >= MIRRORED_NOTE_STRONG_MATCH_LENGTH &&
                    normalizedCallNote.contains(normalizedCandidate) -> true
                normalizedCallNote.length >= MIRRORED_NOTE_STRONG_MATCH_LENGTH &&
                    normalizedCandidate.contains(normalizedCallNote) -> true
                else -> false
            }
        }
    }

    private fun normalizedNoteText(value: String): String {
        return value.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .lowercase()
    }

    private const val MIRRORED_NOTE_MIN_MATCH_LENGTH = 12
    private const val MIRRORED_NOTE_STRONG_MATCH_LENGTH = 24
}
