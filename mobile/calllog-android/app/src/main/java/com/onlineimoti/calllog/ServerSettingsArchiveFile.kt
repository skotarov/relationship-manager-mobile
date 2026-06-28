package com.onlineimoti.calllog

import android.content.Context
import java.io.File

/**
 * Stores the PIN-encrypted cloud settings archive in the same protected
 * app-private .callreport root used by local notes. No storage permission or
 * document picker is required.
 */
internal object ServerSettingsArchiveFile {
    private const val FILE_NAME = "callreport-server-settings.json"

    fun path(context: Context): String = archiveFile(context).absolutePath

    fun save(context: Context, config: AppConfig, code: String): Result<String> {
        return runCatching {
            val target = archiveFile(context)
            target.parentFile?.mkdirs()
            target.writeText(ServerSettingsBackupStore.createEncryptedJson(config, code))
            target.absolutePath
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

    private fun archiveFile(context: Context): File = File(
        LocalNotesFileStore.appPrivateRoot(context),
        FILE_NAME,
    )
}
