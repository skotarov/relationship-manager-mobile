package com.onlineimoti.calllog

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile

data class ContactCallNote(
    val note: String,
    val callAt: Long,
    val savedAt: Long,
    val direction: String,
    val durationSeconds: Long,
)

object ContactNoteReader {
    private const val LOCAL_NOTE_PREFS = "callreport_local_contact_notes"

    fun noteForPhone(context: Context, phoneNumber: String): String = generalNoteForPhone(context, phoneNumber)

    fun generalNoteForPhone(context: Context, phoneNumber: String): String {
        if (phoneNumber.isBlank()) return ""
        val hasContact = findContactId(context, phoneNumber) != null
        if (hasContact) readContactNote(context, phoneNumber).takeIf { it.isNotBlank() }?.let { return it }
        if (!hasContact) LocalNotesFileStore.profileGeneralNote(phoneNumber).takeIf { it.isNotBlank() }?.let { return it }
        return readLocalNote(context, phoneNumber)
    }

    fun callNoteForPhone(phoneNumber: String, callAt: Long, direction: String = ""): String {
        return LocalNotesFileStore.noteForCall(phoneNumber, callAt, direction)
    }

    fun callNotesForPhone(phoneNumber: String): List<ContactCallNote> {
        return LocalNotesFileStore.allCallNotes(phoneNumber)
    }

