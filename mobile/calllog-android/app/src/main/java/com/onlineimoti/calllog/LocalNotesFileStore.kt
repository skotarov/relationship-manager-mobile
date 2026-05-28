package com.onlineimoti.calllog

import android.os.Build
import android.os.Environment
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile

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
                if (!sameCall(json, callAt, direction)) return@firstNotNullOfOrNull null
                json.optString("note").trim().takeIf { it.isNotBlank() }
            }.orEmpty()
        }.getOrDefault("")
    }

    fun allCallNotes(phoneNumber: String): List<ContactCallNote> {
        val phoneKey = phoneNumber.normalizePhoneKey()
        if (phoneKey.isBlank() || !canUsePublicFolder()) return emptyList()
        val file = callLogFile(phoneKey, createDirs = false)
        if (!file.exists()) return emptyList()
        return runCatching {
            val seen = linkedSetOf<String>()
            file.readLines().asReversed().mapNotNull { line ->
                val json = runCatching { JSONObject(line) }.getOrNull() ?: return@mapNotNull null
                if (json.optString("type") != "call_note") return@mapNotNull null
                val note = json.optString("note").trim()
                if (note.isBlank()) return@mapNotNull null
                val callAt = json.optLong("call_at", 0L)
                val direction = json.optString("direction")
                val key = "$callAt-${direction.ifBlank { "call" }}"
                if (!seen.add(key)) return@mapNotNull null
                ContactCallNote(
                    note = note,
                    callAt = callAt,
                    savedAt = json.optLong("at", 0L),
                    direction = direction,
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
            val file = callLogFile(phoneKey, createDirs = true)
            if (callAt > 0L && file.exists()) {
                val keptLines = file.readLines().filterNot { line ->
                    val json = runCatching { JSONObject(line) }.getOrNull() ?: return@filterNot false
                    sameCall(json, callAt, direction)
                }
                file.writeText((keptLines + record.toString()).joinToString("\n") + "\n")
            } else {
                file.appendText(record.toString() + "\n")
            }
            if (isUnknownContact) writeUnknownLatestProfile(phoneNumber, phoneKey, trimmedNote, now)
            true
        }.getOrDefault(false)
    }

    private fun sameCall(json: JSONObject, callAt: Long, direction: String): Boolean {
        val sameCallAt = json.optLong("call_at", 0L) == callAt
        val rowDirection = json.optString("direction")
        val sameDirection = direction.isBlank() || rowDirection.isBlank() || rowDirection == direction
        return sameCallAt && sameDirection
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
