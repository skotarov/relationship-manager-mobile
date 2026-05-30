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
            } ?: error("Не може да се отвори файлът за запис.")
            setStatus("Архивът е създаден успешно.")
            Toast.makeText(context, "Архивът е създаден", Toast.LENGTH_SHORT).show()
        }.onFailure { error ->
            val message = "Не успях да създам архив: ${error.message.orEmpty()}".trim()
            setStatus(message)
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    fun askRestoreMode(context: Context, uri: Uri, setStatus: (String) -> Unit) {
        AlertDialog.Builder(context)
            .setTitle("Връщане на архив")
            .setMessage("Как да се върне архивът?")
            .setPositiveButton("Изчисти и върни") { _, _ ->
                restoreArchive(context, uri, LocalNotesArchiveManager.RestoreMode.ClearAndRestore, setStatus)
            }
            .setNegativeButton("Добави") { _, _ ->
                restoreArchive(context, uri, LocalNotesArchiveManager.RestoreMode.Merge, setStatus)
            }
            .setNeutralButton("Отказ", null)
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
            } ?: error("Не може да се прочете избраният файл.")
            val summary = LocalNotesArchiveManager.restoreArchiveJson(context, json, mode)
            val modeText = when (summary.mode) {
                LocalNotesArchiveManager.RestoreMode.ClearAndRestore -> "изчистен и върнат"
                LocalNotesArchiveManager.RestoreMode.Merge -> "добавен към наличните данни"
            }
            val message = "Архивът е $modeText. Основни бележки: ${summary.generalNotes}, файлове: ${summary.files}."
            setStatus(message)
            Toast.makeText(context, "Архивът е върнат", Toast.LENGTH_SHORT).show()
        }.onFailure { error ->
            val message = "Не успях да върна архив: ${error.message.orEmpty()}".trim()
            setStatus(message)
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }
}
