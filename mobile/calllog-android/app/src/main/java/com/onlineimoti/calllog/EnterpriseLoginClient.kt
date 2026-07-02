package com.onlineimoti.calllog

import android.os.Build
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

internal data class EnterpriseLoginResponse(
    val sessionToken: String,
    val expiresAtMs: Long,
    val accountName: String,
    val userName: String,
    val lookupPath: String,
    val formPath: String,
    val historyPath: String,
)

internal class EnterpriseLoginException(message: String) : IOException(message)

/** Password login for the Play-only company CRM session. */
internal object EnterpriseLoginClient {
    private const val AUTH_PATH = "/relationship-manager/api/mobile_auth.php"
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 10_000

    fun login(identity: String, password: String): EnterpriseLoginResponse {
        val server = BuildConfig.ENTERPRISE_SERVER_BASE_URL.trim().trimEnd('/')
        if (!server.startsWith("https://")) {
            throw EnterpriseLoginException("Служебният сървър не е настроен безопасно.")
        }
        val endpoint = server + AUTH_PATH
        val connection = (URL(endpoint).openConnection() as HttpURLConnection)
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")
            val payload = JSONObject().apply {
                put("username", identity.trim())
                put("password", password)
                put("device_name", deviceName())
                put("app_package", BuildConfig.APPLICATION_ID)
            }
            connection.outputStream.use { output ->
                output.write(payload.toString().toByteArray(Charsets.UTF_8))
            }

            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.use { BufferedReader(InputStreamReader(it, Charsets.UTF_8)).readText() }.orEmpty()
            val json = runCatching { JSONObject(body) }.getOrNull()
            if (status !in 200..299 || json?.optBoolean("ok", false) != true) {
                val message = json?.optString("message").orEmpty().trim()
                    .ifBlank { json?.optString("error").orEmpty().trim() }
                    .ifBlank { "Служебният вход не бе приет (HTTP $status)." }
                throw EnterpriseLoginException(message)
            }
            return parse(json)
        } catch (error: EnterpriseLoginException) {
            throw error
        } catch (error: IOException) {
            throw EnterpriseLoginException("Няма връзка със служебния сървър. Провери интернет връзката и опитай отново.")
        } finally {
            connection.disconnect()
        }
    }

    private fun parse(json: JSONObject): EnterpriseLoginResponse {
        val token = json.optString("session_token").trim()
        val expiresAtMs = json.optLong("expires_at_ms", 0L)
        if (!token.startsWith("rms1_") || expiresAtMs <= System.currentTimeMillis()) {
            throw EnterpriseLoginException("Служебният вход върна невалидна сесия.")
        }
        val paths = json.optJSONObject("paths")
        return EnterpriseLoginResponse(
            sessionToken = token,
            expiresAtMs = expiresAtMs,
            accountName = identityName(json.optJSONObject("account")),
            userName = identityName(json.optJSONObject("user")),
            lookupPath = safePath(paths?.optString("lookup"), "/relationship-manager/api/mobile_lookup.php"),
            formPath = safePath(paths?.optString("form"), ConfigStore.DEFAULT_FORM_PATH),
            historyPath = safePath(paths?.optString("history"), ConfigStore.DEFAULT_HISTORY_PATH),
        )
    }

    private fun identityName(value: JSONObject?): String {
        if (value == null) return ""
        return listOf("name", "display_name", "full_name", "email", "username", "id")
            .asSequence()
            .map { key -> value.optString(key).trim() }
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
    }

    private fun safePath(value: String?, fallback: String): String {
        val candidate = value.orEmpty().trim()
        return candidate.takeIf { it.startsWith('/') && !it.startsWith("//") } ?: fallback
    }

    private fun deviceName(): String {
        return listOf(Build.MANUFACTURER, Build.MODEL)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" ")
            .take(120)
    }
}
