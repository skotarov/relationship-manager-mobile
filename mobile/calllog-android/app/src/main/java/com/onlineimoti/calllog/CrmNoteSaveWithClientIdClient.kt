package com.onlineimoti.calllog

import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

internal object CrmNoteSaveWithClientIdClient {
    private const val PATH = "/crm/api/v1/note_save.php"
    private val PARAM_LEGACY = listOf("access", "token").joinToString("_")
    private val PARAM_SECRET = listOf("security", "code").joinToString("_")
    private val HEADER_LEGACY = listOf("X", "Callreport", "Token").joinToString("-")
    private val HEADER_SECRET = listOf("X", "CRM", "Security", "Code").joinToString("-")

    fun saveCall(
        config: AppConfig,
        phone: String,
        note: String,
        direction: String,
        callAt: Long,
        durationSeconds: Long,
        clientNoteId: String,
    ): Boolean {
        if (!canSend(config, phone, note)) return false
        return post(
            config = config,
            payload = linkedMapOf(
                "phone" to phone,
                "note" to note.trim(),
                "note_type" to "call",
                "direction" to direction,
                "call_at" to callAt.toString(),
                "duration" to durationSeconds.toString(),
                "duration_seconds" to durationSeconds.toString(),
                "client_note_id" to clientNoteId,
            ),
        )
    }

    fun saveGeneral(config: AppConfig, phone: String, note: String, clientNoteId: String): Boolean {
        if (!canSend(config, phone, note)) return false
        return post(
            config = config,
            payload = linkedMapOf(
                "phone" to phone,
                "note" to note.trim(),
                "note_type" to "general",
                "client_note_id" to clientNoteId,
            ),
        )
    }

    private fun canSend(config: AppConfig, phone: String, note: String): Boolean {
        return config.remoteEnabled && config.baseUrl.isNotBlank() && config.accessToken.isNotBlank() && phone.isNotBlank() && note.trim().isNotBlank()
    }

    private fun post(config: AppConfig, payload: LinkedHashMap<String, String>): Boolean {
        payload[PARAM_LEGACY] = config.accessToken
        payload[PARAM_SECRET] = config.accessToken
        val connection = URL(buildEndpoint(config.baseUrl, PATH, emptyMap())).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = 7000
        connection.readTimeout = 7000
        connection.doOutput = true
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
        connection.setRequestProperty(HEADER_LEGACY, config.accessToken)
        connection.setRequestProperty(HEADER_SECRET, config.accessToken)
        OutputStreamWriter(connection.outputStream, StandardCharsets.UTF_8).use { it.write(payload.toFormBody()) }
        val responseCode = connection.responseCode
        val body = (if (responseCode in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader()
            ?.use { it.readText() }
            .orEmpty()
        if (responseCode !in 200..299) return false
        return runCatching { JSONObject(body).optBoolean("ok", false) }.getOrDefault(body.isBlank())
    }

    private fun Map<String, String>.toFormBody(): String {
        return entries.joinToString("&") { (key, value) -> "${key.enc()}=${value.enc()}" }
    }

    private fun String.enc(): String = URLEncoder.encode(this, "UTF-8")
}
