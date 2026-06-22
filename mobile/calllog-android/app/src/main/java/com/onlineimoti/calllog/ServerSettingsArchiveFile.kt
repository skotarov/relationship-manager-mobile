package com.onlineimoti.calllog

import java.io.File

internal object ServerSettingsArchiveFile {
    private const val FILE_NAME = "callreport-server-settings.json"

    fun path(): String = File(LocalNotesFileStore.publicRootPath(), FILE_NAME).absolutePath

    fun save(config: AppConfig, code: String): Result<String> {
        if (!LocalNotesFileStore.canUsePublicFolder()) {
            return Result.failure(IllegalStateException("Разреши достъп до всички файлове за архива."))
        }
        return runCatching {
            val target = File(LocalNotesFileStore.publicRootPath(), FILE_NAME)
            target.parentFile?.mkdirs()
            target.writeText(ServerSettingsBackupStore.createEncryptedJson(config, code))
            target.absolutePath
        }
    }

    fun restore(currentConfig: AppConfig, code: String): ServerSettingsBackupStore.RestoreResult {
        if (!LocalNotesFileStore.canUsePublicFolder()) {
            return ServerSettingsBackupStore.RestoreResult.Failed("Разреши достъп до всички файлове за архива.")
        }
        return runCatching {
            val file = File(LocalNotesFileStore.publicRootPath(), FILE_NAME)
            if (!file.exists()) error("Архивният файл не е намерен: ${file.absolutePath}")
            ServerSettingsBackupStore.restoreEncryptedJson(currentConfig, file.readText(), code)
        }.getOrElse { error ->
            ServerSettingsBackupStore.RestoreResult.Failed(error.message.orEmpty())
        }
    }
}
