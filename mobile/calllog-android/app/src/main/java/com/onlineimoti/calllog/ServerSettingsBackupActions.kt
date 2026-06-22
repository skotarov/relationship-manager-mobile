package com.onlineimoti.calllog

import android.content.Context
import android.net.Uri

internal object ServerSettingsBackupActions {
    fun write(context: Context, uri: Uri, config: AppConfig): Result<Unit> {
        return runCatching {
            val json = ServerSettingsBackupStore.createJson(config)
            context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                output.write(json.toByteArray(Charsets.UTF_8))
            } ?: error("Cannot open backup file")
        }
    }

    fun read(context: Context, uri: Uri, currentConfig: AppConfig): ServerSettingsBackupStore.RestoreResult {
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                input.readBytes().toString(Charsets.UTF_8)
            } ?: error("Cannot open backup file")
        }.fold(
            onSuccess = { content -> ServerSettingsBackupStore.restoreJson(currentConfig, content) },
            onFailure = { error -> ServerSettingsBackupStore.RestoreResult.Failed(error.message.orEmpty()) },
        )
    }
}
