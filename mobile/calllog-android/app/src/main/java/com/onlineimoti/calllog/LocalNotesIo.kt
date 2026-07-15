package com.onlineimoti.calllog

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile

internal sealed class LocalNoteFileRef {
    data class Plain(val file: File) : LocalNoteFileRef()
    data class Saf(val document: DocumentFile) : LocalNoteFileRef()
}

internal object LocalNotesIo {
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

    fun callLogRef(context: Context, phoneKey: String, createDirs: Boolean): LocalNoteFileRef? {
        if (LocalNotesWorkspace.usesSelectedFolder(context)) {
            val dir = LocalNotesWorkspace.safPhoneDir(context, phoneKey, createDirs) ?: return null
            return LocalNotesWorkspace.fileInSafDir(
                dir,
                LocalNotesWorkspace.CALL_LOG_FILE,
                "application/octet-stream",
                createDirs,
            )?.let(LocalNoteFileRef::Saf)
        }
        return LocalNoteFileRef.Plain(
            LocalNotesWorkspace.callLogFile(context, phoneKey, createDirs),
        )
    }

    fun profileRef(context: Context, phoneKey: String, createDirs: Boolean): LocalNoteFileRef? {
        if (LocalNotesWorkspace.usesSelectedFolder(context)) {
            val dir = LocalNotesWorkspace.safPhoneDir(context, phoneKey, createDirs) ?: return null
            return LocalNotesWorkspace.fileInSafDir(
                dir,
                LocalNotesWorkspace.PROFILE_FILE,
                "application/json",
                createDirs,
            )?.let(LocalNoteFileRef::Saf)
        }
        return LocalNoteFileRef.Plain(
            LocalNotesWorkspace.profileFile(context, phoneKey, createDirs),
        )
    }

    fun profileRefs(context: Context): List<LocalNoteFileRef> {
        if (LocalNotesWorkspace.usesSelectedFolder(context)) {
            return LocalNotesWorkspace.safNotesDirs(context, createDirs = false)
                .flatMap { notes -> LocalNotesWorkspace.walkSafFiles(notes).toList() }
                .filter {
                    it.isFile && LocalNotesWorkspace.safFileNameMatches(
                        it.name.orEmpty(),
                        LocalNotesWorkspace.PROFILE_FILE,
                    )
                }
                .distinctBy { it.uri }
                .map(LocalNoteFileRef::Saf)
        }
        val notes = File(LocalNotesWorkspace.plainRoot(context), LocalNotesWorkspace.NOTES_DIR)
        if (!notes.exists()) return emptyList()
        return notes.walkTopDown()
            .filter {
                it.isFile && (
                    it.name == LocalNotesWorkspace.PROFILE_FILE ||
                        it.name == LocalNotesWorkspace.PROFILE_FILE_JSON
                    )
            }
            .map(LocalNoteFileRef::Plain)
            .toList()
    }

    fun callLogRefs(context: Context): List<LocalNoteFileRef> {
        if (LocalNotesWorkspace.usesSelectedFolder(context)) {
            return LocalNotesWorkspace.safNotesDirs(context, createDirs = false)
                .flatMap { notes -> LocalNotesWorkspace.walkSafFiles(notes).toList() }
                .filter {
                    it.isFile && LocalNotesWorkspace.safFileNameMatches(
                        it.name.orEmpty(),
                        LocalNotesWorkspace.CALL_LOG_FILE,
                    )
                }
                .distinctBy { it.uri }
                .map(LocalNoteFileRef::Saf)
        }
        val notes = File(LocalNotesWorkspace.plainRoot(context), LocalNotesWorkspace.NOTES_DIR)
        if (!notes.exists()) return emptyList()
        return notes.walkTopDown()
            .filter {
                it.isFile && (
                    it.name == LocalNotesWorkspace.CALL_LOG_FILE ||
                        it.name == LocalNotesWorkspace.CALL_LOG_FILE_TEXT
                    )
            }
            .map(LocalNoteFileRef::Plain)
            .toList()
    }

    fun sameCall(json: JSONObject, callAt: Long, direction: String): Boolean {
        if (callAt <= 0L) return false
        val sameCallAt = json.optLong("call_at", 0L) == callAt
        if (!sameCallAt) return false
        val rowDirection = json.optString("direction")
        return direction.isBlank() ||
            rowDirection.isBlank() ||
            rowDirection == direction ||
            exactTimestampWins(callAt)
    }

    fun readLastNonBlankLine(context: Context, ref: LocalNoteFileRef?): String {
        ref ?: return ""
        return when (ref) {
            is LocalNoteFileRef.Plain -> readLastNonBlankLine(ref.file)
            is LocalNoteFileRef.Saf -> readText(context, ref)
                .lineSequence()
                .lastOrNull { it.isNotBlank() }
                ?.trim()
                .orEmpty()
        }
    }

    private fun exactTimestampWins(callAt: Long): Boolean = callAt > 0L

    private fun readLastNonBlankLine(file: File): String {
        if (!file.exists() || file.length() <= 0L) return ""
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                var pointer = raf.length() - 1
                val bytes = ArrayList<Byte>()
                while (pointer >= 0) {
                    raf.seek(pointer)
                    val value = raf.readByte()
                    if ((value == '\n'.code.toByte() || value == '\r'.code.toByte()) &&
                        bytes.isNotEmpty()
                    ) break
                    if (value != '\n'.code.toByte() && value != '\r'.code.toByte()) {
                        bytes.add(value)
                    }
                    pointer -= 1
                }
                bytes.asReversed().toByteArray().toString(Charsets.UTF_8).trim()
            }
        }.getOrDefault("")
    }
}
