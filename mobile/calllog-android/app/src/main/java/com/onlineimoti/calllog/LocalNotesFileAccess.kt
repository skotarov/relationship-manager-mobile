package com.onlineimoti.calllog

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import java.io.File

internal sealed class LocalNoteFileRef {
    data class Plain(val file: File) : LocalNoteFileRef()
    data class Saf(val document: DocumentFile) : LocalNoteFileRef()
}

internal object LocalNotesFileAccess {
    fun exists(ref: LocalNoteFileRef): Boolean = when (ref) {
        is LocalNoteFileRef.Plain -> ref.file.exists()
        is LocalNoteFileRef.Saf -> ref.document.exists()
    }

    fun delete(ref: LocalNoteFileRef) {
        when (ref) {
            is LocalNoteFileRef.Plain -> ref.file.delete()
            is LocalNoteFileRef.Saf -> ref.document.delete()
        }
    }

    fun readText(context: Context, ref: LocalNoteFileRef): String = when (ref) {
        is LocalNoteFileRef.Plain -> if (ref.file.exists()) ref.file.readText() else ""
        is LocalNoteFileRef.Saf -> context.contentResolver
            .openInputStream(ref.document.uri)
            ?.bufferedReader()
            ?.use { it.readText() }
            .orEmpty()
    }

    fun writeText(context: Context, ref: LocalNoteFileRef, value: String) {
        when (ref) {
            is LocalNoteFileRef.Plain -> {
                ref.file.parentFile?.mkdirs()
                ref.file.writeText(value)
            }
            is LocalNoteFileRef.Saf -> context.contentResolver
                .openOutputStream(ref.document.uri, "wt")
                ?.bufferedWriter()
                ?.use { it.write(value) }
        }
    }

    fun appendText(context: Context, ref: LocalNoteFileRef, value: String) {
        when (ref) {
            is LocalNoteFileRef.Plain -> {
                ref.file.parentFile?.mkdirs()
                ref.file.appendText(value)
            }
            is LocalNoteFileRef.Saf -> writeText(context, ref, readText(context, ref) + value)
        }
    }

    fun readLines(context: Context, ref: LocalNoteFileRef): List<String> =
        readText(context, ref).lineSequence().filter { it.isNotEmpty() }.toList()

    fun readLastNonBlankLine(context: Context, ref: LocalNoteFileRef?): String {
        if (ref == null || !exists(ref)) return ""
        return readText(context, ref).lineSequence().filter { it.isNotBlank() }.lastOrNull().orEmpty()
    }

    fun callLogRef(context: Context, phoneKey: String, createDirs: Boolean): LocalNoteFileRef? {
        if (LocalNotesStorageLocation.usesSelectedFolder(context)) {
            val dir = LocalNotesStorageLocation.safPhoneDir(context, phoneKey, createDirs) ?: return null
            return LocalNotesStorageLocation.fileInSafDir(
                dir,
                LocalNotesStorageLocation.CALL_LOG_FILE,
                "application/octet-stream",
                createDirs,
            )?.let(LocalNoteFileRef::Saf)
        }
        return LocalNoteFileRef.Plain(
            LocalNotesStorageLocation.callLogFile(context, phoneKey, createDirs),
        )
    }

    fun profileRef(context: Context, phoneKey: String, createDirs: Boolean): LocalNoteFileRef? {
        if (LocalNotesStorageLocation.usesSelectedFolder(context)) {
            val dir = LocalNotesStorageLocation.safPhoneDir(context, phoneKey, createDirs) ?: return null
            return LocalNotesStorageLocation.fileInSafDir(
                dir,
                LocalNotesStorageLocation.PROFILE_FILE,
                "application/json",
                createDirs,
            )?.let(LocalNoteFileRef::Saf)
        }
        return LocalNoteFileRef.Plain(
            LocalNotesStorageLocation.profileFile(context, phoneKey, createDirs),
        )
    }

    fun profileRefs(context: Context): List<LocalNoteFileRef> {
        return if (LocalNotesStorageLocation.usesSelectedFolder(context)) {
            LocalNotesStorageLocation.safProfileDocuments(context).map(LocalNoteFileRef::Saf)
        } else {
            LocalNotesStorageLocation.plainProfileFiles(context).map(LocalNoteFileRef::Plain)
        }
    }

    fun callLogRefs(context: Context): List<LocalNoteFileRef> {
        return if (LocalNotesStorageLocation.usesSelectedFolder(context)) {
            LocalNotesStorageLocation.safCallLogDocuments(context).map(LocalNoteFileRef::Saf)
        } else {
            LocalNotesStorageLocation.plainCallLogFiles(context).map(LocalNoteFileRef::Plain)
        }
    }
}
