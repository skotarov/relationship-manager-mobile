package com.onlineimoti.calllog

import android.content.Context
import android.net.Uri

internal object ServerSettingsBackupActions {
    fun write(context: Context, uri: Uri, config: AppConfig, pin: String): Result<Unit> {
        return runCatching {
            val json = ServerSettingsBackupStore.createEncryptedJson(config, pin)
            context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                output.write(json.toByteArray(Charsets.UTF_8))
            } ?: error("Cannot open backup file")
        }
    }

    fun read(
        context: Context,
        uri: Uri,
        currentConfig: AppConfig,
        pin: String,
    ): ServerSettingsBackupStore.RestoreResult {
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                input.readBytes().toString(Charsets.UTF_8)
            } ?: error("Cannot open backup file")
        }.fold(
            onSuccess = { content -> ServerSettingsBackupStore.restoreEncryptedJson(currentConfig, content, pin) },
            onFailure = { error -> ServerSettingsBackupStore.RestoreResult.Failed(error.message.orEmpty()) },
        )
    }
}
