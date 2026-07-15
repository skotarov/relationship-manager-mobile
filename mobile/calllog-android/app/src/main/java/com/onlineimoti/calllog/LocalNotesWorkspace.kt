package com.onlineimoti.calllog

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.documentfile.provider.DocumentFile
import java.io.File

internal object LocalNotesWorkspace {
    const val ROOT_DIR = ".callreport"
    const val NOTES_DIR = "notes"
    const val CALL_LOG_FILE = "calllog.notes"
    const val CALL_LOG_FILE_TEXT = "calllog.notes.txt"
    const val PROFILE_FILE = "profile.json"
    const val PROFILE_FILE_JSON = "profile.json.json"

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

    fun shouldUsePublicFolder(context: Context): Boolean =
        canUsePublicFolder() && !hasSelectedFolderAccess(context)

    fun usesSelectedFolder(context: Context): Boolean =
        isEnabled(context) && hasSelectedFolderAccess(context)

    fun usesPublicFolder(context: Context): Boolean =
        isEnabled(context) && !usesSelectedFolder(context) && canUsePublicFolder()

    fun canUseConfiguredFolder(context: Context): Boolean = isEnabled(context)

    fun publicRootPath(): String = publicRoot().absolutePath

    fun privateRootPath(context: Context): String = privateRoot(context).absolutePath

    fun activeRootPath(context: Context): String = when {
        !isEnabled(context) -> "изключено"
        usesSelectedFolder(context) -> selectedRootPath(context)
        usesPublicFolder(context) -> publicRootPath()
        else -> privateRootPath(context)
    }

    fun appPrivateRoot(context: Context): File = privateRoot(context)

    fun privateRoot(context: Context): File = File(context.filesDir, ROOT_DIR)

    fun publicRoot(): File = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
        ROOT_DIR,
    )

    fun plainRoot(context: Context): File =
        if (usesPublicFolder(context)) publicRoot() else privateRoot(context)

    fun selectedRootDocument(context: Context): DocumentFile? =
        selectedFolderUri(context)?.let { DocumentFile.fromTreeUri(context, it) }

    fun safRoot(context: Context, createDirs: Boolean): DocumentFile? {
        val tree = selectedRootDocument(context) ?: return null
        if (tree.name == NOTES_DIR) return null
        if (tree.name == ROOT_DIR) return tree
        return tree.takeIf { it.canWrite() || !createDirs }
    }

    fun safNotesDir(context: Context, createDirs: Boolean): DocumentFile? =
        safNotesDirs(context, createDirs).firstOrNull()

    fun safNotesDirs(context: Context, createDirs: Boolean): List<DocumentFile> {
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

    fun directoryInSafDir(parent: DocumentFile, name: String, createDirs: Boolean): DocumentFile? {
        val existing = parent.listFiles().firstOrNull { it.name == name && it.isDirectory }
        if (existing != null) return existing
        return if (createDirs) parent.createDirectory(name) else null
    }

    fun fileInSafDir(parent: DocumentFile, name: String, mimeType: String, createDirs: Boolean): DocumentFile? {
        val existing = parent.listFiles().firstOrNull {
            it.isFile && safFileNameMatches(it.name.orEmpty(), name)
        }
        if (existing != null) return existing
        return if (createDirs) parent.createFile(mimeType, name) else null
    }

    fun walkSafFiles(root: DocumentFile): Sequence<DocumentFile> = sequence {
        root.listFiles().forEach { child ->
            when {
                child.isDirectory -> yieldAll(walkSafFiles(child))
                child.isFile -> yield(child)
            }
        }
    }

    fun safFileNameMatches(actual: String, expected: String): Boolean {
        if (actual == expected) return true
        return when (expected) {
            CALL_LOG_FILE -> actual == CALL_LOG_FILE_TEXT
            PROFILE_FILE -> actual == PROFILE_FILE_JSON
            else -> false
        }
    }

    fun callLogFile(context: Context, phoneKey: String, createDirs: Boolean): File =
        File(phoneDir(context, phoneKey, createDirs), CALL_LOG_FILE)

    fun profileFile(context: Context, phoneKey: String, createDirs: Boolean): File =
        File(phoneDir(context, phoneKey, createDirs), PROFILE_FILE)

    private fun selectedRootPath(context: Context): String {
        val tree = selectedRootDocument(context) ?: return "избрана папка"
        return tree.name.orEmpty().ifBlank { "избрана папка" }
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

    private fun phoneDir(context: Context, phoneKey: String, createDirs: Boolean): File {
        val key = PhoneNormalizer.key(phoneKey)
        val dir = File(
            File(File(plainRoot(context), NOTES_DIR), key.take(3)),
            "${key.drop(3).take(3)}/$key",
        )
        if (createDirs) dir.mkdirs()
        return dir
    }
}
