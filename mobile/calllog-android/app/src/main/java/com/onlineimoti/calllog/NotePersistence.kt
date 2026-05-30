package com.onlineimoti.calllog

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile

internal object NotePersistence {
    private const val LOCAL_NOTE_PREFS = "callreport_local_contact_notes"
    private const val CALL_LOG_FILE = "calllog.notes"
    private const val PROFILE_FILE = "profile.json"

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
            )
        }
        return deleteCallNote(context, phoneNumber, callAt, direction)
    }

    private fun deleteGeneralNote(context: Context, phoneNumber: String): Boolean {
        if (phoneNumber.isBlank()) return false
        val phoneKey = phoneNumber.normalizePhoneKey()
        if (phoneKey.isBlank()) return false
        deleteAndroidContactNote(context, phoneNumber)
        deleteLocalGeneralNote(context, phoneKey)
        deleteUnknownProfileGeneralNote(context, phoneNumber, phoneKey)
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
        return context.contentResolver.query(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI.buildUpon().appendPath(phoneNumber).build(),
            arrayOf(ContactsContract.PhoneLookup._ID),
            null,
            null,
            null,
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }
    }

    private fun deleteLocalGeneralNote(context: Context, phoneKey: String) {
        context.getSharedPreferences(LOCAL_NOTE_PREFS, Context.MODE_PRIVATE).edit().remove(phoneKey).apply()
    }

    private fun deleteUnknownProfileGeneralNote(context: Context, phoneNumber: String, phoneKey: String) {
        if (!LocalNotesFileStore.canUseConfiguredFolder(context)) return
        runCatching {
            val file = profileFile(context, phoneKey, createDirs = false)
            if (!file.exists()) return@runCatching
            val profile = runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject())
            profile.remove("general_note")
            profile.remove("general_note_at")
            cleanupOrWriteProfile(context, file, phoneNumber, phoneKey, profile)
        }
    }

    private fun deleteCallNote(context: Context, phoneNumber: String, callAt: Long, direction: String): Boolean {
        val phoneKey = phoneNumber.normalizePhoneKey()
        if (phoneKey.isBlank()) return false
        if (callAt <= 0L || !LocalNotesFileStore.canUseConfiguredFolder(context)) return true
        return runCatching {
            val file = callLogFile(context, phoneKey, createDirs = false)
            if (!file.exists()) return@runCatching true
            val keptLines = file.readLines().filterNot { line ->
                val json = runCatching { JSONObject(line) }.getOrNull() ?: return@filterNot false
                sameCall(json, callAt, direction)
            }
            if (keptLines.isEmpty()) file.delete() else file.writeText(keptLines.joinToString("\n") + "\n")
            refreshLatestProfileNote(context, phoneNumber, phoneKey)
            true
        }.getOrDefault(false)
    }

    private fun refreshLatestProfileNote(context: Context, phoneNumber: String, phoneKey: String) {
        if (!LocalNotesFileStore.canUseConfiguredFolder(context)) return
        runCatching {
            val profileFile = profileFile(context, phoneKey, createDirs = false)
            if (!profileFile.exists()) return@runCatching
            val profile = runCatching { JSONObject(profileFile.readText()) }.getOrDefault(JSONObject())
            val latestLine = readLastNonBlankLine(callLogFile(context, phoneKey, createDirs = false))
            val latestNote = if (latestLine.isBlank()) "" else runCatching { JSONObject(latestLine).optString("note") }.getOrDefault("").trim()
            if (latestNote.isBlank()) {
                profile.remove("latest_note")
                profile.remove("latest_note_at")
            } else {
                profile.put("latest_note", latestNote)
                profile.put("latest_note_at", System.currentTimeMillis())
            }
            cleanupOrWriteProfile(context, profileFile, phoneNumber, phoneKey, profile)
        }
    }

    private fun cleanupOrWriteProfile(context: Context, file: File, phoneNumber: String, phoneKey: String, profile: JSONObject) {
        val hasGeneral = profile.optString("general_note").trim().isNotBlank()
        val hasLatest = profile.optString("latest_note").trim().isNotBlank()
        if (!hasGeneral && !hasLatest) {
            file.delete()
            return
        }
        profile.put("v", 1)
        profile.put("phone", phoneNumber)
        profile.put("normalized_phone", phoneKey)
        profile.put("has_android_contact", false)
        profile.put("storage", if (LocalNotesFileStore.shouldUsePublicFolder(context)) "public" else "private")
        profile.put("updated_at", System.currentTimeMillis())
        file.writeText(profile.toString(2))
    }

    private fun sameCall(json: JSONObject, callAt: Long, direction: String): Boolean {
        val sameCallAt = json.optLong("call_at", 0L) == callAt
        val rowDirection = json.optString("direction")
        val sameDirection = direction.isBlank() || rowDirection.isBlank() || rowDirection == direction
        return sameCallAt && sameDirection
    }

    private fun callLogFile(context: Context, phoneKey: String, createDirs: Boolean): File = File(phoneDir(context, phoneKey, createDirs), CALL_LOG_FILE)
    private fun profileFile(context: Context, phoneKey: String, createDirs: Boolean): File = File(phoneDir(context, phoneKey, createDirs), PROFILE_FILE)

    private fun phoneDir(context: Context, phoneKey: String, createDirs: Boolean): File {
        val key = phoneKey.filter { it.isDigit() }
        val root = File(LocalNotesFileStore.activeRootPath(context))
        val dir = File(File(File(root, "notes"), key.take(3)), "${key.drop(3).take(3)}/$key")
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