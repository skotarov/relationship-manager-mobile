package com.onlineimoti.calllog

import android.content.Context
import android.net.Uri
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

/**
 * Reads and writes the PIN-encrypted cloud settings archive through Android's
 * Storage Access Framework. This works without broad storage permission and
 * can restore archives previously saved in Documents or Downloads.
 */
internal object ServerSettingsArchiveFile {
    fun save(context: Context, uri: Uri, config: AppConfig, code: String): Result<String> {
        return runCatching {
            val encryptedJson = ServerSettingsBackupStore.createEncryptedJson(config, code)
            val output = context.contentResolver.openOutputStream(uri, "wt")
                ?: error("Не може да се отвори избраният файл за запис.")
            OutputStreamWriter(output, StandardCharsets.UTF_8).use { writer ->
                writer.write(encryptedJson)
            }
            displayName(uri)
        }
    }

    fun restore(context: Context, uri: Uri, currentConfig: AppConfig, code: String): ServerSettingsBackupStore.RestoreResult {
        return runCatching {
            val input = context.contentResolver.openInputStream(uri)
                ?: error("Не може да се отвори избраният архивен файл.")
            val content = InputStreamReader(input, StandardCharsets.UTF_8).use { reader -> reader.readText() }
            ServerSettingsBackupStore.restoreEncryptedJson(currentConfig, content, code)
        }.getOrElse { error ->
            ServerSettingsBackupStore.RestoreResult.Failed(error.message.orEmpty())
        }
    }

    private fun displayName(uri: Uri): String {
        return uri.lastPathSegment
            ?.substringAfterLast('/')
            ?.takeIf { it.isNotBlank() }
            ?: ServerSettingsBackupStore.suggestedFileName()
    }
}
