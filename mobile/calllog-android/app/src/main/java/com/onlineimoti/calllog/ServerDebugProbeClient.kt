package com.onlineimoti.calllog

import android.net.Uri
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

internal data class ServerDebugProbeResponse(
    val code: Int,
    val body: String,
)

internal object ServerDebugProbeClient {
    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 8_000

    fun jsonGet(
        config: AppConfig,
        label: String,
        path: String,
        params: Map<String, String> = emptyMap(),
    ): ServerDebugTestResult {
        return runProbe(label) {
            val response = request(config, "GET", path, params)
            val json = JSONObject(response.body)
            require(json.optBoolean("ok", false)) { json.optString("error", "ok=false") }
            "HTTP ${response.code} · JSON ok"
        }
    }

    fun jsonPost(
        config: AppConfig,
        label: String,
        path: String,
        body: JSONObject,
    ): ServerDebugTestResult {
        return runProbe(label) {
            val response = request(config, "POST", path, jsonBody = body.toString())
            val json = JSONObject(response.body)
            require(json.optBoolean("ok", false)) { json.optString("error", "ok=false") }
            "HTTP ${response.code} · JSON ok"
        }
    }

    fun formPost(
        config: AppConfig,
        label: String,
        path: String,
        params: Map<String, String>,
    ): ServerDebugTestResult {
        return runProbe(label) {
            val response = formRequest(config, path, params)
            val json = JSONObject(response.body)
            require(json.optBoolean("ok", false)) { json.optString("error", "ok=false") }
            "HTTP ${response.code} · записът е потвърден"
        }
    }

    fun formRequest(
        config: AppConfig,
        path: String,
        params: Map<String, String>,
    ): ServerDebugProbeResponse {
        val encoded = params.entries.joinToString("&") { (key, value) ->
            "${Uri.encode(key)}=${Uri.encode(value)}"
        }
        return request(
            config = config,
            method = "POST",
            path = path,
            rawBody = encoded,
            contentType = "application/x-www-form-urlencoded; charset=utf-8",
        )
    }

    fun htmlGet(
        config: AppConfig,
        label: String,
        path: String,
        params: Map<String, String>,
    ): ServerDebugTestResult {
        return runProbe(label) {
            val response = request(config, "GET", path, params)
            require(
                response.body.contains("<html", ignoreCase = true) ||
                    response.body.contains("<!doctype html", ignoreCase = true),
            ) {
                "HTML страница не е разпозната"
            }
            "HTTP ${response.code} · HTML ok"
        }
    }

    fun runProbe(label: String, action: () -> String): ServerDebugTestResult {
        return try {
            ServerDebugTestResult(label, true, action())
        } catch (error: Throwable) {
            ServerDebugTestResult(label, false, safeMessage(error))
        }
    }

    fun request(
        config: AppConfig,
        method: String,
        path: String,
        params: Map<String, String> = emptyMap(),
        jsonBody: String? = null,
        rawBody: String? = null,
        contentType: String = "application/json; charset=utf-8",
    ): ServerDebugProbeResponse {
        val endpoint = buildEndpoint(config.baseUrl, path, params)
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json, text/html;q=0.9, */*;q=0.1")
            connection.setRequestProperty("X-Callreport-Token", config.accessToken)
            val bodyToWrite = jsonBody ?: rawBody
            if (bodyToWrite != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", contentType)
                connection.outputStream.use { output ->
                    output.write(bodyToWrite.toByteArray(Charsets.UTF_8))
                }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.use { input ->
                BufferedReader(InputStreamReader(input, Charsets.UTF_8)).readText()
            }.orEmpty()
            if (code !in 200..299) throw IOException("HTTP $code${extractServerError(body)}")
            return ServerDebugProbeResponse(code, body)
        } finally {
            connection.disconnect()
        }
    }

    private fun extractServerError(body: String): String {
        val json = runCatching { JSONObject(body) }.getOrNull()
        val error = json?.optString("error").orEmpty().trim()
        return if (error.isBlank()) "" else ": $error"
    }

    private fun safeMessage(error: Throwable): String {
        return error.message
            ?.lineSequence()
            ?.firstOrNull()
            ?.take(150)
            .orEmpty()
            .ifBlank { "неуспешен тест" }
    }
}
