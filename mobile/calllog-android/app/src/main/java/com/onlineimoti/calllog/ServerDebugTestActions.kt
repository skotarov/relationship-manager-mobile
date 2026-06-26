package com.onlineimoti.calllog

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Explicit debug probes for the deployed Call Report server contract.
 * Read-only probes never write. Sync and submit are opt-in because they intentionally
 * create/update a clearly marked deterministic test event for the configured test phone.
 */
internal data class ServerDebugTestResult(
    val label: String,
    val success: Boolean,
    val detail: String,
)

internal object ServerDebugTestActions {
    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 8_000
    private const val API_ROOT = "/broker/callreport"

    private data class WriteTestCompany(
        val id: String,
        val name: String,
    )

    fun runAll(
        context: Context,
        config: AppConfig,
        phone: String,
        direction: String,
        allowWriteTests: Boolean,
    ): List<ServerDebugTestResult> {
        val validation = validate(config, phone)
        if (validation != null) return listOf(validation)

        return buildList {
            add(testConfig(config))
            add(testLookup(config, phone, direction))
            add(testNotesLookup(config, phone))
            add(testHistoryLookup(config, phone, direction))
            add(testPropertySearch(config, phone))
            add(testFormPage(config, phone, direction))
            add(testHistoryPage(config, phone))
            if (allowWriteTests) {
                add(testSync(context, config, phone, direction))
                add(testSubmit(context, config, phone, direction))
            } else {
                add(ServerDebugTestResult("sync.php", true, "пропуснат — тестовите записи не са разрешени"))
                add(ServerDebugTestResult("submit.php", true, "пропуснат — тестовите записи не са разрешени"))
            }
        }
    }

    fun testConfig(config: AppConfig): ServerDebugTestResult = jsonGet(config, "config.php", "$API_ROOT/config.php")

    fun testLookup(config: AppConfig, phone: String, direction: String): ServerDebugTestResult {
        return jsonGet(
            config = config,
            label = "lookup.php",
            path = config.lookupPath,
            params = lookupContext(config, phone, direction, "lookup"),
        )
    }

    fun testNotesLookup(config: AppConfig, phone: String): ServerDebugTestResult {
        return jsonPost(
            config = config,
            label = "notes_lookup.php",
            path = "$API_ROOT/notes_lookup.php",
            body = JSONObject().put("phones", JSONArray().put(phone)),
        )
    }

    fun testHistoryLookup(config: AppConfig, phone: String, direction: String): ServerDebugTestResult {
        return jsonGet(
            config = config,
            label = "history_lookup.php",
            path = "$API_ROOT/history_lookup.php",
            params = linkedMapOf("phone" to phone, "direction" to direction),
        )
    }

    fun testPropertySearch(config: AppConfig, query: String): ServerDebugTestResult {
        return jsonGet(
            config = config,
            label = "property_search.php",
            path = "$API_ROOT/property_search.php",
            params = linkedMapOf("q" to query),
        )
    }

    fun testFormPage(config: AppConfig, phone: String, direction: String): ServerDebugTestResult {
        return htmlGet(
            config = config,
            label = "form.php",
            path = config.formPath,
            params = formContext(phone, direction),
        )
    }

    fun testHistoryPage(config: AppConfig, phone: String): ServerDebugTestResult {
        return htmlGet(
            config = config,
            label = "history.php",
            path = config.historyPath,
            params = linkedMapOf("phone" to phone),
        )
    }

    fun testSync(context: Context, config: AppConfig, phone: String, direction: String): ServerDebugTestResult {
        return runProbe("sync.php (тестов запис)") {
            val company = resolveWriteTestCompany(config, phone, direction)
            val clientEventId = debugEventId(CallReportInstallationId.get(context), "sync")
            val event = JSONObject().apply {
                put("client_event_id", clientEventId)
                put("company_id", company.id)
                put("communication_type", "phone")
                put("direction", direction)
                put("status", "answered")
                put("phone", phone)
                put("contact_name", "Android debug test")
                put("occurred_at_ms", System.currentTimeMillis())
                put("duration_seconds", 0)
                put("source", JSONObject().apply {
                    put("channel", "android_debug")
                    put("device_id", CallReportInstallationId.get(context))
                    put("provider_row_id", "debug-sync")
                    put("app_version", BuildConfig.VERSION_NAME)
                })
            }
            val response = request(
                config = config,
                method = "POST",
                path = "$API_ROOT/sync.php",
                jsonBody = JSONObject().apply {
                    put("schema_version", 1)
                    put("events", JSONArray().put(event))
                }.toString(),
            )
            val json = JSONObject(response.body)
            require(json.optBoolean("ok", false)) { json.optString("error", "ok=false") }
            "HTTP ${response.code} · записът е потвърден · ${company.name}"
        }
    }

    fun testSubmit(context: Context, config: AppConfig, phone: String, direction: String): ServerDebugTestResult {
        return runProbe("submit.php (тестова бележка)") {
            val company = resolveWriteTestCompany(config, phone, direction)
            val clientEventId = debugEventId(CallReportInstallationId.get(context), "submit")
            val params = linkedMapOf(
                "format" to "json",
                "company_id" to company.id,
                "phone" to phone,
                "direction" to direction,
                "communication_type" to "phone",
                "status" to "answered",
                "contact_name" to "Android debug test",
                "call_at" to System.currentTimeMillis().toString(),
                "duration" to "0",
                "client_event_id" to clientEventId,
                "notes" to "[TEST] Запис от Debug › Тест на сървъра. Може да се изтрие.",
            )
            val response = formRequest(config, "$API_ROOT/submit.php", params)
            val json = JSONObject(response.body)
            require(json.optBoolean("ok", false)) { json.optString("error", "ok=false") }
            "HTTP ${response.code} · записът е потвърден · ${company.name}"
        }
    }

