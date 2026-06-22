package com.onlineimoti.calllog

import android.content.Context
import org.json.JSONObject
import java.io.File

internal object PersistentServerSettingsArchive {
    private const val FILE_NAME = "server-settings.json"

    fun path(): String = File(LocalNotesFileStore.publicRootPath(), FILE_NAME).absolutePath

    fun save(context: Context, config: AppConfig): Result<String> {
        if (!LocalNotesFileStore.canUsePublicFolder()) {
            return Result.failure(IllegalStateException("Разреши достъп до всички файлове за архива."))
        }
        return runCatching {
            val file = File(LocalNotesFileStore.publicRootPath(), FILE_NAME)
            file.parentFile?.mkdirs()
            val root = JSONObject()
                .put("v", 1)
                .put("app", "Call Report")
                .put("saved_at", System.currentTimeMillis())
                .put("remote_enabled", config.remoteEnabled)
                .put("base_url", config.baseUrl.trim())
                .put("access_token", config.accessToken.trim())
                .put("lookup_path", config.lookupPath.trim())
                .put("form_path", config.formPath.trim())
                .put("history_path", config.historyPath.trim())
            file.writeText(root.toString(2))
            file.absolutePath
        }
    }

    fun restore(context: Context, current: AppConfig): Result<AppConfig> {
        if (!LocalNotesFileStore.canUsePublicFolder()) {
            return Result.failure(IllegalStateException("Разреши достъп до всички файлове за архива."))
        }
        return runCatching {
            val file = File(LocalNotesFileStore.publicRootPath(), FILE_NAME)
            if (!file.exists()) error("Архивният файл не е намерен: ${file.absolutePath}")
            val root = JSONObject(file.readText())
            require(root.optString("app") == "Call Report") { "Неподдържан архивен файл." }
            current.copy(
                remoteEnabled = root.optBoolean("remote_enabled", current.remoteEnabled),
                baseUrl = root.optString("base_url", current.baseUrl).trim(),
                accessToken = root.optString("access_token", current.accessToken).trim(),
                lookupPath = root.optString("lookup_path", current.lookupPath).trim(),
                formPath = root.optString("form_path", current.formPath).trim(),
                historyPath = root.optString("history_path", current.historyPath).trim(),
            )
        }
    }
}
