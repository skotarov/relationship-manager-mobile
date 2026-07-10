package com.onlineimoti.calllog

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.documentfile.provider.DocumentFile
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile

/**
 * Local notes use one active root:
 * - a user-selected Storage Access Framework folder, when configured;
 * - legacy Documents/.callreport only when broad storage access already exists;
 * - otherwise the private .callreport folder of this app.
 *
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

    fun selectedFolderUri(context: Context): Uri? = ConfigStore.load(context).localNotesFolderUri
        .takeIf { it.isNotBlank() }
        ?.let { runCatching { Uri.parse(it) }.getOrNull() }

    fun hasSelectedFolderAccess(context: Context): Boolean {
        val uri = selectedFolderUri(context) ?: return false
        val hasPersistedPermission = context.contentResolver.persistedUriPermissions.any { permission ->
            permission.uri == uri && permission.isReadPermission && permission.isWritePermission
        }
        if (!hasPersistedPermission) return false
        return DocumentFile.fromTreeUri(context, uri)?.canWrite() == true
    }

    fun setSelectedFolder(context: Context, uri: Uri) {
        ConfigStore.save(
            context,
            ConfigStore.load(context).copy(
                useLocalNotesStorage = true,
                localNotesFolderUri = uri.toString(),
            ),
        )
    }

    fun clearSelectedFolder(context: Context) {
        selectedFolderUri(context)?.let { uri ->
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
        }
        ConfigStore.save(context, ConfigStore.load(context).copy(localNotesFolderUri = ""))
    }

    /** The active location prefers the user-selected folder, then legacy public storage, then private storage. */
    fun shouldUsePublicFolder(context: Context): Boolean = canUsePublicFolder() && !hasSelectedFolderAccess(context)

    fun usesSelectedFolder(context: Context): Boolean = isEnabled(context) && hasSelectedFolderAccess(context)

    fun usesPublicFolder(context: Context): Boolean = isEnabled(context) && !usesSelectedFolder(context) && canUsePublicFolder()

    fun canUseConfiguredFolder(context: Context): Boolean = isEnabled(context)

    fun publicRootPath(): String = publicRoot().absolutePath

    fun privateRootPath(context: Context): String = privateRoot(context).absolutePath

    fun activeRootPath(context: Context): String = when {
        !isEnabled(context) -> "изключено"
        usesSelectedFolder(context) -> selectedRootPath(context)
        usesPublicFolder(context) -> publicRootPath()
        else -> privateRootPath(context)
    }

    /** Copies local note files to the user-selected folder after access is granted. */
    fun migratePrivateToSelected(context: Context): Boolean {
        if (!isEnabled(context) || !hasSelectedFolderAccess(context)) return false
        val target = safRoot(context, createDirs = true) ?: return false
        return runCatching {
            copyDirectoryToSaf(context, privateRoot(context), target)
            if (canUsePublicFolder()) copyDirectoryToSaf(context, publicRoot(), target)
            true
        }.getOrDefault(false)
    }

    /** Copies local note files to Documents/.callreport after shared access is granted. */
    fun migratePrivateToPublic(context: Context): Boolean {
        if (!isEnabled(context)) return false
        if (hasSelectedFolderAccess(context)) return migratePrivateToSelected(context)
        if (!canUsePublicFolder()) return false
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
        val line = readLastNonBlankLine(context, callLogRef(context, phoneKey, createDirs = false))
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
        val ref = callLogRef(context, phoneKey, createDirs = false) ?: return ""
        if (!exists(ref)) return ""
        return runCatching {
            readLines(context, ref).asReversed().firstNotNullOfOrNull { line ->
                val json = runCatching { JSONObject(line) }.getOrNull() ?: return@firstNotNullOfOrNull null
                if (!sameCall(json, callAt, direction)) return@firstNotNullOfOrNull null
                json.optString("note").trim().takeIf { it.isNotBlank() }
            }.orEmpty()
        }.getOrDefault("")
    }

    fun companyIdForCall(context: Context, phoneNumber: String, callAt: Long, direction: String = ""): String {
        val phoneKey = phoneNumber.normalizePhoneKey()
        if (phoneKey.isBlank() || callAt <= 0L || !canUseConfiguredFolder(context)) return ""
        val ref = callLogRef(context, phoneKey, createDirs = false) ?: return ""
        if (!exists(ref)) return ""
        return runCatching {
            readLines(context, ref).asReversed().firstNotNullOfOrNull { line ->
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
        val ref = callLogRef(context, phoneKey, createDirs = false) ?: return emptyList()
        if (!exists(ref)) return emptyList()
        return runCatching {
            val seen = linkedSetOf<String>()
            readLines(context, ref).asReversed().mapNotNull { line ->
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
        val ref = profileRef(context, phoneKey, createDirs = false) ?: return ""
        if (!exists(ref)) return ""
        return runCatching { JSONObject(readText(context, ref)).optString("general_note") }.getOrDefault("").trim()
    }

    fun saveUnknownGeneralNote(context: Context, phoneNumber: String, note: String): Boolean {
        val phoneKey = phoneNumber.normalizePhoneKey()
        if (phoneKey.isBlank() || !canUseConfiguredFolder(context)) return false
        return runCatching {
            val now = System.currentTimeMillis()
            val ref = profileRef(context, phoneKey, createDirs = true) ?: return false
            val profile = if (exists(ref)) runCatching { JSONObject(readText(context, ref)) }.getOrDefault(JSONObject()) else JSONObject()
            profile.put("v", 1)
            profile.put("phone", phoneNumber)
            profile.put("normalized_phone", phoneKey)
            profile.put("has_android_contact", false)
            profile.put("general_note", note.trim())
            profile.put("general_note_at", now)
            profile.put("updated_at", now)
            writeText(context, ref, profile.toString(2))
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
            val ref = callLogRef(context, phoneKey, createDirs = true) ?: return false
            if (callAt > 0L && exists(ref)) {
                val keptLines = readLines(context, ref).filterNot { line ->
                    val json = runCatching { JSONObject(line) }.getOrNull() ?: return@filterNot false
                    sameCall(json, callAt, direction)
                }
                writeText(context, ref, (keptLines + record.toString()).joinToString("\n") + "\n")
            } else {
                appendText(context, ref, record.toString() + "\n")
            }
            if (isUnknownContact) writeUnknownLatestProfile(context, phoneNumber, phoneKey, trimmedNote, now)
            true
        }.getOrDefault(false)
    }

    private sealed class NoteFileRef {
        data class Plain(val file: File) : NoteFileRef()
        data class Saf(val document: DocumentFile) : NoteFileRef()
    }

    private fun exists(ref: NoteFileRef): Boolean = when (ref) {
        is NoteFileRef.Plain -> ref.file.exists()
        is NoteFileRef.Saf -> ref.document.exists()
    }

    private fun readText(context: Context, ref: NoteFileRef): String = when (ref) {
        is NoteFileRef.Plain -> if (ref.file.exists()) ref.file.readText() else ""
        is NoteFileRef.Saf -> context.contentResolver.openInputStream(ref.document.uri)?.bufferedReader()?.use { it.readText() }.orEmpty()
    }

    private fun writeText(context: Context, ref: NoteFileRef, value: String) {
        when (ref) {
            is NoteFileRef.Plain -> {
                ref.file.parentFile?.mkdirs()
                ref.file.writeText(value)
            }
            is NoteFileRef.Saf -> context.contentResolver.openOutputStream(ref.document.uri, "wt")?.bufferedWriter()?.use { it.write(value) }
        }
    }

    private fun appendText(context: Context, ref: NoteFileRef, value: String) {
        when (ref) {
            is NoteFileRef.Plain -> {
                ref.file.parentFile?.mkdirs()
                ref.file.appendText(value)
            }
            is NoteFileRef.Saf -> writeText(context, ref, readText(context, ref) + value)
        }
    }

    private fun readLines(context: Context, ref: NoteFileRef): List<String> = readText(context, ref).lineSequence().filter { it.isNotEmpty() }.toList()

    private fun callLogRef(context: Context, phoneKey: String, createDirs: Boolean): NoteFileRef? {
        if (usesSelectedFolder(context)) {
            val dir = safPhoneDir(context, phoneKey, createDirs) ?: return null
            return fileInSafDir(dir, CALL_LOG_FILE, "text/plain", createDirs)?.let(NoteFileRef::Saf)
        }
        return NoteFileRef.Plain(callLogFile(context, phoneKey, createDirs))
    }

    private fun profileRef(context: Context, phoneKey: String, createDirs: Boolean): NoteFileRef? {
        if (usesSelectedFolder(context)) {
            val dir = safPhoneDir(context, phoneKey, createDirs) ?: return null
            return fileInSafDir(dir, PROFILE_FILE, "application/json", createDirs)?.let(NoteFileRef::Saf)
        }
        return NoteFileRef.Plain(profileFile(context, phoneKey, createDirs))
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

    private fun copyDirectoryToSaf(context: Context, source: File, targetDir: DocumentFile) {
        if (!source.exists()) return
        if (source.isDirectory) {
            source.listFiles().orEmpty().forEach { child ->
                if (child.isDirectory) {
                    val childTarget = directoryInSafDir(targetDir, child.name, createDirs = true) ?: return@forEach
                    copyDirectoryToSaf(context, child, childTarget)
                } else {
                    val childTarget = fileInSafDir(targetDir, child.name, "application/octet-stream", createDirs = true) ?: return@forEach
                    context.contentResolver.openOutputStream(childTarget.uri, "wt")?.use { output ->
                        child.inputStream().use { input -> input.copyTo(output) }
                    }
                }
            }
        }
    }

    private fun privateRoot(context: Context): File = File(context.filesDir, ROOT_DIR)

    private fun publicRoot(): File = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
        ROOT_DIR,
    )

    private fun selectedRootPath(context: Context): String {
        val tree = selectedRootDocument(context) ?: return "избрана папка/$ROOT_DIR"
        val folderName = tree.name.orEmpty().ifBlank { "избрана папка" }
        return if (folderName == ROOT_DIR) folderName else "$folderName/$ROOT_DIR"
    }

    private fun selectedRootDocument(context: Context): DocumentFile? =
        selectedFolderUri(context)?.let { DocumentFile.fromTreeUri(context, it) }

    private fun safRoot(context: Context, createDirs: Boolean): DocumentFile? {
        val tree = selectedRootDocument(context) ?: return null
        if (tree.name == ROOT_DIR) return tree
        return directoryInSafDir(tree, ROOT_DIR, createDirs)
    }

    private fun safPhoneDir(context: Context, phoneKey: String, createDirs: Boolean): DocumentFile? {
        val key = PhoneNormalizer.key(phoneKey)
        val root = safRoot(context, createDirs) ?: return null
        val notes = directoryInSafDir(root, NOTES_DIR, createDirs) ?: return null
        val first = directoryInSafDir(notes, key.take(3), createDirs) ?: return null
        val second = directoryInSafDir(first, key.drop(3).take(3), createDirs) ?: return null
        return directoryInSafDir(second, key, createDirs)
    }

    private fun directoryInSafDir(parent: DocumentFile, name: String, createDirs: Boolean): DocumentFile? {
        val existing = parent.listFiles().firstOrNull { it.name == name && it.isDirectory }
        if (existing != null) return existing
        return if (createDirs) parent.createDirectory(name) else null
    }

    private fun fileInSafDir(parent: DocumentFile, name: String, mimeType: String, createDirs: Boolean): DocumentFile? {
        val existing = parent.listFiles().firstOrNull { it.name == name && it.isFile }
        if (existing != null) return existing
        return if (createDirs) parent.createFile(mimeType, name) else null
    }

    private fun sameCall(json: JSONObject, callAt: Long, direction: String): Boolean {
        if (callAt <= 0L) return false
        val sameCallAt = json.optLong("call_at", 0L) == callAt
        if (!sameCallAt) return false
        val rowDirection = json.optString("direction")
        return direction.isBlank() || rowDirection.isBlank() || rowDirection == direction || exactTimestampWins(callAt)
    }

    private fun exactTimestampWins(callAt: Long): Boolean = callAt > 0L

    private fun writeUnknownLatestProfile(context: Context, phoneNumber: String, phoneKey: String, latestNote: String, updatedAt: Long) {
        val ref = profileRef(context, phoneKey, createDirs = true) ?: return
        val profile = if (exists(ref)) runCatching { JSONObject(readText(context, ref)) }.getOrDefault(JSONObject()) else JSONObject()
        profile.put("v", 1)
        profile.put("phone", phoneNumber)
        profile.put("normalized_phone", phoneKey)
        profile.put("has_android_contact", false)
        profile.put("latest_note", latestNote)
        profile.put("latest_note_at", updatedAt)
        profile.put("updated_at", updatedAt)
        writeText(context, ref, profile.toString(2))
    }

    private fun callLogFile(context: Context, phoneKey: String, createDirs: Boolean): File = File(phoneDir(context, phoneKey, createDirs), CALL_LOG_FILE)

    private fun profileFile(context: Context, phoneKey: String, createDirs: Boolean): File = File(phoneDir(context, phoneKey, createDirs), PROFILE_FILE)

    private fun phoneDir(context: Context, phoneKey: String, createDirs: Boolean): File {
        val key = PhoneNormalizer.key(phoneKey)
        val root = if (usesPublicFolder(context)) publicRoot() else privateRoot(context)
        val dir = File(File(File(root, NOTES_DIR), key.take(3)), "${key.drop(3).take(3)}/$key")
        if (createDirs) dir.mkdirs()
        return dir
    }

    private fun readLastNonBlankLine(context: Context, ref: NoteFileRef?): String {
        ref ?: return ""
        return when (ref) {
            is NoteFileRef.Plain -> readLastNonBlankLine(ref.file)
            is NoteFileRef.Saf -> readText(context, ref).lineSequence().lastOrNull { it.isNotBlank() }?.trim().orEmpty()
        }
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

    private fun String.normalizePhoneKey(): String = PhoneNormalizer.key(this)
}
