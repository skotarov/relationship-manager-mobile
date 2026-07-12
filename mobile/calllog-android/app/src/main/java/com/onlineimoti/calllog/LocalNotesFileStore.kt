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

internal data class LocalStoredGeneralNote(
    val phone: String,
    val phoneKey: String,
    val note: String,
    val noteAt: Long,
)

internal data class LocalStoredCallNote(
    val phone: String,
    val phoneKey: String,
    val note: String,
    val noteAt: Long,
    val callAt: Long,
    val direction: String,
    val durationSeconds: Long,
)

/**
 * Local notes use one active root:
 * - a user-selected Storage Access Framework folder, when configured;
 * - legacy Documents/.callreport only when broad storage access already exists;
 * - otherwise the private .callreport folder of this app.
 *
 * The selected SAF folder is now the workspace itself. New writes go to
 * <selected>/notes/..., while older <selected>/.callreport/notes/... files are
 * still read so existing archives keep working.
 */
object LocalNotesFileStore {
    private const val ROOT_DIR = ".callreport"
    private const val NOTES_DIR = "notes"
    private const val CALL_LOG_FILE = "calllog.notes"
    private const val CALL_LOG_FILE_TEXT = "calllog.notes.txt"
    private const val PROFILE_FILE = "profile.json"
    private const val PROFILE_FILE_JSON = "profile.json.json"

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
        val uriString = uri.toString()
        val current = ConfigStore.load(context)
        val portable = SelectedFolderConfigBackup.load(context, uriString)
        ConfigStore.save(
            context,
            (portable ?: current).copy(
                useLocalNotesStorage = true,
                localNotesFolderUri = uriString,
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
        return runCatching {
            val selected = selectedRootDocument(context) ?: return false
            if (selected.name == NOTES_DIR) {
                copyDirectoryToSaf(context, File(privateRoot(context), NOTES_DIR), selected)
                if (canUsePublicFolder()) copyDirectoryToSaf(context, File(publicRoot(), NOTES_DIR), selected)
            } else {
                val target = safRoot(context, createDirs = true) ?: return false
                copyDirectoryToSaf(context, privateRoot(context), target)
                if (canUsePublicFolder()) copyDirectoryToSaf(context, publicRoot(), target)
            }
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
                val note = json.optString("note").trim()
                if (note.isBlank()) return@mapNotNull null
                val callAt = json.optLong("call_at", 0L)
                if (json.optString("type").isNotBlank() && json.optString("type") != "call_note") return@mapNotNull null
                if (callAt <= 0L && json.optString("type") != "call_note") return@mapNotNull null
                val direction = json.optString("direction")
                val clientNoteId = json.optString("id").ifBlank { clientNoteIdForCall(phoneNumber, callAt, direction) }
                val key = if (callAt > 0L) "$callAt-$direction" else "$callAt-${direction.ifBlank { "call" }}"
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
        return runCatching {
            val json = JSONObject(readText(context, ref))
            // latest_note tracks the most recent blue/call note and must never be
            // shown as the yellow/general note after the general note is deleted.
            json.optString("general_note").trim()
                .ifBlank { json.optString("note").trim() }
        }.getOrDefault("")
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

    fun deleteGeneralNote(context: Context, phoneNumber: String): Boolean {
        val phoneKey = phoneNumber.normalizePhoneKey()
        if (phoneKey.isBlank() || !canUseConfiguredFolder(context)) return true
        return runCatching {
            val ref = profileRef(context, phoneKey, createDirs = false) ?: return@runCatching true
            if (!exists(ref)) return@runCatching true
            val profile = runCatching { JSONObject(readText(context, ref)) }.getOrDefault(JSONObject())
            profile.remove("general_note")
            profile.remove("general_note_at")
            profile.remove("note")
            cleanupOrWriteProfile(context, ref, phoneNumber, phoneKey, profile)
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

    fun deleteCallNote(context: Context, phoneNumber: String, callAt: Long, direction: String): Boolean {
        val phoneKey = phoneNumber.normalizePhoneKey()
        if (phoneKey.isBlank()) return false
        if (callAt <= 0L || !canUseConfiguredFolder(context)) return true
        return runCatching {
            val ref = callLogRef(context, phoneKey, createDirs = false) ?: return@runCatching true
            if (!exists(ref)) return@runCatching true
            val keptLines = readLines(context, ref).filterNot { line ->
                val json = runCatching { JSONObject(line) }.getOrNull() ?: return@filterNot false
                sameCall(json, callAt, direction)
            }
            if (keptLines.isEmpty()) deleteRef(ref) else writeText(context, ref, keptLines.joinToString("\n") + "\n")
            refreshLatestProfileNote(context, phoneNumber, phoneKey)
            true
        }.getOrDefault(false)
    }

    internal fun storedGeneralNotes(context: Context): List<LocalStoredGeneralNote> {
        if (!canUseConfiguredFolder(context)) return emptyList()
        return profileRefs(context)
            .mapNotNull { ref -> parseStoredGeneralNote(context, ref) }
            .filter { it.note.isNotBlank() && it.phoneKey.isNotBlank() }
    }

    internal fun storedCallNotes(context: Context): List<LocalStoredCallNote> {
        if (!canUseConfiguredFolder(context)) return emptyList()
        return callLogRefs(context)
            .flatMap { ref -> parseStoredCallNotes(context, ref) }
            .filter { it.note.isNotBlank() && it.phoneKey.isNotBlank() }
    }

    private sealed class NoteFileRef {
        data class Plain(val file: File) : NoteFileRef()
        data class Saf(val document: DocumentFile) : NoteFileRef()
    }

    private fun exists(ref: NoteFileRef): Boolean = when (ref) {
        is NoteFileRef.Plain -> ref.file.exists()
        is NoteFileRef.Saf -> ref.document.exists()
    }

    private fun deleteRef(ref: NoteFileRef) {
        when (ref) {
            is NoteFileRef.Plain -> ref.file.delete()
            is NoteFileRef.Saf -> ref.document.delete()
        }
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
            return fileInSafDir(dir, CALL_LOG_FILE, "application/octet-stream", createDirs)?.let(NoteFileRef::Saf)
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

    private fun profileRefs(context: Context): List<NoteFileRef> {
        if (usesSelectedFolder(context)) {
            return safNotesDirs(context, createDirs = false)
                .flatMap { notes -> walkSafFiles(notes).toList() }
                .filter { it.isFile && safFileNameMatches(it.name.orEmpty(), PROFILE_FILE) }
                .distinctBy { it.uri }
                .map { NoteFileRef.Saf(it) }
        }
        val notes = File(plainRoot(context), NOTES_DIR)
        if (!notes.exists()) return emptyList()
        return notes.walkTopDown()
            .filter { it.isFile && (it.name == PROFILE_FILE || it.name == PROFILE_FILE_JSON) }
            .map { NoteFileRef.Plain(it) }
            .toList()
    }

    private fun callLogRefs(context: Context): List<NoteFileRef> {
        if (usesSelectedFolder(context)) {
            return safNotesDirs(context, createDirs = false)
                .flatMap { notes -> walkSafFiles(notes).toList() }
                .filter { it.isFile && safFileNameMatches(it.name.orEmpty(), CALL_LOG_FILE) }
                .distinctBy { it.uri }
                .map { NoteFileRef.Saf(it) }
        }
        val notes = File(plainRoot(context), NOTES_DIR)
        if (!notes.exists()) return emptyList()
        return notes.walkTopDown()
            .filter { it.isFile && (it.name == CALL_LOG_FILE || it.name == CALL_LOG_FILE_TEXT) }
            .map { NoteFileRef.Plain(it) }
            .toList()
    }

    private fun parseStoredGeneralNote(context: Context, ref: NoteFileRef): LocalStoredGeneralNote? {
        val json = runCatching { JSONObject(readText(context, ref)) }.getOrNull() ?: return null
        val phone = json.optString("phone").ifBlank { json.optString("normalized_phone") }
        val phoneKey = PhoneNormalizer.key(json.optString("normalized_phone").ifBlank { phone })
        if (phoneKey.isBlank()) return null
        val note = json.optString("general_note").trim()
            .ifBlank { json.optString("note").trim() }
        if (note.isBlank()) return null
        return LocalStoredGeneralNote(
            phone = phone.ifBlank { phoneKey },
            phoneKey = phoneKey,
            note = note,
            noteAt = maxOf(json.optLong("general_note_at", 0L), json.optLong("updated_at", 0L)),
        )
    }

    private fun parseStoredCallNotes(context: Context, ref: NoteFileRef): List<LocalStoredCallNote> {
        return runCatching {
            readLines(context, ref).mapNotNull { line ->
                val json = runCatching { JSONObject(line) }.getOrNull() ?: return@mapNotNull null
                val type = json.optString("type")
                val callAt = json.optLong("call_at", 0L)
                if (type.isNotBlank() && type != "call_note") return@mapNotNull null
                if (type.isBlank() && callAt <= 0L) return@mapNotNull null
                val note = json.optString("note").trim()
                if (note.isBlank()) return@mapNotNull null
                val phone = json.optString("phone").ifBlank { json.optString("normalized_phone") }
                val phoneKey = PhoneNormalizer.key(json.optString("normalized_phone").ifBlank { phone })
                if (phoneKey.isBlank()) return@mapNotNull null
                LocalStoredCallNote(
                    phone = phone.ifBlank { phoneKey },
                    phoneKey = phoneKey,
                    note = note,
                    noteAt = json.optLong("at", 0L),
                    callAt = callAt,
                    direction = json.optString("direction"),
                    durationSeconds = json.optLong("duration", 0L),
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun refreshLatestProfileNote(context: Context, phoneNumber: String, phoneKey: String) {
        if (!canUseConfiguredFolder(context)) return
        runCatching {
            val profileRef = profileRef(context, phoneKey, createDirs = false) ?: return@runCatching
            if (!exists(profileRef)) return@runCatching
            val profile = runCatching { JSONObject(readText(context, profileRef)) }.getOrDefault(JSONObject())
            val latestLine = readLastNonBlankLine(context, callLogRef(context, phoneKey, createDirs = false))
            val latestNote = if (latestLine.isBlank()) "" else runCatching { JSONObject(latestLine).optString("note") }.getOrDefault("").trim()
            if (latestNote.isBlank()) {
                profile.remove("latest_note")
                profile.remove("latest_note_at")
            } else {
                profile.put("latest_note", latestNote)
                profile.put("latest_note_at", System.currentTimeMillis())
            }
            cleanupOrWriteProfile(context, profileRef, phoneNumber, phoneKey, profile)
        }
    }

    private fun cleanupOrWriteProfile(
        context: Context,
        ref: NoteFileRef,
        phoneNumber: String,
        phoneKey: String,
        profile: JSONObject,
    ) {
        val hasGeneral = profile.optString("general_note").trim().isNotBlank() || profile.optString("note").trim().isNotBlank()
        val hasLatest = profile.optString("latest_note").trim().isNotBlank()
        if (!hasGeneral && !hasLatest) {
            deleteRef(ref)
            return
        }
        profile.put("v", 1)
        profile.put("phone", phoneNumber)
        profile.put("normalized_phone", phoneKey)
        profile.put("has_android_contact", false)
        profile.put("storage", when {
            usesSelectedFolder(context) -> "selected"
            usesPublicFolder(context) -> "public"
            else -> "private"
        })
        profile.put("updated_at", System.currentTimeMillis())
        writeText(context, ref, profile.toString(2))
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

    private fun plainRoot(context: Context): File = if (usesPublicFolder(context)) publicRoot() else privateRoot(context)

    private fun selectedRootPath(context: Context): String {
        val tree = selectedRootDocument(context) ?: return "избрана папка"
        return tree.name.orEmpty().ifBlank { "избрана папка" }
    }

    private fun selectedRootDocument(context: Context): DocumentFile? =
        selectedFolderUri(context)?.let { DocumentFile.fromTreeUri(context, it) }

    private fun safRoot(context: Context, createDirs: Boolean): DocumentFile? {
        val tree = selectedRootDocument(context) ?: return null
        if (tree.name == NOTES_DIR) return null
        if (tree.name == ROOT_DIR) return tree
        return tree.takeIf { it.canWrite() || !createDirs }
    }

    private fun safNotesDir(context: Context, createDirs: Boolean): DocumentFile? =
        safNotesDirs(context, createDirs).firstOrNull()

    private fun safNotesDirs(context: Context, createDirs: Boolean): List<DocumentFile> {
        val tree = selectedRootDocument(context) ?: return emptyList()
        if (tree.name == NOTES_DIR) return listOf(tree)
        val result = arrayListOf<DocumentFile>()
        val root = safRoot(context, createDirs)
        if (root != null) {
            directoryInSafDir(root, NOTES_DIR, createDirs)?.let { result += it }
        }
        if (tree.name != ROOT_DIR) {
            val legacyRoot = directoryInSafDir(tree, ROOT_DIR, createDirs = false)
            legacyRoot?.let { legacy ->
                directoryInSafDir(legacy, NOTES_DIR, createDirs = false)?.let { result += it }
            }
        }
        return result.distinctBy { it.uri }
    }

    private fun safPhoneDir(context: Context, phoneKey: String, createDirs: Boolean): DocumentFile? {
        val key = PhoneNormalizer.key(phoneKey)
        if (key.isBlank()) return null
        safNotesDirs(context, createDirs = false).forEach { notes ->
            phoneDirInSafNotes(notes, key, createDirs = false)?.let { return it }
        }
        if (!createDirs) return null
        val notes = safNotesDir(context, createDirs = true) ?: return null
        return phoneDirInSafNotes(notes, key, createDirs = true)
    }

    private fun phoneDirInSafNotes(notes: DocumentFile, key: String, createDirs: Boolean): DocumentFile? {
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
        val existing = parent.listFiles().firstOrNull { it.isFile && safFileNameMatches(it.name.orEmpty(), name) }
        if (existing != null) return existing
        return if (createDirs) parent.createFile(mimeType, name) else null
    }

    private fun walkSafFiles(root: DocumentFile): Sequence<DocumentFile> = sequence {
        root.listFiles().forEach { child ->
            when {
                child.isDirectory -> yieldAll(walkSafFiles(child))
                child.isFile -> yield(child)
            }
        }
    }

    private fun safFileNameMatches(actual: String, expected: String): Boolean {
        if (actual == expected) return true
        return when (expected) {
            CALL_LOG_FILE -> actual == CALL_LOG_FILE_TEXT
            PROFILE_FILE -> actual == PROFILE_FILE_JSON
            else -> false
        }
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
        val dir = File(File(File(plainRoot(context), NOTES_DIR), key.take(3)), "${key.drop(3).take(3)}/$key")
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
