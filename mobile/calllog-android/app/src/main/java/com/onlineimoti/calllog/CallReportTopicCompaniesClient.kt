package com.onlineimoti.calllog

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

internal data class CallReportTopicCompany(
    val id: String,
    val name: String,
)

/** Loads the selectable server destinations for a CRM/unknown contact note. */
internal object CallReportTopicCompaniesClient {
    private const val PATH = "/relationship-manager/config.php"
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 10_000

    fun fetch(config: AppConfig): List<CallReportTopicCompany> {
        // Server-off mode must remain completely local and must not open a connection.
        if (!CallReportRemoteAccess.isReady(config)) return emptyList()

        val endpoint = config.baseUrl.trim().trimEnd('/') + PATH
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("X-Relationship-Manager-Token", config.accessToken)

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.use { BufferedReader(InputStreamReader(it, Charsets.UTF_8)).readText() }.orEmpty()
            val response = runCatching { JSONObject(body) }.getOrNull()
            if (code !in 200..299 || response?.optBoolean("ok", false) != true) {
                throw IOException(response?.optString("error").orEmpty().ifBlank { "Company destinations request was rejected." })
            }

            val companies = response.optJSONArray("companies") ?: JSONArray().apply {
                response.optJSONObject("company")?.let(::put)
            }
            return buildList {
                for (index in 0 until companies.length()) {
                    val item = companies.optJSONObject(index) ?: continue
                    val id = item.optString("id").trim()
                    val name = item.optString("name").trim().ifBlank { id }
                    if (id.isNotBlank()) add(CallReportTopicCompany(id, name))
                }
            }.distinctBy { it.id }.sortedBy { it.name.lowercase() }
        } finally {
            connection.disconnect()
        }
    }
}