    fun buildFormUrl(context: Context, config: AppConfig, phone: String, direction: String): String {
        val params = formContext(phone, direction).toMutableMap()
        params["access_token"] = config.accessToken
        params["client_event_id"] = debugEventId(CallReportInstallationId.get(context), "form")
        return buildEndpoint(config.baseUrl, config.formPath, params)
    }

    fun buildHistoryUrl(config: AppConfig, phone: String): String {
        return buildEndpoint(
            config.baseUrl,
            config.historyPath,
            linkedMapOf("phone" to phone, "access_token" to config.accessToken),
        )
    }

    private fun validate(config: AppConfig, phone: String): ServerDebugTestResult? {
        return when {
            !config.remoteEnabled -> ServerDebugTestResult("Сървър", false, "включи „Сървър“ в настройките")
            config.baseUrl.isBlank() -> ServerDebugTestResult("Сървър", false, "липсва Base URL")
            config.accessToken.isBlank() -> ServerDebugTestResult("Сървър", false, "липсва Access token")
            phone.isBlank() -> ServerDebugTestResult("Сървър", false, "липсва тестов телефон")
            else -> null
        }
    }

    private fun lookupContext(
        config: AppConfig,
        phone: String,
        direction: String,
        operation: String,
    ): LinkedHashMap<String, String> = linkedMapOf(
        "phone" to phone,
        "direction" to direction,
        "history_limit" to "3",
        "communication_type" to "phone",
        "status" to "answered",
        "contact_name" to "Android server test",
        "call_at" to System.currentTimeMillis().toString(),
        "duration" to "0",
        "client_event_id" to debugEventId(contextId(config), operation),
    )

    private fun resolveWriteTestCompany(
        config: AppConfig,
        phone: String,
        direction: String,
    ): WriteTestCompany {
        val response = request(
            config = config,
            method = "GET",
            path = config.lookupPath,
            params = lookupContext(config, phone, direction, "write-company"),
        )
        val json = JSONObject(response.body)
        require(json.optBoolean("ok", false)) { json.optString("error", "lookup.php върна ok=false") }

        val primaryCompany = json.optJSONObject("company")
        val primaryId = primaryCompany?.optString("id").orEmpty().trim()
        if (primaryId.isNotBlank()) {
            return WriteTestCompany(
                id = primaryId,
                name = primaryCompany?.optString("name").orEmpty().trim().ifBlank { primaryId },
            )
        }

        val companies = json.optJSONArray("companies")
        for (index in 0 until (companies?.length() ?: 0)) {
            val company = companies?.optJSONObject(index) ?: continue
            val id = company.optString("id").trim()
            if (id.isNotBlank()) {
                return WriteTestCompany(
                    id = id,
                    name = company.optString("name").trim().ifBlank { id },
                )
            }
        }

        throw IllegalStateException("lookup.php не върна достъпна фирма за тестовия запис.")
    }

    private fun jsonGet(
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

    private fun jsonPost(
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

    private fun formPost(
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

    private fun formRequest(
        config: AppConfig,
        path: String,
        params: Map<String, String>,
    ): ProbeResponse {
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

    private fun htmlGet(
        config: AppConfig,
        label: String,
        path: String,
        params: Map<String, String>,
    ): ServerDebugTestResult {
        return runProbe(label) {
            val response = request(config, "GET", path, params)
            require(response.body.contains("<html", ignoreCase = true) || response.body.contains("<!doctype html", ignoreCase = true)) {
                "HTML страница не е разпозната"
            }
            "HTTP ${response.code} · HTML ok"
        }
    }

    private fun runProbe(label: String, action: () -> String): ServerDebugTestResult {
        return try {
            ServerDebugTestResult(label, true, action())
        } catch (error: Throwable) {
            ServerDebugTestResult(label, false, safeMessage(error))
        }
    }

    private data class ProbeResponse(val code: Int, val body: String)

    private fun request(
        config: AppConfig,
        method: String,
        path: String,
        params: Map<String, String> = emptyMap(),
        jsonBody: String? = null,
        rawBody: String? = null,
        contentType: String = "application/json; charset=utf-8",
    ): ProbeResponse {
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
                connection.outputStream.use { output -> output.write(bodyToWrite.toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.use { input -> BufferedReader(InputStreamReader(input, Charsets.UTF_8)).readText() }.orEmpty()
            if (code !in 200..299) throw IOException("HTTP $code${extractServerError(body)}")
            return ProbeResponse(code, body)
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
        return error.message?.lineSequence()?.firstOrNull()?.take(150).orEmpty().ifBlank { "неуспешен тест" }
    }

    private fun formContext(phone: String, direction: String): LinkedHashMap<String, String> = linkedMapOf(
        "phone" to phone,
        "direction" to direction,
        "communication_type" to "phone",
        "status" to "answered",
        "contact_name" to "Android debug test",
        "call_at" to System.currentTimeMillis().toString(),
        "duration" to "0",
    )

    private fun debugEventId(deviceId: String, operation: String): String = "$deviceId:debug-server-test:$operation"
    private fun contextId(config: AppConfig): String = config.baseUrl.trim().ifBlank { "server" }.hashCode().toString()
}
