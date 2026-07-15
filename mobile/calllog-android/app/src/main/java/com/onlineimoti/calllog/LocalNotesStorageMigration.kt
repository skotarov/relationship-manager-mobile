package com.onlineimoti.calllog

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import java.io.File

internal object LocalNotesStorageMigration {
    fun migratePrivateToPublic(context: Context): Boolean = runCatching {
        if (!LocalNotesStorageLocation.canUsePublicFolder(context)) return@runCatching false
        val source = LocalNotesStorageLocation.appPrivateRoot(context)
        val target = File(LocalNotesStorageLocation.publicRootPath())
        if (!source.exists()) {
            target.mkdirs()
            return@runCatching true
        }
        copyDirectory(source, target)
        true
    }.getOrDefault(false)

    fun migratePrivateToSelected(context: Context): Boolean = runCatching {
        if (!LocalNotesStorageLocation.hasSelectedFolderAccess(context)) return@runCatching false
        val source = LocalNotesStorageLocation.appPrivateRoot(context)
        if (!source.exists()) return@runCatching true
        val target = LocalNotesStorageLocation.selectedNotesDir(context, createDirs = true)
            ?: return@runCatching false
        val sourceNotes = File(source, LocalNotesStorageLocation.NOTES_DIR)
        if (sourceNotes.exists()) copyDirectoryToSaf(context, sourceNotes, target)
        true
    }.getOrDefault(false)

    private fun copyDirectory(source: File, target: File) {
        source.walkTopDown().forEach { src ->
            val relative = src.relativeTo(source)
            val dst = File(target, relative.path)
            if (src.isDirectory) dst.mkdirs()
            else {
                dst.parentFile?.mkdirs()
                if (!dst.exists() || dst.length() == 0L) src.copyTo(dst, overwrite = true)
            }
        }
    }

    private fun copyDirectoryToSaf(context: Context, source: File, target: DocumentFile) {
        source.listFiles().orEmpty().forEach { src ->
            if (src.isDirectory) {
                val child = LocalNotesStorageLocation.directoryInSafDir(
                    target,
                    src.name,
                    createDirs = true,
                )
                if (child != null) copyDirectoryToSaf(context, src, child)
            } else {
                val doc = LocalNotesStorageLocation.fileInSafDir(
                    target,
                    src.name,
                    if (src.name == LocalNotesStorageLocation.PROFILE_FILE) {
                        "application/json"
                    } else {
                        "application/octet-stream"
                    },
                    createDirs = true,
                )
                if (doc != null && doc.length() == 0L) {
                    context.contentResolver.openOutputStream(doc.uri, "wt")?.use { output ->
                        src.inputStream().use { input -> input.copyTo(output) }
                    }
                }
            }
        }
    }
}
