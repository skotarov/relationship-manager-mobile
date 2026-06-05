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
)

object ContactNoteReader {
    private const val LOCAL_NOTE_PREFS = "callreport_local_contact_notes"

    fun noteForPhone(context: Context, phoneNumber: String): String = generalNoteForPhone(context, phoneNumber)

    fun generalNoteForPhone(context: Context, phoneNumber: String): String {
        if (phoneNumber.isBlank()) return ""
        return readLocalNote(context, phoneNumber)
    }

    fun callNoteForPhone(context: Context, phoneNumber: String, callAt: Long, direction: String = ""): String {
        return LocalNotesFileStore.noteForCall(context, phoneNumber, callAt, direction)
    }

    fun callNotesForPhone(context: Context, phoneNumber: String): List<ContactCallNote> {
        return LocalNotesFileStore.allCallNotes(context, phoneNumber)
    }

    fun saveGeneralNoteForPhone(context: Context, phoneNumber: String, note: String): Boolean {
        if (phoneNumber.isBlank()) return false
        saveLocalNote(context, phoneNumber, note)
        return phoneNumber.normalizePhoneKey().isNotBlank()
    }

    fun saveCallNoteForPhone(
        context: Context,
        phoneNumber: String,
        note: String,
        direction: String = "",
        callAt: Long = 0L,
        durationSeconds: Long = 0L,
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
        )
    }

    fun saveNoteForPhone(context: Context, phoneNumber: String, note: String): Boolean {
        return saveGeneralNoteForPhone(context, phoneNumber, note)
    }

    private fun findContactId(context: Context, phoneNumber: String): Long? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        return context.contentResolver.query(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI.buildUpon().appendPath(phoneNumber).build(),
            arrayOf(ContactsContract.PhoneLookup._ID),
            null,
            null,
            null,
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }
    }

    private fun readLocalNote(context: Context, phoneNumber: String): String {
        val key = phoneNumber.normalizePhoneKey()
        if (key.isBlank()) return ""
        return context.getSharedPreferences(LOCAL_NOTE_PREFS, Context.MODE_PRIVATE)
            .getString(key, "")
            .orEmpty()
            .trim()
    }

    private fun saveLocalNote(context: Context, phoneNumber: String, note: String) {
        val key = phoneNumber.normalizePhoneKey()
        if (key.isBlank()) return
        context.getSharedPreferences(LOCAL_NOTE_PREFS, Context.MODE_PRIVATE).edit().putString(key, note.trim()).apply()
    }

    private fun String.normalizePhoneKey(): String = filter { it.isDigit() }.let { if (it.length > 9) it.takeLast(9) else it }
}
