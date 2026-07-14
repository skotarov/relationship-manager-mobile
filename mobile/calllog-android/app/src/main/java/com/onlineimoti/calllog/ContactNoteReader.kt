package com.onlineimoti.calllog

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

data class ContactCallNote(
    val note: String,
    val callAt: Long,
    val savedAt: Long,
    val direction: String,
    val durationSeconds: Long,
    val clientNoteId: String = "",
    /** Selected server company for this single conversation; blank means local-only. */
    val companyId: String = "",
    /** Original cloud record identifier for a server-only note that must be updated in place. */
    val serverClientEventId: String = "",
)

object ContactNoteReader {
    private const val LOCAL_NOTE_PREFS = "callreport_local_contact_notes"

    fun noteForPhone(context: Context, phoneNumber: String): String = generalNoteForPhone(context, phoneNumber)

    fun generalNoteForPhone(context: Context, phoneNumber: String): String {
        if (phoneNumber.isBlank()) return ""
        val activeStoreNote = LocalNotesFileStore.profileGeneralNote(context, phoneNumber).trim()
        // Explicitly saved local main notes must stay visible even when their text
        // matches a blue/call note. Mirror filtering belongs only to legacy import
        // paths; applying it here made a newly saved local main note look unsaved.
        if (usesExternalLocalNotesStore(context)) return activeStoreNote
        val legacyPrefsNote = readLocalNote(context, phoneNumber)
        return legacyPrefsNote.ifBlank { activeStoreNote }
    }

    fun callNoteForPhone(context: Context, phoneNumber: String, callAt: Long, direction: String = ""): String {
        return LocalNotesFileStore.noteForCall(context, phoneNumber, callAt, direction)
    }

    fun callNotesForPhone(context: Context, phoneNumber: String): List<ContactCallNote> {
        return LocalNotesFileStore.allCallNotes(context, phoneNumber)
    }

    fun saveGeneralNoteForPhone(context: Context, phoneNumber: String, note: String): Boolean {
        if (phoneNumber.isBlank()) return false
        val activeStoreSaved = LocalNotesFileStore.saveUnknownGeneralNote(context, phoneNumber, note)
        if (usesExternalLocalNotesStore(context)) return activeStoreSaved
        saveLocalNote(context, phoneNumber, note)
        return PhoneNormalizer.key(phoneNumber).isNotBlank()
    }

    fun saveCallNoteForPhone(
        context: Context,
        phoneNumber: String,
        note: String,
        direction: String = "",
        callAt: Long = 0L,
        durationSeconds: Long = 0L,
        companyId: String = "",
    ): Boolean {
        if (phoneNumber.isBlank() || note.isBlank()) return false
        val hasContact = findContactId(context, phoneNumber) != null
        return LocalNotesFileStore.appendCallNote(
            context = context,
            phoneNumber = phoneNumber,
            note = note,
            direction = direction,
            callAt = callAt,
            durationSeconds = durationSeconds,
            isUnknownContact = !hasContact,
            companyId = companyId,
        )
    }

    fun saveNoteForPhone(context: Context, phoneNumber: String, note: String): Boolean {
        return saveGeneralNoteForPhone(context, phoneNumber, note)
    }

    private fun usesExternalLocalNotesStore(context: Context): Boolean {
        return LocalNotesFileStore.usesSelectedFolder(context) || LocalNotesFileStore.usesPublicFolder(context)
    }

    private fun findContactId(context: Context, phoneNumber: String): Long? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        for (candidate in PhoneNormalizer.candidates(phoneNumber)) {
            val match = context.contentResolver.query(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI.buildUpon().appendPath(candidate).build(),
                arrayOf(ContactsContract.PhoneLookup._ID),
                null,
                null,
                null,
            )?.use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }
            if (match != null) return match
        }
        return null
    }

    private fun readLocalNote(context: Context, phoneNumber: String): String {
        val key = PhoneNormalizer.key(phoneNumber)
        if (key.isBlank()) return ""
        return context.getSharedPreferences(LOCAL_NOTE_PREFS, Context.MODE_PRIVATE)
            .getString(key, "")
            .orEmpty()
            .trim()
    }

    private fun saveLocalNote(context: Context, phoneNumber: String, note: String) {
        val key = PhoneNormalizer.key(phoneNumber)
        if (key.isBlank()) return
        context.getSharedPreferences(LOCAL_NOTE_PREFS, Context.MODE_PRIVATE).edit().putString(key, note.trim()).apply()
    }
}