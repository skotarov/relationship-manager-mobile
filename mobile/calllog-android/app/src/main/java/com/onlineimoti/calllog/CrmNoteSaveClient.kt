package com.onlineimoti.calllog

import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

internal object CrmNoteSaveClient {
    private const val NOTE_SAVE_PATH = "/crm/api/v1/note_save.php"

    fun saveCallNote(
        config: AppConfig,
        phone: String,
        note: String,
        direction: String,
        callAt: Long,
        durationSeconds: Long,
    ): Boolean {
        if (!config.remoteEnabled || config.baseUrl.isBlank() || config.accessToken.isBlank()) return false
        if (phone.isBlank() || note.trim().isBlank()) return false

        val url = buildEndpoint(config.baseUrl, NOTE_SAVE_PATH, emptyMap())
        val payload = linkedMapOf(
            "phone" to phone,
            "note" to note.trim(),
            "note_type" to "call",
            "direction" to direction,
            "call_at" to callAt.toString(),
            "duration" to durationSeconds.toString(),
            "duration_seconds" to durationSeconds.toString(),
            "access_token" to config.accessToken,
            "security_code" to config.accessToken,
        )
        return postForm(url, config.accessToken, payload)
    }

    fun saveGeneralNote(config: AppConfig, phone: String, note: String): Boolean {
        if (!config.remoteEnabled || config.baseUrl.isBlank() || config.accessToken.isBlank()) return false
        if (phone.isBlank() || note.trim().isBlank()) return false

        val url = buildEndpoint(config.baseUrl, NOTE_SAVE_PATH, emptyMap())
        val payload = linkedMapOf(
            "phone" to phone,
            "note" to note.trim(),
            "note_type" to "general",
            "access_token" to config.accessToken,
            "security_code" to config.accessToken,
        )
        return postForm(url, config.accessToken, payload)
    }

    private fun postForm(url: String, token: String, payload: Map<String, String>): Boolean {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = 7000
        connection.readTimeout = 7000
        connection.doOutput = true
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
        connection.setRequestProperty("X-Callreport-Token", token)
        connection.setRequestProperty("X-CRM-Security-Code", token)
        OutputStreamWriter(connection.outputStream, StandardCharsets.UTF_8).use { writer ->
            writer.write(payload.toFormBody())
        }
        val responseCode = connection.responseCode
        val body = (if (responseCode in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader()
            ?.use { it.readText() }
            .orEmpty()
        if (responseCode !in 200..299) return false
        return runCatching { JSONObject(body).optBoolean("ok", false) }.getOrDefault(body.isBlank())
    }

    private fun Map<String, String>.toFormBody(): String {
        return entries.joinToString("&") { (key, value) ->
            "${key.urlEncode()}=${value.urlEncode()}"
        }
    }

    private fun String.urlEncode(): String = URLEncoder.encode(this, "UTF-8")
}
