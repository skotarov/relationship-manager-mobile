package com.onlineimoti.calllog

import android.content.Context
import android.os.Build
import android.os.Environment
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile

/**
 * Local notes use one root only: Documents/.callreport whenever shared storage
 * access exists; otherwise the private .callreport folder of this app.
 * Server notes are not handled here.
 */
object LocalNotesFileStore {
    private const val ROOT_DIR = ".callreport"
    private const val NOTES_DIR = "notes"
    private const val CALL_LOG_FILE = "calllog.notes"
    private const val PROFILE_FILE = "profile.json"

    fun canUsePublicFolder(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    fun isEnabled(context: Context): Boolean = ConfigStore.load(context).useLocalNotesStorage

    /** The active location depends only on Android shared-storage access. */
    fun shouldUsePublicFolder(context: Context): Boolean = canUsePublicFolder()

    fun usesPublicFolder(context: Context): Boolean = isEnabled(context) && canUsePublicFolder()

    fun canUseConfiguredFolder(context: Context): Boolean = isEnabled(context)

    fun publicRootPath(): String = publicRoot().absolutePath

    fun privateRootPath(context: Context): String = privateRoot(context).absolutePath

    fun activeRootPath(context: Context): String = when {
        !isEnabled(context) -> "изключено"
        usesPublicFolder(context) -> publicRootPath()
        else -> privateRootPath(context)
    }

    /** Copies local note files to Documents/.callreport after shared access is granted. */
    fun migratePrivateToPublic(context: Context): Boolean {
        if (!isEnabled(context) || !canUsePublicFolder()) return false
        val source = privateRoot(context)
        if (!source.exists()) return true
        val target = publicRoot()
        return runCatching {
            copyDirectory(source, target)
            true
        }.getOrDefault(false)
    }

    /** Used only for app-private files that are not local notes. */
    internal fun appPrivateRoot(context: Context): File = privateRoot(context)

    fun latestNoteForPhone(context: Context, phoneNumber: String): String {
        val phoneKey = phoneNumber.normalizePhoneKey()
        if (phoneKey.isBlank() || !canUseConfiguredFolder(context)) return ""
        val line = readLastNonBlankLine(callLogFile(context, phoneKey, createDirs = false))
        return if (line.isBlank()) "" else runCatching { JSONObject(line).optString("note") }.getOrDefault("").trim()
    }

    /**
     * The Home Call Log may show a blue note only when it belongs to this exact
     * call-log row. The latest note for the number belongs to the caller-info
     * popup, not to an unrelated historical row.
     */
    fun noteForCall(context: Context, phoneNumber: String, callAt: Long, direction: String = ""): String {
        val phoneKey = phoneNumber.normalizePhoneKey()
        if (phoneKey.isBlank() || callAt <= 0L || !canUseConfiguredFolder(context)) return ""
        val file = callLogFile(context, phoneKey, createDirs = false)
        if (!file.exists()) return ""
        return runCatching {
            file.readLines().asReversed().firstNotNullOfOrNull { line ->
                val json = runCatching { JSONObject(line) }.getOrNull() ?: return@firstNotNullOfOrNull null
                if (!sameCall(json, callAt, direction)) return@firstNotNullOfOrNull null
                json.optString("note").trim().takeIf { it.isNotBlank() }
            }.orEmpty()
        }.getOrDefault("")
    }

    fun companyIdForCall(context: Context, phoneNumber: String, callAt: Long, direction: String = ""): String {
        val phoneKey = phoneNumber.normalizePhoneKey()
        if (phoneKey.isBlank() || callAt <= 0L || !canUseConfiguredFolder(context)) return ""
        val file = callLogFile(context, phoneKey, createDirs = false)
        if (!file.exists()) return ""
        return runCatching {
            file.readLines().asReversed().firstNotNullOfOrNull { line ->
                val json = runCatching { JSONObject(line) }.getOrNull() ?: return@firstNotNullOfOrNull null
                if (!sameCall(json, callAt, direction)) return@firstNotNullOfOrNull null
                json.optString("company_id").trim().takeIf { it.isNotBlank() }
            }.orEmpty()
        }.getOrDefault("")
    }

    fun clientNoteIdForCall(phoneNumber: String, callAt: Long, direction: String = ""): String {
        val phoneKey = phoneNumber.normalizePhoneKey()
        if (phoneKey.isBlank()) return ""
        val safeCallAt = if (callAt > 0L) callAt else 0L
        return "$phoneKey-$safeCallAt-${direction.ifBlank { "call" }}"
    }

    fun allCallNotes(context: Context, phoneNumber: String): List<ContactCallNote> {
        val phoneKey = phoneNumber.normalizePhoneKey()
        if (phoneKey.isBlank() || !canUseConfiguredFolder(context)) return emptyList()
        val file = callLogFile(context, phoneKey, createDirs = false)
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
                val clientNoteId = json.optString("id").ifBlank { clientNoteIdForCall(phoneNumber, callAt, direction) }
                val key = if (callAt > 0L) callAt.toString() else "$callAt-${direction.ifBlank { "call" }}"
                if (!seen.add(key)) return@mapNotNull null
                ContactCallNote(
                    note = note,
                    callAt = callAt,
                    savedAt = json.optLong("at", 0L),
                    direction = direction,
                    durationSeconds = json.optLong("duration", 0L),
                    clientNoteId = clientNoteId,
                    companyId = json.optString("company_id").trim(),
                )
            }.sortedByDescending { note -> note.callAt.takeIf { it > 0L } ?: note.savedAt }
        }.getOrDefault(emptyList())
    }

    fun profileGeneralNote(context: Context, phoneNumber: String): String {
        val phoneKey = phoneNumber.normalizePhoneKey()
        if (phoneKey.isBlank() || !canUseConfiguredFolder(context)) return ""
        val file = profileFile(context, phoneKey, createDirs = false)
        if (!file.exists()) return ""
        return runCatching { JSONObject(file.readText()).optString("general_note") }.getOrDefault("").trim()
    }

    fun saveUnknownGeneralNote(context: Context, phoneNumber: String, note: String): Boolean {
        val phoneKey = phoneNumber.normalizePhoneKey()
        if (phoneKey.isBlank() || !canUseConfiguredFolder(context)) return false
        return runCatching {
            val now = System.currentTimeMillis()
            val file = profileFile(context, phoneKey, createDirs = true)
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
        context: Context,
        phoneNumber: String,
        note: String,
        direction: String = "",
        callAt: Long = 0L,
        durationSeconds: Long = 0L,
        isUnknownContact: Boolean = false,
        companyId: String = "",
    ): Boolean {
        val phoneKey = phoneNumber.normalizePhoneKey()
        val trimmedNote = note.trim()
        if (phoneKey.isBlank() || trimmedNote.isBlank() || !canUseConfiguredFolder(context)) return false
        return runCatching {
            val now = System.currentTimeMillis()
            val record = JSONObject().apply {
                put("v", 2)
                put("type", "call_note")
                put("id", clientNoteIdForCall(phoneNumber, callAt.takeIf { it > 0L } ?: now, direction))
                put("at", now)
                put("phone", phoneNumber)
                put("normalized_phone", phoneKey)
                if (direction.isNotBlank()) put("direction", direction)
                if (callAt > 0L) put("call_at", callAt)
                if (durationSeconds > 0L) put("duration", durationSeconds)
                if (companyId.trim().isNotBlank()) put("company_id", companyId.trim())
                put("note", trimmedNote)
            }
            val file = callLogFile(context, phoneKey, createDirs = true)
            if (callAt > 0L && file.exists()) {
                val keptLines = file.readLines().filterNot { line ->
                    val json = runCatching { JSONObject(line) }.getOrNull() ?: return@filterNot false
                    sameCall(json, callAt, direction)
                }
                file.writeText((keptLines + record.toString()).joinToString("\n") + "\n")
            } else {
                file.appendText(record.toString() + "\n")
            }
            if (isUnknownContact) writeUnknownLatestProfile(context, phoneNumber, phoneKey, trimmedNote, now)
            true
        }.getOrDefault(false)
    }

    private fun copyDirectory(source: File, target: File) {
        if (source.isDirectory) {
            target.mkdirs()
            source.listFiles().orEmpty().forEach { child ->
                copyDirectory(child, File(target, child.name))
            }
        } else if (!target.exists() || source.lastModified() > target.lastModified()) {
            target.parentFile?.mkdirs()
            source.copyTo(target, overwrite = true)
        }
    }

    private fun privateRoot(context: Context): File = File(context.filesDir, ROOT_DIR)

    private fun publicRoot(): File = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
        ROOT_DIR,
    )

    private fun sameCall(json: JSONObject, callAt: Long, direction: String): Boolean {
        if (callAt <= 0L) return false
        val sameCallAt = json.optLong("call_at", 0L) == callAt
        if (!sameCallAt) return false
        val rowDirection = json.optString("direction")
        return direction.isBlank() || rowDirection.isBlank() || rowDirection == direction || exactTimestampWins(callAt)
    }

    private fun exactTimestampWins(callAt: Long): Boolean = callAt > 0L

    private fun writeUnknownLatestProfile(context: Context, phoneNumber: String, phoneKey: String, latestNote: String, updatedAt: Long) {
        val file = profileFile(context, phoneKey, createDirs = true)
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

    private fun callLogFile(context: Context, phoneKey: String, createDirs: Boolean): File = File(phoneDir(context, phoneKey, createDirs), CALL_LOG_FILE)

    private fun profileFile(context: Context, phoneKey: String, createDirs: Boolean): File = File(phoneDir(context, phoneKey, createDirs), PROFILE_FILE)

    private fun phoneDir(context: Context, phoneKey: String, createDirs: Boolean): File {
        val key = phoneKey.filter { it.isDigit() }
        val root = if (usesPublicFolder(context)) publicRoot() else privateRoot(context)
        val dir = File(File(File(root, NOTES_DIR), key.take(3)), "${key.drop(3).take(3)}/$key")
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