    fun saveGeneralNoteForPhone(context: Context, phoneNumber: String, note: String): Boolean {
        if (phoneNumber.isBlank()) return false
        val hasContact = findContactId(context, phoneNumber) != null
        val savedToContact = if (hasContact) saveContactNote(context, phoneNumber, note) else false
        val savedToProfile = if (!hasContact) LocalNotesFileStore.saveUnknownGeneralNote(phoneNumber, note) else false
        saveLocalNote(context, phoneNumber, note)
        return savedToContact || savedToProfile || phoneNumber.normalizePhoneKey().isNotBlank()
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

    private fun readContactNote(context: Context, phoneNumber: String): String {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return ""
        val contactId = findContactId(context, phoneNumber) ?: return ""
        return context.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Note.NOTE),
            "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
            arrayOf(contactId.toString(), ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE),
            null,
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0).orEmpty().trim() else "" }.orEmpty()
    }

    private fun saveContactNote(context: Context, phoneNumber: String, note: String): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CONTACTS) != PackageManager.PERMISSION_GRANTED) return false
        val contactId = findContactId(context, phoneNumber) ?: return false
        val rawContactId = findRawContactId(context, contactId) ?: return false
        val values = ContentValues().apply { put(ContactsContract.CommonDataKinds.Note.NOTE, note.trim()) }
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
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI.buildUpon().appendPath(phoneNumber).build(),
            arrayOf(ContactsContract.PhoneLookup._ID),
            null,
            null,
            null,
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }
    }

    private fun findRawContactId(context: Context, contactId: Long): Long? {
        return context.contentResolver.query(
            ContactsContract.RawContacts.CONTENT_URI,
            arrayOf(ContactsContract.RawContacts._ID),
            "${ContactsContract.RawContacts.CONTACT_ID} = ?",
            arrayOf(contactId.toString()),
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

object LocalNotesFileStore {
    private const val ROOT_DIR = ".callreport"
    private const val NOTES_DIR = "notes"
    private const val CALL_LOG_FILE = "calllog.notes"
    private const val PROFILE_FILE = "profile.json"

    fun canUsePublicFolder(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()
    fun publicRootPath(): String = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), ROOT_DIR).absolutePath

    fun latestNoteForPhone(phoneNumber: String): String {
        val phoneKey = phoneNumber.normalizePhoneKey()
        if (phoneKey.isBlank() || !canUsePublicFolder()) return ""
        val line = readLastNonBlankLine(callLogFile(phoneKey, createDirs = false))
        return if (line.isBlank()) "" else runCatching { JSONObject(line).optString("note") }.getOrDefault("").trim()
    }

    fun noteForCall(phoneNumber: String, callAt: Long, direction: String = ""): String {
        val phoneKey = phoneNumber.normalizePhoneKey()
        if (phoneKey.isBlank() || callAt <= 0L || !canUsePublicFolder()) return ""
        val file = callLogFile(phoneKey, createDirs = false)
        if (!file.exists()) return ""
        return runCatching {
            file.readLines().asReversed().firstNotNullOfOrNull { line ->
                val json = runCatching { JSONObject(line) }.getOrNull() ?: return@firstNotNullOfOrNull null
                val sameCall = json.optLong("call_at", 0L) == callAt
                val sameDirection = direction.isBlank() || json.optString("direction").isBlank() || json.optString("direction") == direction
                if (sameCall && sameDirection) json.optString("note").trim().takeIf { it.isNotBlank() } else null
            }.orEmpty()
        }.getOrDefault("")
    }

    fun allCallNotes(phoneNumber: String): List<ContactCallNote> {
        val phoneKey = phoneNumber.normalizePhoneKey()
        if (phoneKey.isBlank() || !canUsePublicFolder()) return emptyList()
        val file = callLogFile(phoneKey, createDirs = false)
        if (!file.exists()) return emptyList()
        return runCatching {
            file.readLines().mapNotNull { line ->
                val json = runCatching { JSONObject(line) }.getOrNull() ?: return@mapNotNull null
                val note = json.optString("note").trim()
                if (note.isBlank()) return@mapNotNull null
                ContactCallNote(
                    note = note,
                    callAt = json.optLong("call_at", 0L),
                    savedAt = json.optLong("at", 0L),
                    direction = json.optString("direction"),
                    durationSeconds = json.optLong("duration", 0L),
                )
            }.sortedByDescending { note -> note.callAt.takeIf { it > 0L } ?: note.savedAt }
        }.getOrDefault(emptyList())
    }

    fun profileGeneralNote(phoneNumber: String): String {
        val phoneKey = phoneNumber.normalizePhoneKey()
        if (phoneKey.isBlank() || !canUsePublicFolder()) return ""
        val file = profileFile(phoneKey, createDirs = false)
        if (!file.exists()) return ""
        return runCatching { JSONObject(file.readText()).optString("general_note") }.getOrDefault("").trim()
    }

    fun saveUnknownGeneralNote(phoneNumber: String, note: String): Boolean {
        val phoneKey = phoneNumber.normalizePhoneKey()
        if (phoneKey.isBlank() || !canUsePublicFolder()) return false
        return runCatching {
            val now = System.currentTimeMillis()
            val file = profileFile(phoneKey, createDirs = true)
            val profile = if (file.exists()) runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject()) else JSONObject()
            profile.put("v", 1)
            profile.put("phone", phoneNumber)
            profile.put("normalized_phone", phoneKey)
            profile.put("has_android_contact", false)
            profile.put("general_note", note.trim())
            profile.put("general_note_at", now)
            profile.put("updated_at", now)
            file.writeText(profile.toString(2))
            true
        }.getOrDefault(false)
    }

    fun appendCallNote(
        phoneNumber: String,
        note: String,
        direction: String = "",
        callAt: Long = 0L,
        durationSeconds: Long = 0L,
        isUnknownContact: Boolean = false,
    ): Boolean {
        val phoneKey = phoneNumber.normalizePhoneKey()
        val trimmedNote = note.trim()
        if (phoneKey.isBlank() || trimmedNote.isBlank() || !canUsePublicFolder()) return false
        return runCatching {
            val now = System.currentTimeMillis()
            val record = JSONObject().apply {
                put("v", 1)
                put("type", "call_note")
                put("id", "$phoneKey-${if (callAt > 0L) callAt else now}-${direction.ifBlank { "call" }}")
                put("at", now)
                put("phone", phoneNumber)
                put("normalized_phone", phoneKey)
                if (direction.isNotBlank()) put("direction", direction)
                if (callAt > 0L) put("call_at", callAt)
                if (durationSeconds > 0L) put("duration", durationSeconds)
                put("note", trimmedNote)
            }
            callLogFile(phoneKey, createDirs = true).appendText(record.toString() + "\n")
            if (isUnknownContact) writeUnknownLatestProfile(phoneNumber, phoneKey, trimmedNote, now)
            true
        }.getOrDefault(false)
    }

    private fun writeUnknownLatestProfile(phoneNumber: String, phoneKey: String, latestNote: String, updatedAt: Long) {
        val file = profileFile(phoneKey, createDirs = true)
        val profile = if (file.exists()) runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject()) else JSONObject()
        profile.put("v", 1)
        profile.put("phone", phoneNumber)
        profile.put("normalized_phone", phoneKey)
        profile.put("has_android_contact", false)
        profile.put("latest_note", latestNote)
        profile.put("latest_note_at", updatedAt)
        profile.put("updated_at", updatedAt)
        file.writeText(profile.toString(2))
    }

    private fun callLogFile(phoneKey: String, createDirs: Boolean): File = File(phoneDir(phoneKey, createDirs), CALL_LOG_FILE)
    private fun profileFile(phoneKey: String, createDirs: Boolean): File = File(phoneDir(phoneKey, createDirs), PROFILE_FILE)

    private fun phoneDir(phoneKey: String, createDirs: Boolean): File {
        val key = phoneKey.filter { it.isDigit() }
        val dir = File(File(File(File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), ROOT_DIR), NOTES_DIR), key.take(3)), "${key.drop(3).take(3)}/$key")
        if (createDirs) dir.mkdirs()
        return dir
    }

    private fun readLastNonBlankLine(file: File): String {
        if (!file.exists() || file.length() <= 0L) return ""
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                var pointer = raf.length() - 1
                val bytes = ArrayList<Byte>()
                while (pointer >= 0) {
                    raf.seek(pointer)
                    val value = raf.readByte()
                    if ((value == '\n'.code.toByte() || value == '\r'.code.toByte()) && bytes.isNotEmpty()) break
                    if (value != '\n'.code.toByte() && value != '\r'.code.toByte()) bytes.add(value)
                    pointer -= 1
                }
                bytes.asReversed().toByteArray().toString(Charsets.UTF_8).trim()
            }
        }.getOrDefault("")
    }

    private fun String.normalizePhoneKey(): String = filter { it.isDigit() }.let { if (it.length > 9) it.takeLast(9) else it }
}
