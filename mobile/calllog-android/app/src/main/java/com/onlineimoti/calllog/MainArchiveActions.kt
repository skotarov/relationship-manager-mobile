package com.onlineimoti.calllog

import android.app.AlertDialog
import android.content.Context
import android.net.Uri
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal object MainArchiveActions {
    fun archiveFileName(): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        return "call-log-archive-$stamp.json"
    }

    fun createArchive(context: Context, uri: Uri, setStatus: (String) -> Unit) {
        runCatching {
            val json = LocalNotesArchiveManager.createArchiveJson(context)
            context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                output.write(json.toByteArray(Charsets.UTF_8))
            } ?: error(context.getString(R.string.archive_write_open_failed))
            setStatus(context.getString(R.string.archive_created_status))
            Toast.makeText(context, context.getString(R.string.archive_created_toast), Toast.LENGTH_SHORT).show()
        }.onFailure { error ->
            val message = context.getString(R.string.archive_create_failed, error.message.orEmpty()).trim()
            setStatus(message)
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    fun askRestoreMode(context: Context, uri: Uri, setStatus: (String) -> Unit) {
        AlertDialog.Builder(context)
            .setTitle(R.string.archive_restore_title)
            .setMessage(R.string.archive_restore_question)
            .setPositiveButton(R.string.archive_restore_replace) { _, _ ->
                restoreArchive(context, uri, LocalNotesArchiveManager.RestoreMode.ClearAndRestore, setStatus)
            }
            .setNegativeButton(R.string.archive_restore_merge) { _, _ ->
                restoreArchive(context, uri, LocalNotesArchiveManager.RestoreMode.Merge, setStatus)
            }
            .setNeutralButton(R.string.archive_cancel, null)
            .show()
    }

    private fun restoreArchive(
        context: Context,
        uri: Uri,
        mode: LocalNotesArchiveManager.RestoreMode,
        setStatus: (String) -> Unit,
    ) {
        runCatching {
            val json = context.contentResolver.openInputStream(uri)?.use { input ->
                input.readBytes().toString(Charsets.UTF_8)
            } ?: error(context.getString(R.string.archive_read_failed))
            val summary = LocalNotesArchiveManager.restoreArchiveJson(context, json, mode)
            val modeText = context.getString(
                when (summary.mode) {
                    LocalNotesArchiveManager.RestoreMode.ClearAndRestore -> R.string.archive_mode_replaced
                    LocalNotesArchiveManager.RestoreMode.Merge -> R.string.archive_mode_merged
                },
            )
            val message = context.getString(
                R.string.archive_restore_summary,
                modeText,
                summary.generalNotes,
                summary.files,
            )
            setStatus(message)
            Toast.makeText(context, context.getString(R.string.archive_restored_toast), Toast.LENGTH_SHORT).show()
        }.onFailure { error ->
            val message = context.getString(R.string.archive_restore_failed, error.message.orEmpty()).trim()
            setStatus(message)
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }
}
