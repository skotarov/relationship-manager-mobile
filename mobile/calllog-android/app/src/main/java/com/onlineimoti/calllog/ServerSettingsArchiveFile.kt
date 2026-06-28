package com.onlineimoti.calllog

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

/**
 * Stores the PIN-encrypted cloud settings archive in the same protected
 * app-private .callreport root used by local notes. A legacy archive can be
 * imported once through the Android document picker and is then kept locally.
 */
internal object ServerSettingsArchiveFile {
    private const val FILE_NAME = "callreport-server-settings.json"

    fun path(context: Context): String = archiveFile(context).absolutePath
    fun exists(context: Context): Boolean = archiveFile(context).isFile

    fun save(context: Context, config: AppConfig, code: String): Result<String> {
        return runCatching {
            writeEncryptedArchive(context, ServerSettingsBackupStore.createEncryptedJson(config, code))
            path(context)
        }
    }

    fun restore(context: Context, currentConfig: AppConfig, code: String): ServerSettingsBackupStore.RestoreResult {
        return runCatching {
            val file = archiveFile(context)
            if (!file.exists()) {
                error("Архивният файл не е намерен в локалната папка за бележки: ${file.absolutePath}")
            }
            ServerSettingsBackupStore.restoreEncryptedJson(currentConfig, file.readText(), code)
        }.getOrElse { error ->
            ServerSettingsBackupStore.RestoreResult.Failed(error.message.orEmpty())
        }
    }

    fun importAndRestore(
        context: Context,
        uri: Uri,
        currentConfig: AppConfig,
        code: String,
    ): ServerSettingsBackupStore.RestoreResult {
        return runCatching {
            val input = context.contentResolver.openInputStream(uri)
                ?: error("Не може да се отвори избраният архивен файл.")
            val content = InputStreamReader(input, StandardCharsets.UTF_8).use { reader -> reader.readText() }
            when (val result = ServerSettingsBackupStore.restoreEncryptedJson(currentConfig, content, code)) {
                is ServerSettingsBackupStore.RestoreResult.Restored -> {
                    writeEncryptedArchive(context, content)
                    result
                }
                is ServerSettingsBackupStore.RestoreResult.Failed -> result
            }
        }.getOrElse { error ->
            ServerSettingsBackupStore.RestoreResult.Failed(error.message.orEmpty())
        }
    }

    private fun writeEncryptedArchive(context: Context, content: String) {
        val target = archiveFile(context)
        target.parentFile?.mkdirs()
        target.writeText(content)
    }

    private fun archiveFile(context: Context): File = File(
        LocalNotesFileStore.appPrivateRoot(context),
        FILE_NAME,
    )
}
