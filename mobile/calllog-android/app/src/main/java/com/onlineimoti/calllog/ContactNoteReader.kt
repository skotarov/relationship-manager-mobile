package com.onlineimoti.calllog

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

object ContactNoteReader {
    private const val LOCAL_NOTE_PREFS = "callreport_local_contact_notes"

    fun noteForPhone(context: Context, phoneNumber: String): String {
        if (phoneNumber.isBlank()) return ""
        val contactNote = readContactNote(context, phoneNumber)
        if (contactNote.isNotBlank()) return contactNote
        return readLocalNote(context, phoneNumber)
    }

    fun saveNoteForPhone(context: Context, phoneNumber: String, note: String): Boolean {
        if (phoneNumber.isBlank()) return false
        val savedToContact = saveContactNote(context, phoneNumber, note)
        saveLocalNote(context, phoneNumber, note)
        return savedToContact || phoneNumber.normalizePhoneKey().isNotBlank()
    }

    private fun readContactNote(context: Context, phoneNumber: String): String {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return ""
        val contactId = findContactId(context, phoneNumber) ?: return ""
        return context.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Note.NOTE),
            "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
            arrayOf(contactId.toString(), ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE),
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0).orEmpty().trim() else ""
        }.orEmpty()
    }

    private fun saveContactNote(context: Context, phoneNumber: String, note: String): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CONTACTS) != PackageManager.PERMISSION_GRANTED) return false
        val contactId = findContactId(context, phoneNumber) ?: return false
        val rawContactId = findRawContactId(context, contactId) ?: return false
        val values = ContentValues().apply {
            put(ContactsContract.CommonDataKinds.Note.NOTE, note.trim())
        }
        val updated = context.contentResolver.update(
            ContactsContract.Data.CONTENT_URI,
            values,
            "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
            arrayOf(contactId.toString(), ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE),
        )
        if (updated > 0) return true
        val insertValues = ContentValues().apply {
            put(ContactsContract.Data.RAW_CONTACT_ID, rawContactId)
            put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE)
            put(ContactsContract.CommonDataKinds.Note.NOTE, note.trim())
        }
        return context.contentResolver.insert(ContactsContract.Data.CONTENT_URI, insertValues) != null
    }

    private fun findContactId(context: Context, phoneNumber: String): Long? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        return context.contentResolver.query(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI.buildUpon()
                .appendPath(phoneNumber)
                .build(),
            arrayOf(ContactsContract.PhoneLookup._ID),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else null
        }
    }

    private fun findRawContactId(context: Context, contactId: Long): Long? {
        return context.contentResolver.query(
            ContactsContract.RawContacts.CONTENT_URI,
            arrayOf(ContactsContract.RawContacts._ID),
            "${ContactsContract.RawContacts.CONTACT_ID} = ?",
            arrayOf(contactId.toString()),
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else null
        }
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
        context.getSharedPreferences(LOCAL_NOTE_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(key, note.trim())
            .apply()
    }

    private fun String.normalizePhoneKey(): String {
        val digits = filter { it.isDigit() }
        return if (digits.length > 9) digits.takeLast(9) else digits
    }
}
