package com.onlineimoti.calllog

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import java.io.File

internal object LocalNotesMigration {
    fun migratePrivateToSelected(context: Context): Boolean {
        if (!LocalNotesWorkspace.isEnabled(context) ||
            !LocalNotesWorkspace.hasSelectedFolderAccess(context)
        ) return false
        return runCatching {
            val selected = LocalNotesWorkspace.selectedRootDocument(context) ?: return false
            if (selected.name == LocalNotesWorkspace.NOTES_DIR) {
                copyDirectoryToSaf(
                    context,
                    File(LocalNotesWorkspace.privateRoot(context), LocalNotesWorkspace.NOTES_DIR),
                    selected,
                )
                if (LocalNotesWorkspace.canUsePublicFolder()) {
                    copyDirectoryToSaf(
                        context,
                        File(LocalNotesWorkspace.publicRoot(), LocalNotesWorkspace.NOTES_DIR),
                        selected,
                    )
                }
            } else {
                val target = LocalNotesWorkspace.safRoot(context, createDirs = true) ?: return false
                copyDirectoryToSaf(context, LocalNotesWorkspace.privateRoot(context), target)
                if (LocalNotesWorkspace.canUsePublicFolder()) {
                    copyDirectoryToSaf(context, LocalNotesWorkspace.publicRoot(), target)
                }
            }
            true
        }.getOrDefault(false)
    }

    fun migratePrivateToPublic(context: Context): Boolean {
        if (!LocalNotesWorkspace.isEnabled(context)) return false
        if (LocalNotesWorkspace.hasSelectedFolderAccess(context)) {
            return migratePrivateToSelected(context)
        }
        if (!LocalNotesWorkspace.canUsePublicFolder()) return false
        val source = LocalNotesWorkspace.privateRoot(context)
        if (!source.exists()) return true
        val target = LocalNotesWorkspace.publicRoot()
        return runCatching {
            copyDirectory(source, target)
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

    private fun copyDirectoryToSaf(context: Context, source: File, targetDir: DocumentFile) {
        if (!source.exists()) return
        if (source.isDirectory) {
            source.listFiles().orEmpty().forEach { child ->
                if (child.isDirectory) {
                    val childTarget = LocalNotesWorkspace.directoryInSafDir(
                        targetDir,
                        child.name,
                        createDirs = true,
                    ) ?: return@forEach
                    copyDirectoryToSaf(context, child, childTarget)
                } else {
                    val childTarget = LocalNotesWorkspace.fileInSafDir(
                        targetDir,
                        child.name,
                        "application/octet-stream",
                        createDirs = true,
                    ) ?: return@forEach
                    context.contentResolver.openOutputStream(childTarget.uri, "wt")?.use { output ->
                        child.inputStream().use { input -> input.copyTo(output) }
                    }
                }
            }
        }
    }
}
