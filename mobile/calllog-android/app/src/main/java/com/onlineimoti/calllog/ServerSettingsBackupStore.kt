package com.onlineimoti.calllog

import org.json.JSONObject
import java.io.File

/** Keeps the server connection settings in the shared Call Report folder across reinstalls. */
internal object ServerSettingsBackupStore {
    private const val FILE_NAME = "server-settings.json"
    private const val APP_NAME = "Call Report"
    private const val VERSION = 1

    sealed interface SaveResult {
        data class Saved(val path: String) : SaveResult
        data class Failed(val message: String) : SaveResult
        data object StorageUnavailable : SaveResult
    }

    sealed interface RestoreResult {
        data class Restored(val config: AppConfig, val path: String) : RestoreResult
        data class NotFound(val path: String) : RestoreResult
        data class Failed(val message: String) : RestoreResult
        data object StorageUnavailable : RestoreResult
    }

    fun backupPath(): String = backupFile().absolutePath

    fun canUseBackupFile(): Boolean = LocalNotesFileStore.canUsePublicFolder()

    fun save(config: AppConfig): SaveResult {
        if (!canUseBackupFile()) return SaveResult.StorageUnavailable

        return runCatching {
            val file = backupFile()
            file.parentFile?.mkdirs()
            val content = JSONObject()
                .put("v", VERSION)
                .put("app", APP_NAME)
                .put("saved_at", System.currentTimeMillis())
                .put(
                    "server_settings",
                    JSONObject()
                        .put("remote_enabled", config.remoteEnabled)
                        .put("base_url", config.baseUrl.trim())
                        .put("access_token", config.accessToken.trim())
                        .put("lookup_path", config.lookupPath.trim())
                        .put("form_path", config.formPath.trim())
                        .put("history_path", config.historyPath.trim()),
                )
                .toString(2)
            writeAtomically(file, content)
            SaveResult.Saved(file.absolutePath)
        }.getOrElse { error ->
            SaveResult.Failed(error.message.orEmpty())
        }
    }

    fun restore(currentConfig: AppConfig): RestoreResult {
        if (!canUseBackupFile()) return RestoreResult.StorageUnavailable

        val file = backupFile()
        if (!file.exists()) return RestoreResult.NotFound(file.absolutePath)

        return runCatching {
            val root = JSONObject(file.readText())
            if (root.optString("app") != APP_NAME) error("Unsupported backup file")
            val settings = root.optJSONObject("server_settings") ?: error("Server settings are missing")
            RestoreResult.Restored(
                config = currentConfig.copy(
                    remoteEnabled = settings.booleanOrCurrent("remote_enabled", currentConfig.remoteEnabled),
                    baseUrl = settings.stringOrCurrent("base_url", currentConfig.baseUrl),
                    accessToken = settings.stringOrCurrent("access_token", currentConfig.accessToken),
                    lookupPath = settings.stringOrCurrent("lookup_path", currentConfig.lookupPath),
                    formPath = settings.stringOrCurrent("form_path", currentConfig.formPath),
                    historyPath = settings.stringOrCurrent("history_path", currentConfig.historyPath),
                ),
                path = file.absolutePath,
            )
        }.getOrElse { error ->
            RestoreResult.Failed(error.message.orEmpty())
        }
    }

    private fun backupFile(): File = File(LocalNotesFileStore.publicRootPath(), FILE_NAME)

    private fun writeAtomically(target: File, content: String) {
        val temporary = File(target.parentFile, "$FILE_NAME.tmp")
        temporary.writeText(content)
        if (!temporary.renameTo(target)) {
            temporary.copyTo(target, overwrite = true)
            temporary.delete()
        }
    }

    private fun JSONObject.stringOrCurrent(key: String, currentValue: String): String {
        if (!has(key) || isNull(key)) return currentValue
        return optString(key).trim()
    }

    private fun JSONObject.booleanOrCurrent(key: String, currentValue: Boolean): Boolean {
        return if (!has(key) || isNull(key)) currentValue else optBoolean(key, currentValue)
    }
}
