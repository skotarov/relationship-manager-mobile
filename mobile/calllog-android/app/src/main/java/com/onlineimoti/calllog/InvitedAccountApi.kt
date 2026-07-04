package com.onlineimoti.calllog

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

internal object InvitedAccountApi {
    private const val AUTH_PATH = "/relationship-manager/api/auth.php"

    fun register(
        context: Context,
        email: String,
        password: String,
        displayName: String,
        inviteCode: String,
    ): Result<CompanyAccountApi.Session> = runCatching {
        val config = ConfigStore.load(context)
        require(config.baseUrl.isNotBlank()) { "Първо задай Server URL в Настройки." }
        val payload = JSONObject()
            .put("action", "register_invited")
            .put("email", email.trim())
            .put("password", password)
            .put("display_name", displayName.trim())
            .put("invite_code", inviteCode.trim())
            .put("device_name", android.os.Build.MODEL.take(120))
            .toString()
            .toByteArray(StandardCharsets.UTF_8)
        val connection = (URL(buildEndpoint(config.baseUrl, AUTH_PATH, emptyMap())).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 30_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
        }
        try {
            connection.outputStream.use { it.write(payload) }
            val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
            val response = JSONObject(stream?.bufferedReader()?.use { it.readText() }.orEmpty().ifBlank { "{}" })
            if (connection.responseCode !in 200..299 || !response.optBoolean("ok", false)) {
                val error = response.optJSONObject("error")
                throw IllegalStateException(error?.optString("message").orEmpty().ifBlank { "Неуспешно присъединяване към фирмата." })
            }
            val accessToken = response.optString("access_token").trim()
            require(accessToken.isNotBlank()) { "Сървърът не върна валиден access token." }
            val user = response.optJSONObject("user")
            val organization = response.optJSONObject("organization")
            CompanyAccountApi.Session(
                accessToken = accessToken,
                userName = user?.optString("display_name").orEmpty().trim(),
                organizationName = organization?.optString("name").orEmpty().trim(),
                organizationId = organization?.optString("id").orEmpty().trim(),
            )
        } finally {
            connection.disconnect()
        }
    }
}
