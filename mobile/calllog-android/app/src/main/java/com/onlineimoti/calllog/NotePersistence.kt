package com.onlineimoti.calllog

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

internal object NotePersistence {
    private const val LOCAL_NOTE_PREFS = "callreport_local_contact_notes"

    fun saveOrDeleteGeneralNote(context: Context, phoneNumber: String, note: String): Boolean {
        val trimmed = note.trim()
        if (trimmed.isNotBlank()) return ContactNoteReader.saveGeneralNoteForPhone(context, phoneNumber, trimmed)
        return deleteGeneralNote(context, phoneNumber)
    }

    fun saveOrDeleteCallNote(
        context: Context,
        phoneNumber: String,
        note: String,
        direction: String = "",
        callAt: Long = 0L,
        durationSeconds: Long = 0L,
        companyId: String = "",
    ): Boolean {
        val trimmed = note.trim()
        if (trimmed.isNotBlank()) {
            return ContactNoteReader.saveCallNoteForPhone(
                context = context,
                phoneNumber = phoneNumber,
                note = trimmed,
                direction = direction,
                callAt = callAt,
                durationSeconds = durationSeconds,
                companyId = companyId,
            )
        }
        return deleteCallNote(context, phoneNumber, callAt, direction)
    }

    private fun deleteGeneralNote(context: Context, phoneNumber: String): Boolean {
        if (phoneNumber.isBlank()) return false
        val phoneKey = PhoneNormalizer.key(phoneNumber)
        if (phoneKey.isBlank()) return false
        deleteAndroidContactNote(context, phoneNumber)
        deleteLocalGeneralNote(context, phoneKey)
        // Use the same active local-note backend as saving/reading. This matters
        // when the user selected a Storage Access Framework folder; File(path)
        // cannot address that tree URI and silently targeted the app-private root.
        LocalNotesFileStore.deleteGeneralNote(context, phoneNumber)
        return true
    }

    private fun deleteAndroidContactNote(context: Context, phoneNumber: String): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CONTACTS) != PackageManager.PERMISSION_GRANTED) return false
        val contactId = findContactId(context, phoneNumber) ?: return false
        val deleted = context.contentResolver.delete(
            ContactsContract.Data.CONTENT_URI,
            "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
            arrayOf(contactId.toString(), ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE),
        )
        return deleted > 0
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

    private fun deleteLocalGeneralNote(context: Context, phoneKey: String) {
        context.getSharedPreferences(LOCAL_NOTE_PREFS, Context.MODE_PRIVATE).edit().remove(phoneKey).apply()
    }

    private fun deleteCallNote(context: Context, phoneNumber: String, callAt: Long, direction: String): Boolean {
        val phoneKey = PhoneNormalizer.key(phoneNumber)
        if (phoneKey.isBlank()) return false
        return LocalNotesFileStore.deleteCallNote(context, phoneNumber, callAt, direction)
    }
}
