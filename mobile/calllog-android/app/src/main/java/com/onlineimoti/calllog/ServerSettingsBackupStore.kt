package com.onlineimoti.calllog

import org.json.JSONObject

/** Creates and restores a user-selected server settings backup file. */
internal object ServerSettingsBackupStore {
    private const val FILE_NAME = "callreport-server-settings.json"
    private const val APP_NAME = "Call Report"
    private const val VERSION = 1

    sealed interface RestoreResult {
        data class Restored(val config: AppConfig) : RestoreResult
        data class Failed(val message: String) : RestoreResult
    }

    fun suggestedFileName(): String = FILE_NAME

    fun createJson(config: AppConfig): String {
        return JSONObject()
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
    }

    fun restoreJson(currentConfig: AppConfig, content: String): RestoreResult {
        return runCatching {
            val root = JSONObject(content)
            if (root.optString("app") != APP_NAME) error("Unsupported backup file")
            val settings = root.optJSONObject("server_settings") ?: error("Server settings are missing")
            RestoreResult.Restored(
                currentConfig.copy(
                    remoteEnabled = settings.booleanOrCurrent("remote_enabled", currentConfig.remoteEnabled),
                    baseUrl = settings.stringOrCurrent("base_url", currentConfig.baseUrl),
                    accessToken = settings.stringOrCurrent("access_token", currentConfig.accessToken),
                    lookupPath = settings.stringOrCurrent("lookup_path", currentConfig.lookupPath),
                    formPath = settings.stringOrCurrent("form_path", currentConfig.formPath),
                    historyPath = settings.stringOrCurrent("history_path", currentConfig.historyPath),
                ),
            )
        }.getOrElse { error ->
            RestoreResult.Failed(error.message.orEmpty())
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
