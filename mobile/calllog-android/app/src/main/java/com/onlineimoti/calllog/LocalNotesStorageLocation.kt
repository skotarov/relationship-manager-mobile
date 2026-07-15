package com.onlineimoti.calllog

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import java.io.File

internal object LocalNotesStorageLocation {
    const val ROOT_DIR = ".callreport"
    const val NOTES_DIR = "NOTES"
    const val CALL_LOG_FILE = "CALL_LOG"
    const val PROFILE_FILE = "PROFILE"
    const val CALL_LOG_FILE_TEXT = "CALL_LOG.txt"
    const val PROFILE_FILE_JSON = "PROFILE.json"

    fun canUsePublicFolder(context: Context): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()
    }

    fun selectedFolderUri(context: Context): Uri? {
        val uriValue = ConfigStore.load(context).localNotesFolderUri.trim()
        return uriValue.takeIf { it.isNotBlank() }?.let(Uri::parse)
    }

    fun hasSelectedFolderAccess(context: Context): Boolean {
        val uri = selectedFolderUri(context) ?: return false
        val persisted = context.contentResolver.persistedUriPermissions
            .any { it.uri == uri && it.isReadPermission && it.isWritePermission }
        if (!persisted) return false
        return runCatching { DocumentFile.fromTreeUri(context, uri)?.canWrite() == true }.getOrDefault(false)
    }

    fun usesSelectedFolder(context: Context): Boolean = selectedFolderUri(context) != null

    fun usesPublicFolder(context: Context): Boolean =
        !usesSelectedFolder(context) && ConfigStore.load(context).usePublicLocalNotesStorage

    fun publicRootPath(): String = publicRoot().absolutePath

    fun privateRootPath(context: Context): String = privateRoot(context).absolutePath

    fun activeRootPath(context: Context): String = when {
        usesSelectedFolder(context) -> selectedRootPath(context)
        usesPublicFolder(context) -> publicRootPath()
        else -> privateRootPath(context)
    }

    fun appPrivateRoot(context: Context): File = privateRoot(context)

    fun ensurePlainStorage(context: Context): Boolean = runCatching {
        val root = plainRoot(context)
        val notes = File(root, NOTES_DIR)
        if (!root.exists() && !root.mkdirs()) return@runCatching false
        if (!notes.exists() && !notes.mkdirs()) return@runCatching false
        notes.canWrite()
    }.getOrDefault(false)

    fun ensureSelectedStorage(context: Context): Boolean = runCatching {
        val root = selectedRootDocument(context) ?: return@runCatching false
        if (root.name == NOTES_DIR) return@runCatching root.canWrite()
        val selected = safRoot(context, createDirs = true) ?: return@runCatching false
        directoryInSafDir(selected, NOTES_DIR, createDirs = true)?.canWrite() == true
    }.getOrDefault(false)

    fun requestManageAllFilesSettings(context: Context) =
        android.content.Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
            data = Uri.parse("package:${context.packageName}")
        }

    fun canReadAndWritePublicStorage(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return Environment.isExternalStorageManager()
        val read = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        @Suppress("DEPRECATION")
        val write = ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        return read && write
    }

    fun callLogFile(context: Context, phoneKey: String, createDirs: Boolean): File =
        File(phoneDir(context, phoneKey, createDirs), CALL_LOG_FILE)

    fun profileFile(context: Context, phoneKey: String, createDirs: Boolean): File =
        File(phoneDir(context, phoneKey, createDirs), PROFILE_FILE)

    fun plainProfileFiles(context: Context): List<File> {
        val notes = File(plainRoot(context), NOTES_DIR)
        if (!notes.exists()) return emptyList()
        return notes.walkTopDown()
            .filter { it.isFile && (it.name == PROFILE_FILE || it.name == PROFILE_FILE_JSON) }
            .toList()
    }

    fun plainCallLogFiles(context: Context): List<File> {
        val notes = File(plainRoot(context), NOTES_DIR)
        if (!notes.exists()) return emptyList()
        return notes.walkTopDown()
            .filter { it.isFile && (it.name == CALL_LOG_FILE || it.name == CALL_LOG_FILE_TEXT) }
            .toList()
    }

    fun safProfileDocuments(context: Context): List<DocumentFile> =
        safNotesDirs(context, createDirs = false)
            .flatMap { notes -> walkSafFiles(notes).toList() }
            .filter { it.isFile && safFileNameMatches(it.name.orEmpty(), PROFILE_FILE) }
            .distinctBy { it.uri }

    fun safCallLogDocuments(context: Context): List<DocumentFile> =
        safNotesDirs(context, createDirs = false)
            .flatMap { notes -> walkSafFiles(notes).toList() }
            .filter { it.isFile && safFileNameMatches(it.name.orEmpty(), CALL_LOG_FILE) }
            .distinctBy { it.uri }

    fun safPhoneDir(context: Context, phoneKey: String, createDirs: Boolean): DocumentFile? {
        val key = PhoneNormalizer.key(phoneKey)
        if (key.isBlank()) return null
        safNotesDirs(context, createDirs = false).forEach { notes ->
            phoneDirInSafNotes(notes, key, createDirs = false)?.let { return it }
        }
        if (!createDirs) return null
        val notes = safNotesDir(context, createDirs = true) ?: return null
        return phoneDirInSafNotes(notes, key, createDirs = true)
    }

    fun fileInSafDir(
        parent: DocumentFile,
        name: String,
        mimeType: String,
        createDirs: Boolean,
    ): DocumentFile? {
        val existing = parent.listFiles().firstOrNull {
            it.isFile && safFileNameMatches(it.name.orEmpty(), name)
        }
        if (existing != null) return existing
        return if (createDirs) parent.createFile(mimeType, name) else null
    }

    fun selectedNotesDir(context: Context, createDirs: Boolean): DocumentFile? =
        safNotesDir(context, createDirs)

    fun directoryInSafDir(parent: DocumentFile, name: String, createDirs: Boolean): DocumentFile? {
        val existing = parent.listFiles().firstOrNull { it.name == name && it.isDirectory }
        if (existing != null) return existing
        return if (createDirs) parent.createDirectory(name) else null
    }

    private fun privateRoot(context: Context): File = File(context.filesDir, ROOT_DIR)

    private fun publicRoot(): File = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
        ROOT_DIR,
    )

    private fun plainRoot(context: Context): File =
        if (usesPublicFolder(context)) publicRoot() else privateRoot(context)

    private fun phoneDir(context: Context, phoneKey: String, createDirs: Boolean): File {
        val key = PhoneNormalizer.key(phoneKey)
        val dir = File(
            File(File(plainRoot(context), NOTES_DIR), key.take(3)),
            "${key.drop(3).take(3)}/$key",
        )
        if (createDirs) dir.mkdirs()
        return dir
    }

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

    private fun phoneDirInSafNotes(
        notes: DocumentFile,
        key: String,
        createDirs: Boolean,
    ): DocumentFile? {
        val first = directoryInSafDir(notes, key.take(3), createDirs) ?: return null
        val second = directoryInSafDir(first, key.drop(3).take(3), createDirs) ?: return null
        return directoryInSafDir(second, key, createDirs)
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
}
